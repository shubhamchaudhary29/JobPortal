package com.example.backend.geo.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OpenStreetMap localities (suburbs, neighbourhoods, quarters and named tech hubs, ODbL) per gazetteer city, from
 * {@code in-localities.tsv} built by {@code scripts/build-osm-geo.py}. Every locality belongs to exactly one city.
 * Immutable after construction, so lookups are safe from any thread.
 */
public final class LocalityIndex {
    public static final LocalityIndex EMPTY = new LocalityIndex(List.of());
    /** Name matches first, then English name, then alternate names; suburbs before smaller kinds; then id. */
    private static final Comparator<Match> ORDER = Comparator.comparingInt(Match::tier)
            .thenComparingInt(match -> match.locality().kind().equals("suburb") ? 0 : 1)
            .thenComparing(match -> match.locality().id());

    private final Map<String, Locality> byId;
    private final Map<String, List<Locality>> byPlace;
    private final Map<String, List<Locality>> byPlaceKey;

    LocalityIndex(List<Locality> localities) {
        Map<String, Locality> ids = new LinkedHashMap<>();
        Map<String, List<Locality>> places = new HashMap<>();
        Map<String, List<Match>> keys = new HashMap<>();
        for (Locality locality : localities) {
            if (ids.putIfAbsent(locality.id(), locality) != null) continue;
            places.computeIfAbsent(locality.placeId(), ignored -> new ArrayList<>()).add(locality);
            index(keys, locality, locality.name(), 0);
            index(keys, locality, locality.nameEn(), 1);
            locality.altNames().forEach(alternate -> index(keys, locality, alternate, 2));
        }
        Map<String, List<Locality>> frozenPlaces = new HashMap<>();
        places.forEach((place, values) -> frozenPlaces.put(place, List.copyOf(values)));
        Map<String, List<Locality>> frozenKeys = new HashMap<>();
        keys.forEach((key, matches) -> frozenKeys.put(key, matches.stream().sorted(ORDER).map(Match::locality)
                .distinct().toList()));
        this.byId = Map.copyOf(ids);
        this.byPlace = Map.copyOf(frozenPlaces);
        this.byPlaceKey = Map.copyOf(frozenKeys);
    }

    /** Rows of {@code in-localities.tsv} (header and {@code #} comments already removed). */
    static LocalityIndex fromRows(List<String[]> rows) {
        List<Locality> localities = new ArrayList<>(rows.size());
        for (String[] row : rows) {
            if (row.length < 8) continue;
            List<String> alternates = row[3].isEmpty() ? List.of() : List.of(row[3].split("\\|"));
            localities.add(new Locality(row[0], row[1], row[2], alternates, row[4], Double.parseDouble(row[5]),
                    Double.parseDouble(row[6]), row[7]));
        }
        return new LocalityIndex(localities);
    }

    public Optional<Locality> byId(String id) { return Optional.ofNullable(id == null ? null : byId.get(id)); }

    public List<Locality> inPlace(String placeId) { return placeId == null ? List.of() : byPlace.getOrDefault(placeId, List.of()); }

    /** Localities of {@code placeId} whose name, English name or alternate name has this {@link Gazetteer#key}. */
    public List<Locality> matches(String key, String placeId) {
        if (key == null || key.isEmpty() || placeId == null) return List.of();
        return byPlaceKey.getOrDefault(placeId + '\u0001' + key, List.of());
    }

    /** The locality of {@code placeId} nearest to the point, if one is within {@code maxKm}; ties go to the lower id. */
    public Optional<Locality> nearest(String placeId, double lat, double lng, double maxKm) {
        Locality best = null;
        double bestKm = maxKm;
        for (Locality locality : inPlace(placeId)) {
            double km = MapJobService.haversineKm(lat, lng, locality.lat(), locality.lng());
            if (km < bestKm || (km == bestKm && best != null && locality.id().compareTo(best.id()) < 0)) {
                best = locality;
                bestKm = km;
            }
        }
        return Optional.ofNullable(best);
    }

    public int size() { return byId.size(); }

    private static void index(Map<String, List<Match>> keys, Locality locality, String name, int tier) {
        String key = Gazetteer.key(name);
        if (key.isEmpty()) return;
        keys.computeIfAbsent(locality.placeId() + '\u0001' + key, ignored -> new ArrayList<>()).add(new Match(locality, tier));
    }

    /** {@code id} is {@code osm-} + n/w/r + the OSM id; {@code kind} is suburb, neighbourhood, quarter or techhub. */
    public record Locality(String id, String name, String nameEn, List<String> altNames, String placeId, double lat,
                           double lng, String kind) {
        public Locality {
            altNames = altNames == null ? List.of() : List.copyOf(altNames);
            nameEn = nameEn == null ? "" : nameEn;
        }
    }

    private record Match(Locality locality, int tier) { }
}
