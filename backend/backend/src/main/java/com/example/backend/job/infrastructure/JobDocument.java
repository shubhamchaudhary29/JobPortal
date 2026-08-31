package com.example.backend.job.infrastructure;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import com.example.backend.job.domain.JobMatchFeatures;

@Data
@Document(collection = "jobs")
public class JobDocument {

    @Id
    private String id;

    private String title;
    private String description;
    private String location;
    private String company;
    private double salary;
    private double experience;

    private String recruiterId;

    private LocalDateTime createdAt = LocalDateTime.now();

    private String sourceUrl;      // original posting URL; null for manually-created jobs
    private String source;         // "manual" or "adzuna"
    private String externalId;     // Adzuna's job id, used to avoid duplicate imports
    private LocalDateTime fetchedAt;
    private LocalDateTime lastSeenAt;
    private String employmentType;
    private Double salaryMin;
    private Double salaryMax;
    private String applicationUrl;
    private LocalDateTime publishedAt;
    private LocalDateTime firstSeenAt;
    private LocalDateTime expiresAt;
    /** Null is deliberately treated as active for pre-Phase-1 documents. */
    private Boolean active = true;
    private String fingerprint;
    private Set<String> sourceIdentities = new LinkedHashSet<>();
    /** Every original provider deep link associated with the canonical job. */
    private Set<String> applicationUrls = new LinkedHashSet<>();
    private java.util.List<ImportedSourceListing> sourceListings = new java.util.ArrayList<>();
    private LocalDateTime lastSuccessfulSyncAt;
    private int consecutiveMissingRuns;
    private String inactiveReason;
    private LocalDateTime inactiveAt;
    private String reconciliationTargetId;
    private String reconciliationConflictId;
    private String reconciliationOriginalFingerprint;
    /** Conservatively retained application claims; cleanup also checks legacy application documents. */
    private long applicationReferenceCount;
    private String cleanupClaimId;
    private LocalDateTime cleanupClaimedAt;
    /** Provider-neutral, versioned invariant features; candidate-specific scores are never stored here. */
    private JobMatchFeatures matchFeatures;

}
