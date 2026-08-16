package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ManualSyncCoordinatorTest {
    @Test
    void providerAndEmployerRunsUseTheSameProviderLockAndCoordinator() {
        EmployerIngestionService ingestion = mock(EmployerIngestionService.class);
        DistributedLeaseLock locks = mock(DistributedLeaseLock.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
        SyncRunService runs = mock(SyncRunService.class);
        when(ingestion.requireEnabledEmployer(EmployerRegistryProperties.Source.GREENHOUSE, "board"))
                .thenReturn("board");
        when(ingestion.sync(eq(EmployerRegistryProperties.Source.GREENHOUSE), eq("board"), any()))
                .thenReturn(new EmployerIngestionService.Result(1, 0, 0, 0, 0));
        when(ingestion.sync(eq(EmployerRegistryProperties.Source.GREENHOUSE), any()))
                .thenReturn(new EmployerIngestionService.Result(0, 1, 0, 0, 0));
        when(locks.acquire("employer-ingestion:GREENHOUSE", 5_000)).thenReturn("owner-1", "owner-2");
        doReturn(heartbeat).when(scheduler)
                .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
        when(runs.begin(eq("GREENHOUSE"), eq("board"), eq(SyncRunService.Trigger.MANUAL)))
                .thenReturn(new SyncRunService.Handle("manual", Instant.EPOCH));
        when(runs.begin(eq("GREENHOUSE"), isNull(), eq(SyncRunService.Trigger.SCHEDULED)))
                .thenReturn(new SyncRunService.Handle("scheduled", Instant.EPOCH));
        IngestionCoordinator coordinator = new IngestionCoordinator(
                ingestion, locks, 5_000, 1_000, scheduler, runs);

        assertFalse(coordinator.run(EmployerRegistryProperties.Source.GREENHOUSE, "board",
                SyncRunService.Trigger.MANUAL).locked());
        assertFalse(coordinator.run(EmployerRegistryProperties.Source.GREENHOUSE,
                SyncRunService.Trigger.SCHEDULED).locked());

        verify(locks, times(2)).acquire("employer-ingestion:GREENHOUSE", 5_000);
        verify(ingestion).sync(eq(EmployerRegistryProperties.Source.GREENHOUSE), eq("board"), any());
        verify(ingestion).sync(eq(EmployerRegistryProperties.Source.GREENHOUSE), any());
        verify(runs).finish(argThat(handle -> "manual".equals(handle.runId())),
                eq(SyncRunService.Outcome.COMPLETED), any(), isNull());
        verify(runs).finish(argThat(handle -> "scheduled".equals(handle.runId())),
                eq(SyncRunService.Outcome.COMPLETED), any(), isNull());
    }
}
