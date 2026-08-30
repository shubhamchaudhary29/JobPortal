package com.example.backend.candidate.application.storage;

public record ValidatedResumeFile(byte[] bytes, ResumeDocumentType type, String displayFilename, String sha256) { }
