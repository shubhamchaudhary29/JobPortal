package com.example.backend.application.api.dto;

import com.example.backend.application.domain.ApplicationStatus;
import java.time.LocalDateTime;

public record ApplicationSummaryResponse(String applicationId, ApplicationStatus status, LocalDateTime appliedAt,
                                         String jobId, String jobTitle, String jobCompany, String jobLocation,
                                         double jobSalary, String sourceUrl) { }
