package com.example.backend.integration.jobs;
import com.example.backend.job.domain.LocationHint;
import java.time.LocalDateTime;
/**
 * A provider job before it is stored. {@code locationHint} (GEO-10A) carries the provider's structured location
 * (Adzuna area and point, ATS coordinates or postal code) to the resolver; null when the provider sends none.
 */
public record ExternalJob(String externalId, String title, String description, String company, String location,
                          String employmentType, Double salaryMin, Double salaryMax, String applicationUrl,
                          LocalDateTime publishedAt, LocalDateTime expiresAt, String fingerprint, LocationHint locationHint) {
    /** Without a location hint (pre-GEO-10 shape). */
    public ExternalJob(String externalId, String title, String description, String company, String location,
                       String employmentType, Double salaryMin, Double salaryMax, String applicationUrl,
                       LocalDateTime publishedAt, LocalDateTime expiresAt, String fingerprint) {
        this(externalId, title, description, company, location, employmentType, salaryMin, salaryMax, applicationUrl,
                publishedAt, expiresAt, fingerprint, null);
    }
}
