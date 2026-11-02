#!/usr/bin/env node
/**
 * ASANNI catalogue seeder: uploads seed/catalogue.json to Firestore `cities` and `areas`.
 *
 * TRUSTED ADMIN TOOL. Run it on YOUR computer, never inside the Android app.
 * It uses the Firebase Admin SDK with a service-account key, which bypasses security rules;
 * that's why ordinary app users can't write the catalogue but this script can.
 *
 *   npm install                                              (once, inside this seed/ folder)
 *   node seed-catalogue.js --dry-run                         (check the data, no upload)
 *   GOOGLE_APPLICATION_CREDENTIALS=/path/key.json node seed-catalogue.js --project YOUR_PROJECT_ID
 *
 * Safe to run again: records are keyed by stable IDs (karachi, karachi-clifton ...) and merged,
 * createdAt is kept for existing records, updatedAt is refreshed. Nothing is deleted:
 * to retire a place, set "isActive": false on it in catalogue.json and re-run.
 *
 * NEVER commit the service-account key, put it in the app, or share it.
 */
'use strict';

const fs = require('fs');
const path = require('path');

/** MUST match TextNormalizer.normalize() in the Android app. */
function normalize(text) {
  if (!text) return '';
  return text
    .normalize('NFKD')
    .replace(/\p{M}+/gu, '')
    .toLowerCase()
    .replace(/[^a-z0-9]/g, '');
}

function fail(msg) {
  console.error('ERROR: ' + msg);
  process.exit(1);
}

/** Builds and validates every document from catalogue.json. Pure function, no network. */
function buildDocuments(catalogue) {
  const countryCode = catalogue.countryCode;
  if (!/^[A-Z]{2}$/.test(countryCode || '')) fail('countryCode must be a 2-letter code like "PK"');

  const idPattern = /^[a-z0-9]+(-[a-z0-9]+)*$/;
  const cities = new Map();
  for (const c of catalogue.cities || []) {
    if (!idPattern.test(c.id || '')) fail(`bad city id "${c.id}" (use lowercase-with-dashes)`);
    if (!c.name || !c.name.trim()) fail(`city ${c.id} has no name`);
    if (cities.has(c.id)) fail(`duplicate city id "${c.id}"`);
    cities.set(c.id, {
      name: c.name.trim(),
      normalizedName: normalize(c.name),
      normalizedAliases: [...new Set((c.aliases || []).map(normalize).filter(Boolean))],
      countryCode,
      isActive: c.isActive !== false,
    });
  }
  if (cities.size === 0) fail('no cities in catalogue.json');

  const areas = new Map();
  const addArea = (cityId, a, isCityWide) => {
    const docId = `${cityId}-${a.id}`;
    if (!idPattern.test(a.id || '')) fail(`bad area id "${a.id}" in ${cityId}`);
    if (!a.name || !a.name.trim()) fail(`area ${docId} has no name`);
    if (areas.has(docId)) fail(`duplicate area id "${docId}"`);
    areas.set(docId, {
      name: a.name.trim(),
      normalizedName: normalize(a.name),
      normalizedAliases: [...new Set((a.aliases || []).map(normalize).filter(Boolean))],
      cityId,
      cityName: cities.get(cityId).name,
      countryCode,
      isCityWide,
      isActive: a.isActive !== false,
    });
  };

  for (const [cityId, list] of Object.entries(catalogue.areas || {})) {
    if (!cities.has(cityId)) fail(`areas listed for unknown city "${cityId}"`);
    for (const a of list) addArea(cityId, a, false);
  }
  const cw = catalogue.cityWideArea;
  if (cw) {
    for (const [cityId, city] of cities) {
      addArea(cityId, { id: cw.idSuffix, name: cw.nameTemplate.replace('{city}', city.name) }, true);
    }
  }
  return { cities, areas };
}

async function upload(db, FieldValue, collection, docs) {
  const ids = [...docs.keys()];
  const existing = new Set();
  for (let i = 0; i < ids.length; i += 300) {
    const refs = ids.slice(i, i + 300).map((id) => db.collection(collection).doc(id));
    const snaps = await db.getAll(...refs);
    snaps.forEach((s) => s.exists && existing.add(s.id));
  }
  let batch = db.batch();
  let n = 0;
  for (const [id, data] of docs) {
    const doc = { ...data, updatedAt: FieldValue.serverTimestamp() };
    if (!existing.has(id)) doc.createdAt = FieldValue.serverTimestamp();
    batch.set(db.collection(collection).doc(id), doc, { merge: true });
    if (++n % 400 === 0) {
      await batch.commit();
      batch = db.batch();
    }
  }
  await batch.commit();
  console.log(`${collection}: ${docs.size} written (${docs.size - existing.size} new, ${existing.size} updated)`);
}

async function main() {
  const args = process.argv.slice(2);
  const dryRun = args.includes('--dry-run');
  const projectIdx = args.indexOf('--project');
  const projectId = projectIdx >= 0 ? args[projectIdx + 1] : undefined;

  const file = path.join(__dirname, 'catalogue.json');
  const { cities, areas } = buildDocuments(JSON.parse(fs.readFileSync(file, 'utf8')));
  const perCity = {};
  for (const a of areas.values()) perCity[a.cityId] = (perCity[a.cityId] || 0) + 1;
  console.log(`Validated: ${cities.size} cities, ${areas.size} areas.`);
  console.log('Areas per city:', perCity);

  if (dryRun) {
    console.log('\nDry run: nothing uploaded. Sample documents:');
    console.log('cities/karachi =', JSON.stringify(cities.get('karachi')));
    console.log('areas/karachi-clifton =', JSON.stringify(areas.get('karachi-clifton')));
    return;
  }
  if (!process.env.GOOGLE_APPLICATION_CREDENTIALS) {
    fail('set GOOGLE_APPLICATION_CREDENTIALS to the path of your service-account key (see GUIDE.md)');
  }
  if (!projectId) fail('pass --project YOUR_FIREBASE_PROJECT_ID');

  const { initializeApp, applicationDefault } = require('firebase-admin/app');
  const { getFirestore, FieldValue } = require('firebase-admin/firestore');
  initializeApp({ credential: applicationDefault(), projectId });
  const db = getFirestore();

  await upload(db, FieldValue, 'cities', cities);
  await upload(db, FieldValue, 'areas', areas);
  console.log('Done.');
}

if (require.main === module) {
  main().catch((e) => {
    console.error(e);
    process.exit(1);
  });
}

module.exports = { normalize, buildDocuments };