package com.example.backend.matching.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class MatchingMetrics {
    private final MeterRegistry registry;
    public MatchingMetrics(MeterRegistry registry) { this.registry = registry; }

    public void featureExtraction(String outcome) {
        registry.counter("job.feature.extractions", "outcome", outcome).increment();
    }

    public void matchCalculation(String outcome, long nanos) {
        registry.counter("job.match.calculations", "outcome", outcome).increment();
        Timer.builder("job.match.calculation.duration").register(registry).record(nanos, TimeUnit.NANOSECONDS);
    }
}
