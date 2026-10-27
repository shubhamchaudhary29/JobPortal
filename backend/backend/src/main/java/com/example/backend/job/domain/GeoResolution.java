package com.example.backend.job.domain;

import java.util.List;

/**
 * Persisted place resolution for one listing (or a listing-less job). {@code version} is the resolver
 * version that produced it; a different current version marks it stale for re-resolution. {@code localityIds}
 * (GEO-8) are OpenStreetMap localities inside {@code placeIds}, in the order the resolver found them; documents
 * written before GEO-8 have none and read back with an empty list.
 *
 * <p>{@code siteLabel} (GEO-9A) is the ATS's own name for the office or building in that city ("We Work", "Urmi"),
 * kept as text and never geocoded, so the UI can say which office a city-centre job names. Null when the label named
 * none; documents written before GEO-9 read back with null.
 */
public record GeoResolution(GeoStatus status, List<String> placeIds, boolean remote, GeoPoint point, String version,
                            List<String> localityIds, String siteLabel) {
    public GeoResolution {
        placeIds = placeIds == null ? List.of() : List.copyOf(placeIds);
        localityIds = localityIds == null ? List.of() : List.copyOf(localityIds);
    }

    /** Without localities (pre-GEO-8 shape). */
    public GeoResolution(GeoStatus status, List<String> placeIds, boolean remote, GeoPoint point, String version) {
        this(status, placeIds, remote, point, version, List.of(), null);
    }

    /** Without a site label (pre-GEO-9 shape). */
    public GeoResolution(GeoStatus status, List<String> placeIds, boolean remote, GeoPoint point, String version,
                         List<String> localityIds) {
        this(status, placeIds, remote, point, version, localityIds, null);
    }
}
