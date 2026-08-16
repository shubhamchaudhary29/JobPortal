package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.backend.integration.adzuna.AdzunaIngestionCoordinator;
import com.example.backend.integration.adzuna.AdzunaService;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.query.Query;

@SpringBootTest
class SyncRunMongoIntegrationTest {
    @Autowired SyncRunService runs;
    @Autowired MongoTemplate mongo;

    @BeforeEach
    void clear() {
        mongo.remove(new Query(), SyncRunDocument.class);
    }

    @Test
    void everyOutcomePersistsExactlyOnceWithBoundedSanitizedDetailsAndIndexes() {
        for (SyncRunService.Outcome outcome : List.of(SyncRunService.Outcome.COMPLETED,
                SyncRunService.Outcome.PARTIAL, SyncRunService.Outcome.FAILED,
                SyncRunService.Outcome.LOCKED, SyncRunService.Outcome.LEASE_LOST)) {
            SyncRunService.Handle handle = runs.begin("LEVER", "board", SyncRunService.Trigger.MANUAL);
            RuntimeException failure = outcome == SyncRunService.Outcome.FAILED
                    ? new RuntimeException("request https://provider.test/jobs?api_key=secret token=also-secret\n" + "x".repeat(400))
                    : null;
            runs.finish(handle, outcome, new SyncRunService.Counts(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), failure);
        }

        List<SyncRunDocument> persisted = mongo.findAll(SyncRunDocument.class);
        assertEquals(5, persisted.size());
        SyncRunDocument failed = persisted.stream()
                .filter(run -> run.getOutcome() == SyncRunService.Outcome.FAILED).findFirst().orElseThrow();
        assertAll(
                () -> assertEquals("lever", failed.getProvider()),
                () -> assertEquals("board", failed.getEmployer()),
                () -> assertEquals(1, failed.getInserted()),
                () -> assertEquals(9, failed.getLifecycleModified()),
                () -> assertEquals(10, failed.getRetries()),
                () -> assertEquals(11, failed.getAttemptedBatches()),
                () -> assertEquals(12, failed.getAttemptedEmployers()),
                () -> assertEquals("RuntimeException", failed.getFailureType()),
                () -> assertTrue(failed.getFailureDetail().length() <= 240),
                () -> assertFalse(failed.getFailureDetail().contains("provider.test")),
                () -> assertFalse(failed.getFailureDetail().contains("also-secret")),
                () -> assertNotNull(failed.getCompletedAt()),
                () -> assertTrue(failed.getExpiresAt().isAfter(failed.getStartedAt())),
                () -> assertThrows(IllegalStateException.class,
                        () -> runs.finish(new SyncRunService.Handle(failed.getRunId(), failed.getStartedAt()),
                                SyncRunService.Outcome.COMPLETED, SyncRunService.Counts.empty(), null)));

        List<IndexInfo> indexes = mongo.indexOps(SyncRunDocument.class).getIndexInfo();
        boolean ttlIndex = mongo.getCollection("sync_runs").listIndexes().into(new java.util.ArrayList<>()).stream()
                .anyMatch(index -> "sync_run_retention_ttl".equals(index.getString("name"))
                        && index.get("expireAfterSeconds") instanceof Number seconds
                        && seconds.longValue() == 0);
        assertAll(
                () -> assertTrue(indexes.stream().anyMatch(index -> "sync_run_scope_started_idx".equals(index.getName()))),
                () -> assertTrue(indexes.stream().anyMatch(index -> "sync_run_outcome_started_idx".equals(index.getName()))),
                () -> assertTrue(ttlIndex));
    }

    @Test
    void employerCoordinatorRecordsPartialLockedLeaseLostAndThrownRuns() {
        EmployerIngestionService ingestion = mock(EmployerIngestionService.class);
        DistributedLeaseLock locks = mock(DistributedLeaseLock.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
        doReturn(heartbeat).when(scheduler)
                .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
        when(locks.acquire(anyString(), anyLong())).thenReturn("owner", null, "owner", "owner");
        when(ingestion.sync(eq(EmployerRegistryProperties.Source.GREENHOUSE), any()))
                .thenReturn(new EmployerIngestionService.Result(1, 2, 3, 4, 1, 5, 6, 7))
                .thenReturn(new EmployerIngestionService.Result(0, 0, 1, 0, 0, 2, 2, 1))
                .thenThrow(new RuntimeException("password=hunter2 https://private.test/path"));

        IngestionCoordinator coordinator = new IngestionCoordinator(
                ingestion, locks, 5_000, 1_000, scheduler, runs);
        coordinator.run(EmployerRegistryProperties.Source.GREENHOUSE, SyncRunService.Trigger.MANUAL);
        coordinator.run(EmployerRegistryProperties.Source.GREENHOUSE, SyncRunService.Trigger.SCHEDULED);

        doAnswer(invocation -> {
                    invocation.<Runnable>getArgument(0).run();
                    return heartbeat;
                }).when(scheduler)
                .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
        when(locks.renew(anyString(), anyString(), anyLong())).thenReturn(false);
        coordinator.run(EmployerRegistryProperties.Source.GREENHOUSE, SyncRunService.Trigger.SCHEDULED);
        doReturn(heartbeat).when(scheduler)
                .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
        when(locks.renew(anyString(), anyString(), anyLong())).thenReturn(true);
        assertThrows(RuntimeException.class, () -> coordinator.run(
                EmployerRegistryProperties.Source.GREENHOUSE, SyncRunService.Trigger.MANUAL));

        List<SyncRunDocument> persisted = mongo.findAll(SyncRunDocument.class);
        assertEquals(4, persisted.size());
        assertEquals(java.util.Set.of(SyncRunService.Outcome.FAILED, SyncRunService.Outcome.LEASE_LOST,
                        SyncRunService.Outcome.LOCKED, SyncRunService.Outcome.PARTIAL),
                persisted.stream().map(SyncRunDocument::getOutcome)
                        .collect(java.util.stream.Collectors.toSet()));
        SyncRunDocument partial = persisted.stream().filter(run -> run.getOutcome() == SyncRunService.Outcome.PARTIAL)
                .findFirst().orElseThrow();
        SyncRunDocument failure = persisted.stream().filter(run -> run.getOutcome() == SyncRunService.Outcome.FAILED)
                .findFirst().orElseThrow();
        assertAll(
                () -> assertEquals(5, partial.getLifecycleMatched()),
                () -> assertEquals(6, partial.getLifecycleModified()),
                () -> assertEquals(7, partial.getAttemptedEmployers()),
                () -> assertEquals(SyncRunService.Trigger.MANUAL, partial.getTrigger()),
                () -> assertFalse(failure.getFailureDetail().contains("hunter2")),
                () -> assertFalse(failure.getFailureDetail().contains("private.test")));
    }

    @Test
    void adzunaCoordinatorPersistsCompletedCountsAndScheduledTrigger() {
        AdzunaService ingestion = mock(AdzunaService.class);
        DistributedLeaseLock locks = mock(DistributedLeaseLock.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
        when(locks.acquire(anyString(), anyLong())).thenReturn("owner");
        doReturn(heartbeat).when(scheduler)
                .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
        when(ingestion.sync(any())).thenReturn(new AdzunaService.SyncResult(
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, AdzunaService.Outcome.FULL_SUCCESS));
        AdzunaIngestionCoordinator coordinator = new AdzunaIngestionCoordinator(
                ingestion, locks, 5_000, 1_000, scheduler, runs);

        AdzunaIngestionCoordinator.Result result = coordinator.run(SyncRunService.Trigger.SCHEDULED);

        SyncRunDocument persisted = mongo.findById(result.runId(), SyncRunDocument.class);
        assertAll(
                () -> assertNotNull(persisted),
                () -> assertEquals(SyncRunService.Outcome.COMPLETED, persisted.getOutcome()),
                () -> assertEquals(SyncRunService.Trigger.SCHEDULED, persisted.getTrigger()),
                () -> assertEquals(6, persisted.getFailedItems()),
                () -> assertEquals(5, persisted.getFailedBatches()),
                () -> assertEquals(7, persisted.getRetries()),
                () -> assertEquals(8, persisted.getLifecycleMatched()),
                () -> assertEquals(9, persisted.getLifecycleModified()),
                () -> assertEquals(10, persisted.getAttemptedBatches()));
    }
}
