package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AggregationMetricsTest {
    @Test
    void recordsCountsDurationContentionAndLeaseLossWithFixedLowCardinalityTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AggregationMetrics metrics = new AggregationMetrics(registry);
        SyncRunService.Counts counts = new SyncRunService.Counts(
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);

        metrics.record("LEVER", SyncRunService.Outcome.LOCKED, SyncRunService.Trigger.MANUAL,
                counts, Duration.ofMillis(250), false);
        metrics.record("untrusted-employer-name", SyncRunService.Outcome.LEASE_LOST,
                SyncRunService.Trigger.SCHEDULED, SyncRunService.Counts.empty(), Duration.ofMillis(50), true);

        assertAll(
                () -> assertEquals(1, registry.get("jobportal.aggregation.runs")
                        .tags("provider", "lever", "outcome", "locked", "trigger", "manual")
                        .counter().count()),
                () -> assertEquals(10, registry.get("jobportal.aggregation.retries")
                        .tags("provider", "lever", "outcome", "locked", "trigger", "manual")
                        .counter().count()),
                () -> assertEquals(18, registry.get("jobportal.aggregation.errors")
                        .tags("provider", "lever", "outcome", "locked", "trigger", "manual")
                        .counter().count()),
                () -> assertEquals(1, registry.get("jobportal.aggregation.contention")
                        .tags("provider", "lever", "outcome", "locked", "trigger", "manual")
                        .counter().count()),
                () -> assertEquals(1, registry.get("jobportal.aggregation.lease.lost")
                        .tags("provider", "other", "outcome", "lease_lost", "trigger", "scheduled")
                        .counter().count()),
                () -> assertEquals(250, registry.get("jobportal.aggregation.duration")
                        .tags("provider", "lever", "outcome", "locked", "trigger", "manual")
                        .timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)));

        Set<String> allowedKeys = Set.of("provider", "outcome", "trigger");
        Set<String> providers = Set.of("adzuna", "greenhouse", "lever", "other");
        Set<String> outcomes = Set.of("running", "completed", "partial", "failed", "locked", "lease_lost");
        Set<String> triggers = Set.of("scheduled", "manual", "unknown");
        for (Meter meter : registry.getMeters()) {
            assertTrue(meter.getId().getTags().stream().allMatch(tag -> allowedKeys.contains(tag.getKey())));
            assertTrue(providers.contains(meter.getId().getTag("provider")));
            assertTrue(outcomes.contains(meter.getId().getTag("outcome")));
            assertTrue(triggers.contains(meter.getId().getTag("trigger")));
            assertNull(meter.getId().getTag("employer"));
            assertNull(meter.getId().getTag("run_id"));
            assertNull(meter.getId().getTag("url"));
            assertNull(meter.getId().getTag("exception"));
        }
    }
}
