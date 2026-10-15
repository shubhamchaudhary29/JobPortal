package com.example.backend.shared.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Company-name matching key shared by job fingerprints and office pins: NFKC, lower case, {@code &} → "and",
 * punctuation to spaces, legal/generic suffixes stripped from the end, then former or legal names mapped by
 * {@code aggregation/company-aliases.json}. Used only for matching; it never rewrites displayed names.
 * Fingerprints depend on it, so changing any rule or alias needs a new {@code JobFingerprint.VERSION}.
 */
public final class CompanyNames {
    static final String ALIASES = "aggregation/company-aliases.json";
    private static final List<String> SUFFIXES = new ArrayList<>();
    private static final Map<String, String> ALIAS_TARGETS = new HashMap<>();

    static {
        JsonNode companies = read();
        companies.get("suffixes").forEach(node -> SUFFIXES.add(node.asText()));
        SUFFIXES.sort((left, right) -> right.length() - left.length());
        companies.get("aliases").fields().forEachRemaining(entry -> ALIAS_TARGETS.put(entry.getKey(), entry.getValue().asText()));
    }

    private CompanyNames() { }

    /** Matching key; {@code ""} for null or blank names. */
    public static String key(String company) {
        String normalized = basic(company);
        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String suffix : SUFFIXES) {
                if (normalized.endsWith(" " + suffix)) {
                    normalized = normalized.substring(0, normalized.length() - suffix.length() - 1).trim();
                    stripped = true;
                    break;
                }
            }
        }
        return ALIAS_TARGETS.getOrDefault(normalized, normalized);
    }

    private static String basic(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replace("&", " and ");
        return normalized.replaceAll("[^\\p{L}\\p{N}+#]+", " ").trim();
    }

    private static JsonNode read() {
        try (InputStream input = CompanyNames.class.getClassLoader().getResourceAsStream(ALIASES)) {
            if (input == null) throw new IllegalStateException("Missing company alias table " + ALIASES);
            return new ObjectMapper().readTree(input);
        } catch (IOException failure) {
            throw new IllegalStateException("Unreadable company alias table " + ALIASES, failure);
        }
    }
}
