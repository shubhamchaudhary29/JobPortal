package com.example.backend.auth.api.dto;

import com.example.backend.user.domain.UserRole;

public record RegisterResponse(String id, String email, String fullName, UserRole role) { }
