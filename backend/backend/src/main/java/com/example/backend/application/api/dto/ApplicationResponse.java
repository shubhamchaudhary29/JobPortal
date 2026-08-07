package com.example.backend.application.api.dto;

import com.example.backend.application.domain.ApplicationStatus;
import java.time.LocalDateTime;

public record ApplicationResponse(String id, String jobId, String candidateEmail,
                                  ApplicationStatus status, LocalDateTime appliedAt) { }
