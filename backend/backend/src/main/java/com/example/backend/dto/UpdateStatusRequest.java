package com.example.backend.dto;

import com.example.backend.entity.ApplicationStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateStatusRequest(@NotNull ApplicationStatus status) { }
