package com.example.backend.geo.application;

import com.example.backend.geo.domain.Place;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Offline India gazetteer (GeoNames, CC BY 4.0) plus hand-maintained alias, state and country files.
 * Loaded once at startup and immutable afterwards, so lookups are safe from any thread.
 */
@Component
public class Gazetteer {
    private static final Logger log = LoggerFactory.getLogger(Gazetteer.class);
    static final String PLACES = "geo/in-places.tsv";
    static final String ADMIN1 = "geo/in-admin1.tsv";
    static final String PLACE_ALIASES = "geo/place-aliases.tsv";
    static final String STATE_ALIASES = "geo/state-aliases.tsv";
    static final String COUNTRIES = "geo/countries.tsv";
    private static final List<String> DATA_FILES = List.of(PLACES, ADMIN1, PLACE_ALIASES, STATE_ALIASES, COUNTRIES);
    private static final Pattern DROPPED = Pattern.compile("['.’]");
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    private final Map<String, Place> byId;
    private final Map<String, List<Place>> primary;
    private final Map<String, List<Place>> alternate;
    private final Map<String, List<Place>> aliases;
    private final Map<String, String> admin1Names;
    private final Map<String, String> states;
    private final Set<String> foreign;
    private final Set<String> india;
    private final String dataHash;

    public Gazetteer() {
        Map<String, byte[]> raw = new LinkedHashMap<>();
        for (String file : DATA_FILES) raw.put(file, read(file));
        Map<String, Place> places = new LinkedHashMap<>();
        Map<String, List<Place>> primaryIndex = new HashMap<>();
        Map<String, List<Place>> alternateIndex = new HashMap<>();
        for (String[] row : rows(raw.get(PLACES), true)) {
            Place place = new Place(row[0], row[1], row[3], Double.parseDouble(row[4]), Double.parseDouble(row[5]),
                    Long.parseLong(row[6]), row[7]);
            places.put(place.id(), place);
            Set<String> primaryKeys = new HashSet<>(List.of(key(row[1]), key(row[2])));
            primaryKeys.forEach(name -> add(primaryIndex, name, place));
            if (row.length > 8 && !row[8].isEmpty()) {
                for (String alternateName : row[8].split("\\|")) {
                    String alternateKey = key(alternateName);
                    if (!primaryKeys.contains(alternateKey)) add(alternateIndex, alternateKey, place);
                }
            }
        }
        Map<String, String> admin1 = new HashMap<>();
        for (String[] row : rows(raw.get(ADMIN1), true)) admin1.put(row[0], row[1]);
        Map<String, String> stateIndex = new HashMap<>();
        for (String[] row : rows(raw.get(STATE_ALIASES), false)) stateIndex.put(key(row[0]), row[1]);
        Map<String, List<Place>> aliasIndex = new HashMap<>();
        for (String[] row : rows(raw.get(PLACE_ALIASES), false)) {
            String admin1Filter = row.length > 2 && !row[2].isBlank() ? row[2].trim() : null;
            for (Place place : primaryIndex.getOrDefault(key(row[1]), List.of())) {
                if (admin1Filter == null || admin1Filter.equals(place.admin1())) add(aliasIndex, key(row[0]), place);
            }
        }
        Set<String> foreignKeys = new HashSet<>();
        Set<String> indiaKeys = new HashSet<>();
        for (String[] row : rows(raw.get(COUNTRIES), false)) {
            ("india".equals(row[1].trim()) ? indiaKeys : foreignKeys).add(key(row[0]));
        }
        this.byId = Map.copyOf(places);
        this.primary = freeze(primaryIndex);
        this.alternate = freeze(alternateIndex);
        this.aliases = freeze(aliasIndex);
        this.admin1Names = Map.copyOf(admin1);
        this.states = Map.copyOf(stateIndex);
        this.foreign = Set.copyOf(foreignKeys);
        this.india = Set.copyOf(indiaKeys);
        this.dataHash = hash(raw);
        log.info("event=geo_gazetteer_loaded places={} aliases={} states={} data_hash={}",
                byId.size(), aliases.size(), states.size(), dataHash);
    }

    /** Lowercase, strip diacritics, {@code &} to "and", punctuation to spaces, collapsed whitespace. */
    public static String key(String value) {
        if (value == null) return "";
        String folded = MARKS.matcher(Normalizer.normalize(value, Normalizer.Form.NFKD)).replaceAll("");
        folded = DROPPED.matcher(folded.toLowerCase(Locale.ROOT).replace("&", " and ")).replaceAll("");
        return SEPARATORS.matcher(folded).replaceAll(" ").trim();
    }

    public Optional<Place> byId(String id) { return Optional.ofNullable(id == null ? null : byId.get(id)); }
    public int placeCount() { return byId.size(); }
    /** Every place, for in-memory scans such as the nearest city to an office pin. */
    public java.util.Collection<Place> places() { return java.util.Collections.unmodifiableCollection(byId.values()); }
    /** First 12 hex characters of SHA-256 over every data file's raw bytes. */
    public String dataHash() { return dataHash; }
    public List<Place> aliasMatches(String key) { return aliases.getOrDefault(key, List.of()); }
    public List<Place> primaryMatches(String key) { return primary.getOrDefault(key, List.of()); }
    public List<Place> alternateMatches(String key) { return alternate.getOrDefault(key, List.of()); }
    public Optional<String> stateCode(String key) { return Optional.ofNullable(states.get(key)); }
    public boolean admin1Exists(String code) { return admin1Names.containsKey(code); }
    public Optional<String> admin1Name(String code) { return Optional.ofNullable(code == null ? null : admin1Names.get(code)); }
    public boolean isForeign(String key) { return foreign.contains(key); }
    public boolean isIndia(String key) { return india.contains(key); }

    private static byte[] read(String file) {
        try (InputStream input = Gazetteer.class.getClassLoader().getResourceAsStream(file)) {
            if (input == null) throw new IllegalStateException("Geo data file missing from classpath: " + file);
            return input.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException("Geo data file could not be read: " + file, failure);
        }
    }

    private static List<String[]> rows(byte[] content, boolean hasHeader) {
        List<String[]> rows = new ArrayList<>();
        boolean first = true;
        for (String line : new String(content, StandardCharsets.UTF_8).split("\n")) {
            if (first && hasHeader) { first = false; continue; }
            first = false;
            if (line.isBlank() || line.startsWith("#")) continue;
            rows.add(line.split("\t", -1));
        }
        return rows;
    }

    private static void add(Map<String, List<Place>> index, String key, Place place) {
        if (key.isEmpty()) return;
        List<Place> values = index.computeIfAbsent(key, ignored -> new ArrayList<>());
        if (!values.contains(place)) values.add(place);
    }

    private static Map<String, List<Place>> freeze(Map<String, List<Place>> index) {
        Map<String, List<Place>> frozen = new HashMap<>();
        index.forEach((name, values) -> frozen.put(name, List.copyOf(values)));
        return Map.copyOf(frozen);
    }

    private static String hash(Map<String, byte[]> raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            raw.values().forEach(digest::update);
            return HexFormat.of().formatHex(digest.digest()).substring(0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
