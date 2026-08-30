package com.example.backend.candidate.api.dto;

import com.example.backend.candidate.domain.ResumeParsingStatus;

import java.time.Instant;
import java.util.List;

public record ResumeStatusResponse(
        ResumeParsingStatus status,
        String filename,
        Instant uploadedAt,
        String parserVersion,
        String errorCode,
        String errorMessage,
        List<String> warnings,
        ResumeQualityResponse quality) { }
