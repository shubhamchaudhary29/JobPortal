package com.example.backend.job.domain;

import java.util.List;

/**
 * Persisted place resolution for one listing (or a listing-less job). {@code version} is the resolver
 * version that produced it; a different current version marks it stale for re-resolution.
 */
public record GeoResolution(GeoStatus status, List<String> placeIds, boolean remote, GeoPoint point, String version) {
    public GeoResolution {
        placeIds = placeIds == null ? List.of() : List.copyOf(placeIds);
    }
}
