package com.example.backend.application.api.dto;

import com.example.backend.application.domain.ApplicationStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateApplicationStatusRequest(@NotNull ApplicationStatus status) { }
