package com.example.backend.job.infrastructure;

import java.time.LocalDateTime;
import lombok.Data;

/** Additive imported-listing state; lifecycle transitions are introduced in M1C. */
@Data
public class ImportedSourceListing {
    private String identity;
    private String provider;
    private String employer;
    private String externalId;
    private String applicationUrl;
    private LocalDateTime firstSeenAt;
    private LocalDateTime lastSeenAt;
    private boolean active = true;
    private int consecutiveMissingRuns;
    /** This provider's own location string (GEO-1); canonical {@code location} stays last-writer-wins. */
    private String rawLocation;
    /** True when {@code rawLocation} was seeded from the canonical location by re-resolution, not the provider. */
    private boolean rawLocationInferred;
    /**
     * The provider's structured area (Adzuna {@code location.area[]}, GEO-10A), kept so re-resolution can use it as the
     * hint again; documents written before GEO-10 have none.
     */
    private java.util.List<String> rawArea;
    private com.example.backend.job.domain.GeoResolution geo;
}
