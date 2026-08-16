package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;

class IngestionCoordinatorLifecycleProtectionTest {
    @Test
    void lockedRunNeverStartsIngestionOrLifecycleWork() {
        EmployerIngestionService ingestion = mock(EmployerIngestionService.class);
        DistributedLeaseLock locks = mock(DistributedLeaseLock.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        SyncRunService runs = mock(SyncRunService.class);
        SyncRunService.Handle handle = new SyncRunService.Handle("run-1", java.time.Instant.EPOCH);
        when(runs.begin(anyString(), isNull(), any())).thenReturn(handle);
        when(locks.acquire(anyString(), anyLong())).thenReturn(null);
        IngestionCoordinator coordinator = new IngestionCoordinator(
                ingestion, locks, 5_000, 1_000, scheduler, runs);

        IngestionCoordinator.Result result = coordinator.run(EmployerRegistryProperties.Source.GREENHOUSE);

        assertTrue(result.locked());
        assertNull(result.sync());
        verifyNoInteractions(ingestion, scheduler);
        verify(runs).finish(handle, SyncRunService.Outcome.LOCKED, SyncRunService.Counts.empty(), null);
    }
}
