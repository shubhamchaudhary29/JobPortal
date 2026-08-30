package com.example.backend.candidate.api.dto;

import java.util.List;

public record ResumeQualityResponse(
        int qualityScore,
        String scoreLabel,
        String explanation,
        List<String> strengths,
        List<Issue> issues) {

    public record Issue(String severity, String category, String message, String recommendation) { }
}
