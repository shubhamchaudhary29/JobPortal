package com.example.backend.user.api.dto;

import com.example.backend.user.domain.UserRole;

public record UserResponse(String id, String email, String fullName, UserRole role,
                           int totalApplications, int acceptedApplications,
                           int rejectedApplications, int pendingApplications) { }
