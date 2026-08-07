package com.example.backend.user;

import com.example.backend.user.api.dto.UserResponse;
import com.example.backend.user.application.ApplicationStats;
import com.example.backend.user.infrastructure.UserDocument;

public final class UserMapper {
    private UserMapper() { }

    public static UserResponse toResponse(UserDocument user, ApplicationStats stats) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole(), stats.total(),
                stats.accepted(), stats.rejected(), stats.pending());
    }
}
