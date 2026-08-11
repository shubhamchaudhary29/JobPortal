package com.example.backend.integration.jobs;
import java.time.LocalDateTime;
public record ExternalJob(String externalId, String title, String description, String company, String location,
                          String employmentType, Double salaryMin, Double salaryMax, String applicationUrl,
                          LocalDateTime publishedAt, LocalDateTime expiresAt, String fingerprint) { }
