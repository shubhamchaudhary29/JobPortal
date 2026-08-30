package com.example.backend.candidate.application;

import com.example.backend.candidate.domain.ResumeParsingStatus;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class CandidateIntelligenceMetrics {
    private final MeterRegistry registry;
    public CandidateIntelligenceMetrics(MeterRegistry registry) { this.registry = registry; }
    public void uploadAccepted() { registry.counter("candidate.resume.uploads", "outcome", "accepted").increment(); }
    public void uploadRejected() { registry.counter("candidate.resume.uploads", "outcome", "rejected").increment(); }
    public void parsed(ResumeParsingStatus status) {
        String value = switch (status) {
            case PARSED -> "parsed";
            case PARTIALLY_PARSED -> "partial";
            case OCR_REQUIRED -> "ocr_required";
            default -> "failed";
        };
        registry.counter("candidate.resume.parses", "outcome", value).increment();
    }
}
