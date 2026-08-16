package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mongodb.client.result.UpdateResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

class SyncRunObservabilityTest {
    @Test
    void emitsMetricsOnlyAfterDurableFinalization() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AggregationMetrics metrics = mock(AggregationMetrics.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-16T12:00:00Z"), ZoneOffset.UTC);
        SyncRunService service = new SyncRunService(mongo, clock, 30, metrics);
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(SyncRunDocument.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        SyncRunService.Counts counts = new SyncRunService.Counts(
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);

        SyncRunService.Handle handle = service.begin("LEVER", "private-employer",
                SyncRunService.Trigger.SCHEDULED);
        service.finish(handle, SyncRunService.Outcome.PARTIAL, counts, null);

        verify(metrics).record("lever", SyncRunService.Outcome.PARTIAL,
                SyncRunService.Trigger.SCHEDULED, counts, Duration.ZERO, false);
    }

    @Test
    void missingOrAlreadyCompletedRunCannotProduceMetrics() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AggregationMetrics metrics = mock(AggregationMetrics.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-16T12:00:00Z"), ZoneOffset.UTC);
        SyncRunService service = new SyncRunService(mongo, clock, 30, metrics);
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(SyncRunDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertThrows(IllegalStateException.class, () -> service.finish(
                new SyncRunService.Handle("run", clock.instant(), "greenhouse", SyncRunService.Trigger.MANUAL),
                SyncRunService.Outcome.FAILED, SyncRunService.Counts.empty(), new RuntimeException("failure")));

        verifyNoInteractions(metrics);
    }
}
