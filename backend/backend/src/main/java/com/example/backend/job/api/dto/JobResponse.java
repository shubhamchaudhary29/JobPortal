package com.example.backend.job.api.dto;

import java.time.LocalDateTime;
import java.util.List;

public record JobResponse(String id, String title, String description, String location, String company,
                          double salary, double experience, LocalDateTime createdAt,
                          String sourceUrl, String source, String applicationUrl,
                          List<String> applicationUrls, List<String> sourceIdentities) { }
