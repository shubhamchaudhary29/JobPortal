package com.example.backend.matching.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class MatchingMetricsTest {
    @Test
    void recordsOnlyBoundedOutcomeTagsWithoutCandidateOrJobIdentifiers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MatchingMetrics metrics = new MatchingMetrics(registry);
        metrics.featureExtraction("success");
        metrics.featureExtraction("failure");
        metrics.matchCalculation("success", 1_000_000);
        metrics.matchCalculation("failure", 2_000_000);

        assertEquals(1, registry.counter("job.feature.extractions", "outcome", "success").count());
        assertEquals(1, registry.counter("job.match.calculations", "outcome", "failure").count());
        assertEquals(2, registry.timer("job.match.calculation.duration").count());
        registry.getMeters().forEach(meter -> {
            Set<String> keys = meter.getId().getTags().stream().map(io.micrometer.core.instrument.Tag::getKey)
                    .collect(Collectors.toSet());
            assertTrue(keys.isEmpty() || keys.equals(Set.of("outcome")));
            assertNull(meter.getId().getTag("candidateId"));
            assertNull(meter.getId().getTag("jobId"));
            assertNull(meter.getId().getTag("email"));
        });
    }
}
