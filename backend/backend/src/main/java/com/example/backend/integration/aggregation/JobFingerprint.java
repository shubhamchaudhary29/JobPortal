package com.example.backend.integration.aggregation;

import com.example.backend.shared.text.CompanyNames;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Provider-independent imported-job fingerprint, {@value #VERSION}: SHA-256 of normalized
 * {@code company|title|location}. Normalization is deterministic and used only for matching; it never rewrites
 * displayed values. Changing any rule or alias table requires a new version and the fingerprint migration.
 */
public final class JobFingerprint {
    public static final int VERSION = 2;

    private static final Map<String, String> CITY_ALIASES = new HashMap<>();
    private static final Set<String> CITIES = new HashSet<>();
    private static final Set<String> STATES = new HashSet<>();
    private static final Set<String> STATE_CODES = new HashSet<>();
    private static final Set<String> COUNTRIES = new HashSet<>();
    private static final List<String> REMOTE_WORDS = new ArrayList<>();
    private static final List<String> WORK_MODE_WORDS = new ArrayList<>();

    static {
        JsonNode cities = read("aggregation/city-aliases.json");
        cities.get("aliases").fields().forEachRemaining(entry -> CITY_ALIASES.put(entry.getKey(), entry.getValue().asText()));
        cities.get("cities").forEach(node -> CITIES.add(node.asText()));
        CITIES.addAll(CITY_ALIASES.values());
        cities.get("states").forEach(node -> STATES.add(node.asText()));
        cities.get("stateCodes").forEach(node -> STATE_CODES.add(node.asText()));
        cities.get("countries").forEach(node -> COUNTRIES.add(node.asText()));
        cities.get("remoteWords").forEach(node -> REMOTE_WORDS.add(node.asText()));
        REMOTE_WORDS.sort((left, right) -> right.length() - left.length());
        cities.get("workModeWords").forEach(node -> WORK_MODE_WORDS.add(node.asText()));
        WORK_MODE_WORDS.sort((left, right) -> right.length() - left.length());
    }

    private JobFingerprint() { }

    /** Returns {@code null} when company or title is missing, like the previous version. */
    public static String of(String company, String title, String location) {
        if (company == null || title == null) return null;
        String key = company(company) + "|" + title(title) + "|" + location(location);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String title(String value) { return basic(value); }

    /** Company part of the key; the rules live in {@link CompanyNames} so office pins match the same way. */
    static String company(String value) { return CompanyNames.key(value); }

    static String location(String value) {
        if (value == null || value.isBlank()) return "";
        String lower = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        Set<String> alternatives = new TreeSet<>();
        for (String alternative : lower.split("[/;|]| & ")) {
            String normalized = alternative(alternative);
            if (!normalized.isEmpty()) alternatives.add(normalized);
        }
        if (alternatives.isEmpty()) return basic(value);
        return String.join(" / ", alternatives);
    }

    private static String alternative(String raw) {
        String text = raw;
        boolean remote = false;
        for (String word : REMOTE_WORDS) {
            String pattern = wordPattern(word);
            if (java.util.regex.Pattern.compile(pattern).matcher(text).find()) {
                remote = true;
                text = text.replaceAll(pattern, " ");
            }
        }
        for (String word : WORK_MODE_WORDS) text = text.replaceAll(wordPattern(word), " ");
        List<String> segments = new ArrayList<>();
        for (String segment : text.split("[,()\\-–]")) {
            String normalized = basic(segment);
            if (!normalized.isEmpty()) segments.add(normalized);
        }
        while (!segments.isEmpty() && strippable(segments.get(segments.size() - 1), segments)) {
            segments.remove(segments.size() - 1);
        }
        List<String> mapped = new ArrayList<>();
        for (String segment : segments) {
            String city = city(segment);
            if (!mapped.contains(city)) mapped.add(city);
        }
        String place = String.join(" ", mapped);
        if (remote) return place.isEmpty() ? "remote" : "remote " + place;
        return place;
    }

    private static String wordPattern(String word) {
        return "(?<![\\p{L}\\p{N}])" + java.util.regex.Pattern.quote(word) + "(?![\\p{L}\\p{N}])";
    }

    private static boolean strippable(String segment, List<String> segments) {
        if (COUNTRIES.contains(segment) || STATES.contains(segment)) return true;
        return STATE_CODES.contains(segment) && segments.size() > 1 && CITIES.contains(city(segments.get(0)));
    }

    private static String city(String segment) {
        String alias = CITY_ALIASES.get(segment);
        if (alias != null) return alias;
        for (String state : STATES) {
            if (segment.endsWith(" " + state)) {
                String head = segment.substring(0, segment.length() - state.length() - 1).trim();
                String headAlias = CITY_ALIASES.getOrDefault(head, head);
                if (CITIES.contains(headAlias)) return headAlias;
            }
        }
        return segment;
    }

    private static String basic(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replace("&", " and ");
        return normalized.replaceAll("[^\\p{L}\\p{N}+#]+", " ").trim();
    }

    private static JsonNode read(String resource) {
        try (InputStream input = JobFingerprint.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing fingerprint table " + resource);
            return new ObjectMapper().readTree(input);
        } catch (IOException failure) {
            throw new IllegalStateException("Unreadable fingerprint table " + resource, failure);
        }
    }
}
