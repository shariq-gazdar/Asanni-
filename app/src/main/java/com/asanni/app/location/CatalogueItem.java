package com.asanni.app.location;

import com.google.firebase.firestore.DocumentSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One city or area record from the Firestore catalogue.
 *
 * Location: app/src/main/java/com/asanni/app/location/CatalogueItem.java  (NEW)
 */
public final class CatalogueItem {

    public final String id;
    public final String name;
    public final String normalizedName;
    /** Other spellings, already normalized (e.g. "defence" for DHA). */
    public final List<String> normalizedAliases;
    /** For areas: parent city ID. For cities: null. */
    public final String cityId;
    /** For areas: true for the "All of <city>" record, sorted last and never auto-detected. */
    public final boolean isCityWide;

    public CatalogueItem(String id, String name, String normalizedName,
                         List<String> normalizedAliases, String cityId, boolean isCityWide) {
        this.id = id;
        this.name = name;
        this.normalizedName = normalizedName != null && !normalizedName.isEmpty()
                ? normalizedName : TextNormalizer.normalize(name);
        this.normalizedAliases = normalizedAliases != null
                ? Collections.unmodifiableList(normalizedAliases) : Collections.<String>emptyList();
        this.cityId = cityId;
        this.isCityWide = isCityWide;
    }

    /** Returns null if the document lacks a name (skipped instead of crashing). */
    static CatalogueItem fromSnapshot(DocumentSnapshot doc) {
        String name = doc.getString("name");
        if (name == null || name.trim().isEmpty()) return null;
        List<String> aliases = new ArrayList<>();
        Object raw = doc.get("normalizedAliases");
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (o instanceof String) aliases.add((String) o);
            }
        }
        return new CatalogueItem(doc.getId(), name, doc.getString("normalizedName"), aliases,
                doc.getString("cityId"), Boolean.TRUE.equals(doc.getBoolean("isCityWide")));
    }

    /** Search: does the (normalized) query appear in the name or any alias? */
    public boolean matchesQuery(String normalizedQuery) {
        if (normalizedQuery.isEmpty()) return true;
        if (normalizedName.contains(normalizedQuery)) return true;
        for (String a : normalizedAliases) {
            if (a.contains(normalizedQuery)) return true;
        }
        return false;
    }

    /** Exact name or alias match, used for reverse-geocoding suggestions. */
    public boolean matchesExactly(String normalizedText) {
        return normalizedName.equals(normalizedText) || normalizedAliases.contains(normalizedText);
    }

    @Override
    public String toString() {
        return name;
    }
}