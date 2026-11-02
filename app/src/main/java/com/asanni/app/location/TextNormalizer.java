package com.asanni.app.location;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Turns place names into a comparable form: "Gulistan-e-Johar" -> "gulistanejohar".
 *
 * Location: app/src/main/java/com/asanni/app/location/TextNormalizer.java  (NEW)
 *
 * MUST stay identical to normalize() in seed/seed-catalogue.js, which writes normalizedName
 * and normalizedAliases into Firestore.
 */
public final class TextNormalizer {

    private TextNormalizer() {}

    public static String normalize(String text) {
        if (text == null) return "";
        String s = Normalizer.normalize(text, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")          // drop accents
                .toLowerCase(Locale.ROOT);
        return s.replaceAll("[^a-z0-9]", "");      // keep letters and digits only
    }
}