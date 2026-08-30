package com.example.backend.candidate.domain;

public enum ResumeParsingStatus {
    NOT_UPLOADED,
    PROCESSING,
    PARSED,
    PARTIALLY_PARSED,
    FAILED,
    OCR_REQUIRED
}
