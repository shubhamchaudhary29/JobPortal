package com.example.backend.geo.application;

import com.example.backend.geo.domain.Place;
import com.example.backend.shared.text.CompanyNames;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Named company offices and office buildings from OpenStreetMap (ODbL), from {@code in-osm-offices.tsv} built by
 * {@code scripts/build-osm-geo.py}. Each row already carries its gazetteer parent city. Immutable after startup.
 * Offices are applied at read time, never written into jobs, so this file is not part of the resolver version.
 *
 * <p>Matching (GEO-8D) is exact equality of {@link CompanyNames#key} only, never fuzzy, edit-distance or prefix: a
 * row matches company key {@code k} when the key of its name, English name, brand or operator equals {@code k}, either
 * as is or with trailing generic words removed ("Infosys Campus", "Google Office Bengaluru", "Amazon Development
 * Centre"). Keys that are empty, shorter than {@value #MIN_KEY_LENGTH} characters, generic ("it park") or the city's own
 * name never match.
 * Rows of one company and city within {@value #SAME_OFFICE_METRES} m of each other count as one (the lower id).
 */
@Component
public class OsmOfficeDirectory {
    private static final Logger log = LoggerFactory.getLogger(OsmOfficeDirectory.class);
    static final String OFFICES = "geo/in-osm-offices.tsv";
    static final int MIN_KEY_LENGTH = 3;
    static final double SAME_OFFICE_METRES = 150;
    /** Trailing words removed (repeatedly) before comparing; longest first so "development centre" beats "centre". */
    static final List<String> GENERIC_SUFFIXES = List.of("development centre", "development center", "technology centre",
            "technology center", "innovation centre", "innovation center", "delivery centre", "delivery center",
            "headquarters", "offices", "office", "campus", "india", "hq", "idc", "gdc", "r and d", "r d", "sez");
    private static final Pattern NUMBERED_PART = Pattern.compile("\\s+(?:(?:tower|block|building)\\s+\\S+|phase\\s+\\d+)$");
    static final Set<String> DENIED = Set.of("it park", "tech park", "technology park", "software park", "business park",
            "business centre", "business center", "business hub", "it hub", "tech hub", "it sez", "sez", "office", "offices",
            "head office", "corporate office", "regional office", "branch office", "registered office", "tower", "building",
            "commercial complex", "shopping complex", "complex", "plaza", "mall", "cyber city", "info park", "infopark",
            "technopark", "it city", "knowledge park", "special economic zone");

    private final List<OsmOffice> offices;
    private final Map<String, OsmOffice> byRef;
    /** company key → place → de-duplicated candidates, ordered by id. */
    private final Map<String, Map<String, List<OsmOffice>>> index;

    @Autowired
    public OsmOfficeDirectory(Gazetteer gazetteer, @Value("${geo.osm-offices.file:" + OFFICES + "}") String file) {
        long before = Gazetteer.usedHeapAfterGc();
        this.offices = load(Gazetteer.readOsm(file).rows(), gazetteer);
        this.byRef = byRef(offices);
        this.index = buildIndex(offices, gazetteer::cityKeys);
        long officeBytes = Math.max(0, Gazetteer.usedHeapAfterGc() - before);
        log.info("event=geo_osm_loaded localities={} offices={} office_keys={} heap_delta_mb={}", gazetteer.localities().size(),
                offices.size(), index.size(), Math.round((gazetteer.localityHeapBytes() + officeBytes) / 1048576.0 * 10) / 10.0);
    }

    OsmOfficeDirectory(List<OsmOffice> offices, Function<String, Set<String>> cityKeys) {
        this.offices = List.copyOf(offices);
        this.byRef = byRef(this.offices);
        this.index = buildIndex(this.offices, cityKeys);
    }

    public List<OsmOffice> offices() { return offices; }

    public int size() { return offices.size(); }

    public Optional<OsmOffice> byRef(String osmRef) { return Optional.ofNullable(osmRef == null ? null : byRef.get(osmRef)); }

    /** De-duplicated rows of {@code placeId} whose names match {@code companyKey} exactly, ordered by id. */
    public List<OsmOffice> candidates(String companyKey, String placeId) {
        if (companyKey == null || placeId == null) return List.of();
        return index.getOrDefault(companyKey, Map.of()).getOrDefault(placeId, List.of());
    }

    /** Every (company key, place) with at least one candidate, in key then place order (for auto-apply and reports). */
    public void forEachPair(PairConsumer consumer) {
        index.forEach((key, places) -> places.forEach((placeId, rows) -> consumer.accept(key, placeId, rows)));
    }

    @FunctionalInterface
    public interface PairConsumer { void accept(String companyKey, String placeId, List<OsmOffice> candidates); }

    /** Every distinct matching key of a row: name, English name, brand and operator, each as is and stripped. */
    static Set<String> keys(OsmOffice office, Set<String> cityKeys) {
        Set<String> keys = new LinkedHashSet<>();
        for (String name : List.of(office.name(), office.nameEn(), office.brand(), office.operator())) {
            String full = CompanyNames.key(name);
            for (String key : List.of(full, strip(full, cityKeys))) {
                if (key.length() >= MIN_KEY_LENGTH && !DENIED.contains(key) && !cityKeys.contains(key)) keys.add(key);
            }
        }
        return keys;
    }

    /** Removes trailing generic words, numbered parts ("tower b", "phase 2") and the city's own names, repeatedly. */
    static String strip(String key, Set<String> cityKeys) {
        String current = key;
        boolean changed = true;
        while (changed && !current.isEmpty()) {
            changed = false;
            for (String suffix : cityKeys.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
                if (!suffix.isEmpty() && current.endsWith(" " + suffix)) {
                    current = current.substring(0, current.length() - suffix.length() - 1).trim();
                    changed = true;
                }
            }
            for (String suffix : GENERIC_SUFFIXES) {
                if (current.endsWith(" " + suffix)) {
                    current = current.substring(0, current.length() - suffix.length() - 1).trim();
                    changed = true;
                    break;
                }
            }
            String numbered = NUMBERED_PART.matcher(current).replaceFirst("");
            if (!numbered.equals(current)) {
                current = numbered.trim();
                changed = true;
            }
            // A legal suffix may surface after stripping ("Infosys Limited Campus").
            String again = CompanyNames.key(current);
            if (!again.equals(current)) {
                current = again;
                changed = true;
            }
        }
        return current;
    }

    private static Map<String, Map<String, List<OsmOffice>>> buildIndex(List<OsmOffice> offices,
                                                                       Function<String, Set<String>> cityKeys) {
        Map<String, Map<String, List<OsmOffice>>> raw = new TreeMap<>();
        for (OsmOffice office : offices) {
            for (String key : keys(office, cityKeys.apply(office.placeId()))) {
                raw.computeIfAbsent(key, ignored -> new TreeMap<>()).computeIfAbsent(office.placeId(), ignored -> new ArrayList<>())
                        .add(office);
            }
        }
        Map<String, Map<String, List<OsmOffice>>> frozen = new HashMap<>();
        raw.forEach((key, places) -> {
            Map<String, List<OsmOffice>> byPlace = new TreeMap<>();
            places.forEach((placeId, rows) -> byPlace.put(placeId, deduplicate(rows)));
            frozen.put(key, java.util.Collections.unmodifiableMap(byPlace));
        });
        return java.util.Collections.unmodifiableMap(new TreeMap<>(frozen));
    }

    /** Rows within {@value #SAME_OFFICE_METRES} m of a kept row are the same office; the lower id is kept. */
    private static List<OsmOffice> deduplicate(List<OsmOffice> rows) {
        List<OsmOffice> kept = new ArrayList<>();
        for (OsmOffice row : rows.stream().sorted(OsmOffice.BY_ID).toList()) {
            boolean near = kept.stream().anyMatch(other ->
                    MapJobService.haversineKm(row.lat(), row.lng(), other.lat(), other.lng()) * 1000 <= SAME_OFFICE_METRES);
            if (!near) kept.add(row);
        }
        return List.copyOf(kept);
    }

    private static Map<String, OsmOffice> byRef(List<OsmOffice> offices) {
        Map<String, OsmOffice> refs = new HashMap<>();
        offices.forEach(office -> refs.putIfAbsent(office.osmRef(), office));
        return Map.copyOf(refs);
    }

    private static List<OsmOffice> load(List<String[]> rows, Gazetteer gazetteer) {
        List<OsmOffice> loaded = new ArrayList<>(rows.size());
        for (String[] row : rows) {
            if (row.length < 9 || gazetteer.byId(row[6]).isEmpty()) continue;
            loaded.add(new OsmOffice(row[0], row[1], row[2], row[3], row[4], row[5], row[6], Double.parseDouble(row[7]),
                    Double.parseDouble(row[8])));
        }
        return List.copyOf(loaded);
    }

    /** {@code osmRef} is {@code osm-} + n/w/r + the OSM id; {@code kind} is the matching tag, e.g. {@code office=it}. */
    public record OsmOffice(String osmRef, String name, String nameEn, String brand, String operator, String kind,
                            String placeId, double lat, double lng) {
        /** By numeric OSM id, then node before way before relation. */
        static final Comparator<OsmOffice> BY_ID = Comparator.comparingLong((OsmOffice office) -> Long.parseLong(office.osmRef().substring(5)))
                .thenComparing(office -> office.osmRef().charAt(4));

        /** The object's page on openstreetmap.org, for checking a match by eye. */
        public String url() {
            String type = switch (osmRef.charAt(4)) {
                case 'n' -> "node";
                case 'w' -> "way";
                default -> "relation";
            };
            return "https://www.openstreetmap.org/" + type + "/" + osmRef.substring(5);
        }

        /** The name to show: OSM name, else English name, brand or operator. */
        public String displayName() {
            for (String value : List.of(name, nameEn, brand, operator)) {
                if (value != null && !value.isBlank()) return value.trim();
            }
            return osmRef;
        }

        public double distanceKmTo(Place place) {
            return MapJobService.haversineKm(lat, lng, place.lat(), place.lng());
        }
    }
}
