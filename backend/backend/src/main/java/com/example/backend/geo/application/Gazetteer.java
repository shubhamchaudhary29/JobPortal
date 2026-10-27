package com.example.backend.geo.application;

import com.example.backend.geo.domain.Place;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
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
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Offline India gazetteer (GeoNames, CC BY 4.0) plus hand-maintained alias, state and country files, and the
 * OpenStreetMap localities of its cities (ODbL, GEO-8). Loaded once at startup and immutable afterwards, so lookups
 * are safe from any thread. The locality file is part of {@link #dataHash()} because it changes resolutions.
 */
@Component
public class Gazetteer {
    private static final Logger log = LoggerFactory.getLogger(Gazetteer.class);
    static final String PLACES = "geo/in-places.tsv";
    static final String ADMIN1 = "geo/in-admin1.tsv";
    static final String PLACE_ALIASES = "geo/place-aliases.tsv";
    static final String STATE_ALIASES = "geo/state-aliases.tsv";
    static final String COUNTRIES = "geo/countries.tsv";
    /** Foreign regions and cities that are never an office site label after an Indian city (GEO-9A). */
    static final String FOREIGN_QUALIFIERS = "geo/foreign-qualifiers.tsv";
    /** Written as {@code .tsv.gz} instead when it exceeds 4 MB; either form is read. */
    static final String LOCALITIES = "geo/in-localities.tsv";
    static final String LOCALITY_ALIASES = "geo/locality-aliases.tsv";
    private static final List<String> DATA_FILES = List.of(PLACES, ADMIN1, PLACE_ALIASES, STATE_ALIASES, COUNTRIES,
            FOREIGN_QUALIFIERS);
    private static final Pattern DROPPED = Pattern.compile("['.’]");
    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    private final Map<String, Place> byId;
    private final Map<String, List<Place>> primary;
    private final Map<String, List<Place>> alternate;
    private final Map<String, List<Place>> aliases;
    private final Map<String, Set<String>> cityKeys;
    private final Map<String, String> admin1Names;
    private final Map<String, String> states;
    private final Set<String> foreign;
    private final Set<String> india;
    private final Set<String> foreignQualifiers;
    private final String dataHash;
    private final LocalityIndex localities;
    private final Map<String, LocalityIndex.Locality> localityAliases;
    private final List<String> danglingLocalityAliases;
    private final long localityHeapBytes;

    public Gazetteer() {
        Map<String, byte[]> raw = new LinkedHashMap<>();
        for (String file : DATA_FILES) raw.put(file, read(file));
        long heapBefore = usedHeapAfterGc();
        OsmFile localityFile = readOsm(LOCALITIES);
        raw.put(localityFile.name(), localityFile.raw());
        this.localities = LocalityIndex.fromRows(localityFile.rows());
        this.localityHeapBytes = Math.max(0, usedHeapAfterGc() - heapBefore);
        raw.put(LOCALITY_ALIASES, read(LOCALITY_ALIASES));
        Map<String, LocalityIndex.Locality> localityAliasIndex = new HashMap<>();
        List<String> dangling = new ArrayList<>();
        for (String[] row : rows(raw.get(LOCALITY_ALIASES), false)) {
            LocalityIndex.Locality target = row.length < 2 ? null : localityTarget(row[1].trim());
            if (target == null) { dangling.add(String.join(" -> ", row)); continue; }
            localityAliasIndex.put(LocalityIndex.localityKey(key(row[0])), target);
        }
        if (!dangling.isEmpty()) log.warn("event=geo_locality_alias_dangling aliases={}", dangling);
        this.localityAliases = Map.copyOf(localityAliasIndex);
        this.danglingLocalityAliases = List.copyOf(dangling);
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
        Map<String, Set<String>> namesByPlace = new HashMap<>();
        places.values().forEach(place -> namesByPlace.computeIfAbsent(place.id(), id -> new HashSet<>()).add(key(place.name())));
        aliasIndex.forEach((alias, targets) -> targets.forEach(place ->
                namesByPlace.computeIfAbsent(place.id(), id -> new HashSet<>()).add(alias)));
        Map<String, Set<String>> frozenNames = new HashMap<>();
        namesByPlace.forEach((id, names) -> frozenNames.put(id, Set.copyOf(names)));
        this.cityKeys = Map.copyOf(frozenNames);
        this.admin1Names = Map.copyOf(admin1);
        this.states = Map.copyOf(stateIndex);
        this.foreign = Set.copyOf(foreignKeys);
        this.india = Set.copyOf(indiaKeys);
        Set<String> qualifierKeys = new HashSet<>();
        for (String[] row : rows(raw.get(FOREIGN_QUALIFIERS), false)) qualifierKeys.add(key(row[0]));
        this.foreignQualifiers = Set.copyOf(qualifierKeys);
        this.dataHash = hash(raw);
        log.info("event=geo_gazetteer_loaded places={} aliases={} states={} localities={} data_hash={}",
                byId.size(), aliases.size(), states.size(), localities.size(), dataHash);
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
    /**
     * A foreign region or city from {@code foreign-qualifiers.tsv} ("Sindh", "Ontario"): not a country, so it never
     * makes a job FOREIGN, but "Hyderabad, Sindh" must not read "Sindh" as an office site label.
     */
    public boolean isForeignQualifier(String key) { return foreignQualifiers.contains(key); }
    /** {@link #key}s a place is known by: its name and every alias that points to it ("bengaluru", "bangalore"). */
    public Set<String> cityKeys(String placeId) { return placeId == null ? Set.of() : cityKeys.getOrDefault(placeId, Set.of()); }

    public LocalityIndex localities() { return localities; }
    public Optional<LocalityIndex.Locality> localityById(String id) { return localities.byId(id); }
    public List<LocalityIndex.Locality> localitiesInPlace(String placeId) { return localities.inPlace(placeId); }
    /** Localities of {@code placeId} with this {@link #key} as name, English name or alternate name, best first. */
    public List<LocalityIndex.Locality> localityMatches(String key, String placeId) { return localities.matches(key, placeId); }
    public Optional<LocalityIndex.Locality> nearestLocality(String placeId, double lat, double lng, double maxKm) {
        return localities.nearest(placeId, lat, lng, maxKm);
    }
    /**
     * The curated locality for an alias key ({@code locality-aliases.tsv}), compared after
     * {@link LocalityIndex#localityKey}. The only way a locality is found without its city.
     */
    public Optional<LocalityIndex.Locality> localityAlias(String key) {
        return Optional.ofNullable(key == null ? null : localityAliases.get(LocalityIndex.localityKey(key)));
    }
    /** Alias rows whose target is not in the locality file (must be empty; checked by a test). */
    List<String> danglingLocalityAliases() { return danglingLocalityAliases; }

    /** Heap retained by the locality index at startup (after GC), for the GEO-8 memory budget log. */
    long localityHeapBytes() { return localityHeapBytes; }

    /**
     * An OpenStreetMap-derived TSV: {@code <base>.gz} when present, else {@code <base>}. {@code #} comment lines
     * and the column header are dropped from {@code rows}; {@code raw} is the file as stored (for hashing).
     */
    /** {@code osm-…} id, or {@code name|placeId} naming exactly one locality of that place. */
    private LocalityIndex.Locality localityTarget(String target) {
        if (target.startsWith("osm-")) return localities.byId(target).orElse(null);
        int bar = target.lastIndexOf('|');
        if (bar <= 0) return null;
        String name = target.substring(0, bar).trim();
        List<LocalityIndex.Locality> named = localities.inPlace(target.substring(bar + 1).trim()).stream()
                .filter(locality -> locality.name().equals(name)).toList();
        return named.size() == 1 ? named.get(0) : null;
    }

    static OsmFile readOsm(String base) {
        String zipped = base + ".gz";
        boolean gz = Gazetteer.class.getClassLoader().getResource(zipped) != null;
        String name = gz ? zipped : base;
        byte[] raw = read(name);
        byte[] text = raw;
        if (gz) {
            try (InputStream input = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                text = input.readAllBytes();
            } catch (IOException failure) {
                throw new IllegalStateException("Geo data file could not be read: " + name, failure);
            }
        }
        List<String[]> rows = new ArrayList<>();
        boolean header = true;
        for (String line : new String(text, StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            if (header) { header = false; continue; }
            rows.add(line.split("\t", -1));
        }
        return new OsmFile(name, raw, rows);
    }

    record OsmFile(String name, byte[] raw, List<String[]> rows) { }

    static long usedHeapAfterGc() {
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

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
