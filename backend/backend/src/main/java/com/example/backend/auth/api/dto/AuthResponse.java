package com.example.backend.auth.api.dto;

import com.example.backend.user.domain.UserRole;

public record AuthResponse(String accessToken, UserRole role, String email) { }
