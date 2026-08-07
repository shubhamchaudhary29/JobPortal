package com.example.backend.dto;

import com.example.backend.entity.UserRole;

public record AuthResponse(String accessToken, UserRole role, String email) { }
