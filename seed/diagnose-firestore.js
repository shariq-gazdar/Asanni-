const { initializeApp, applicationDefault } =
  require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");

async function main() {
  const projectId = process.argv[2];

  if (!projectId) {
    throw new Error("Pass your Firebase project ID.");
  }

  const app = initializeApp({
    credential: applicationDefault(),
    projectId,
  });

  const db = getFirestore(app);

  console.log("Project ID:", app.options.projectId);
  console.log("Database ID:", db.databaseId);

  const ref = db.collection("_diagnostics").doc("seed-test");

  await ref.set({
    message: "Firestore diagnostic",
    checkedAt: new Date().toISOString(),
  });

  const snapshot = await ref.get();

  console.log("Document exists:", snapshot.exists);
  console.log("Document data:", snapshot.data());

  await app.delete();
}

main().catch((error) => {
  console.error("DIAGNOSTIC FAILED:", error);
  process.exitCode = 1;
});
