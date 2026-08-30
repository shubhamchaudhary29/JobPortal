package com.example.backend.candidate.application;

import com.example.backend.candidate.domain.ResumeParsingStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CandidateIntelligenceMetricsTest {
    @Test
    void recordsOnlyBoundedOutcomeTagsWithoutCandidateIdentifiers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CandidateIntelligenceMetrics metrics = new CandidateIntelligenceMetrics(registry);
        metrics.uploadAccepted(); metrics.uploadRejected();
        metrics.parsed(ResumeParsingStatus.PARSED); metrics.parsed(ResumeParsingStatus.PARTIALLY_PARSED);
        metrics.parsed(ResumeParsingStatus.OCR_REQUIRED); metrics.parsed(ResumeParsingStatus.FAILED);
        assertEquals(1, registry.counter("candidate.resume.uploads", "outcome", "accepted").count());
        assertEquals(1, registry.counter("candidate.resume.parses", "outcome", "ocr_required").count());
        registry.getMeters().forEach(meter -> assertEquals(java.util.Set.of("outcome"), meter.getId().getTags().stream()
                .map(io.micrometer.core.instrument.Tag::getKey).collect(java.util.stream.Collectors.toSet())));
    }
}
