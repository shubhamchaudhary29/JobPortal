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
}
