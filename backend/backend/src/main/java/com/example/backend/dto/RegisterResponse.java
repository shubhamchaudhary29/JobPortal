package com.example.backend.dto;

import com.example.backend.entity.UserRole;

public record RegisterResponse(String id, String email, String fullName, UserRole role) { }
