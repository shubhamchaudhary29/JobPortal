package com.example.backend.user.application;

public interface ApplicationStatsProvider {
    ApplicationStats forCandidate(String email);
}
