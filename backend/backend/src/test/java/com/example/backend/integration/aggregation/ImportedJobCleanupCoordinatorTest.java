package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;

class ImportedJobCleanupCoordinatorTest {
    @Test
    void lockContentionSkipsCleanup() {
        ImportedJobCleanupService cleanup = mock(ImportedJobCleanupService.class);
        DistributedLeaseLock locks = mock(DistributedLeaseLock.class);
        when(locks.acquire(ImportedJobCleanupCoordinator.LOCK_NAME, 5_000)).thenReturn(null);

        ImportedJobCleanupCoordinator.Result result =
                new ImportedJobCleanupCoordinator(cleanup, locks, 5_000).run();

        assertTrue(result.locked());
        assertNull(result.cleanup());
        verifyNoInteractions(cleanup);
    }

    @Test
    void ownerRunsBoundedCleanupAndReleasesItsLease() {
        ImportedJobCleanupService cleanup = mock(ImportedJobCleanupService.class);
        DistributedLeaseLock locks = mock(DistributedLeaseLock.class);
        ImportedJobCleanupService.Result cleanupResult = new ImportedJobCleanupService.Result(2, 1, 1);
        when(locks.acquire(ImportedJobCleanupCoordinator.LOCK_NAME, 5_000)).thenReturn("owner");
        when(cleanup.cleanup(any())).thenReturn(cleanupResult);

        ImportedJobCleanupCoordinator.Result result =
                new ImportedJobCleanupCoordinator(cleanup, locks, 5_000).run();

        assertFalse(result.locked());
        assertEquals(cleanupResult, result.cleanup());
        verify(locks).release(ImportedJobCleanupCoordinator.LOCK_NAME, "owner");
    }
}
