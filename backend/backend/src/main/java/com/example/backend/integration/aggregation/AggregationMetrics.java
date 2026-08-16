package com.example.backend.integration.aggregation;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class AggregationMetrics {
    private final MeterRegistry registry;

    public AggregationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String provider, SyncRunService.Outcome outcome, SyncRunService.Trigger trigger,
                       SyncRunService.Counts counts, Duration duration, boolean failurePresent) {
        Tags tags = Tags.of("provider", provider(provider), "outcome", outcome(outcome),
                "trigger", trigger(trigger));
        counter("jobportal.aggregation.runs", tags, 1);
        Timer.builder("jobportal.aggregation.duration").tags(tags).register(registry)
                .record(duration.isNegative() ? Duration.ZERO : duration);
        counter("jobportal.aggregation.inserted", tags, counts.inserted());
        counter("jobportal.aggregation.updated", tags, counts.updated());
        counter("jobportal.aggregation.unchanged", tags, counts.unchanged());
        counter("jobportal.aggregation.rejected", tags, counts.rejected());
        counter("jobportal.aggregation.retries", tags, counts.retries());
        counter("jobportal.aggregation.lifecycle.matched", tags, counts.lifecycleMatched());
        counter("jobportal.aggregation.lifecycle.modified", tags, counts.lifecycleModified());
        counter("jobportal.aggregation.attempted.batches", tags, counts.attemptedBatches());
        counter("jobportal.aggregation.attempted.employers", tags, counts.attemptedEmployers());
        long errors = (long) counts.failedItems() + counts.failedBatches() + counts.failedEmployers();
        counter("jobportal.aggregation.errors", tags, Math.max(errors, failurePresent ? 1 : 0));
        counter("jobportal.aggregation.contention", tags,
                outcome == SyncRunService.Outcome.LOCKED ? 1 : 0);
        counter("jobportal.aggregation.lease.lost", tags,
                outcome == SyncRunService.Outcome.LEASE_LOST ? 1 : 0);
    }

    private void counter(String name, Tags tags, long amount) {
        if (amount <= 0) return;
        Counter.builder(name).tags(tags).register(registry).increment(amount);
    }

    private String provider(String value) {
        if (value == null) return "other";
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "adzuna", "greenhouse", "lever" -> value.toLowerCase(Locale.ROOT);
            default -> "other";
        };
    }
    private String outcome(SyncRunService.Outcome value) {
        return value == null ? "failed" : value.name().toLowerCase(Locale.ROOT);
    }
    private String trigger(SyncRunService.Trigger value) {
        return value == null ? "unknown" : value.name().toLowerCase(Locale.ROOT);
    }
}
