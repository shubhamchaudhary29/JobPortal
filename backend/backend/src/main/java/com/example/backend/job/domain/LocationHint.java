package com.example.backend.job.domain;

import java.util.List;

/** Structured location from a provider (Adzuna {@code location.area[]} and coordinates). Never persisted. */
public record LocationHint(List<String> area, Double lat, Double lng) {
    public LocationHint {
        area = area == null ? List.of() : area.stream().filter(java.util.Objects::nonNull).toList();
    }
}
