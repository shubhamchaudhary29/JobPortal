package com.example.backend.geo.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable lookup of enabled offices by (company key, place). The resolution rule: a job of company key {@code k}
 * at place {@code p} belongs to an office only when exactly one enabled office has that key and place; with none
 * or several (ambiguous) the job stays at city level.
 */
public final class OfficeIndex {
    public static final OfficeIndex EMPTY = new OfficeIndex(List.of());
    private final Map<Pair, Office> unique;
    private final Map<String, Office> byId;

    public OfficeIndex(Collection<Office> enabledOffices) {
        Map<Pair, List<Office>> grouped = new HashMap<>();
        Map<String, Office> ids = new HashMap<>();
        for (Office office : enabledOffices) {
            grouped.computeIfAbsent(new Pair(office.companyKey(), office.placeId()), pair -> new ArrayList<>()).add(office);
            ids.put(office.id(), office);
        }
        Map<Pair, Office> single = new HashMap<>();
        grouped.forEach((pair, offices) -> { if (offices.size() == 1) single.put(pair, offices.get(0)); });
        this.unique = Map.copyOf(single);
        this.byId = Map.copyOf(ids);
    }

    /** The office jobs of {@code companyKey} at {@code placeId} belong to, if exactly one is enabled. */
    public Optional<Office> officeFor(String companyKey, String placeId) {
        if (companyKey == null || companyKey.isEmpty() || placeId == null) return Optional.empty();
        return Optional.ofNullable(unique.get(new Pair(companyKey, placeId)));
    }

    /** Any enabled office by id, including ambiguous ones (which own no jobs). */
    public Optional<Office> byId(String id) { return Optional.ofNullable(id == null ? null : byId.get(id)); }

    /** Offices that own jobs (one per company and place), in no particular order. */
    public Collection<Office> resolvable() { return unique.values(); }

    public record Office(String id, String companyKey, String company, String label, String placeId, double lat, double lng) { }

    private record Pair(String companyKey, String placeId) { }
}
