package com.example.backend.job.infrastructure;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

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

}
