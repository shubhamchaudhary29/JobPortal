package com.example.backend.candidate.application.extraction;

import com.example.backend.candidate.application.storage.ResumeDocumentType;

public interface ResumeTextExtractor {
    ResumeDocumentType type();
    ResumeTextExtraction extract(byte[] bytes);
}
