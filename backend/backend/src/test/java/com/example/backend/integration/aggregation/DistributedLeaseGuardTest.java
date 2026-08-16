package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DistributedLeaseGuardTest {
    @Test
    void renewalFailureOrExceptionMarksLostWithoutEscapingAndCloseIsIdempotent() {
        for (boolean throwsFailure : new boolean[]{false, true}) {
            DistributedLeaseLock locks = mock(DistributedLeaseLock.class);
            ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            AtomicReference<Runnable> heartbeat = new AtomicReference<>();
            doAnswer(invocation -> {
                heartbeat.set(invocation.getArgument(0));
                return future;
            }).when(scheduler).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(),
                    eq(TimeUnit.MILLISECONDS));
            if (throwsFailure) when(locks.renew("provider", "owner", 5_000))
                    .thenThrow(new RuntimeException("mongo unavailable"));
            else when(locks.renew("provider", "owner", 5_000)).thenReturn(false);

            DistributedLeaseGuard guard = DistributedLeaseGuard.start(
                    locks, "provider", "owner", 5_000, 1_000, scheduler);
            assertDoesNotThrow(() -> heartbeat.get().run());
            assertTrue(guard.isLost());
            assertFalse(guard.isValid());
            guard.close();
            guard.close();

            verify(future).cancel(true);
            verify(locks).release("provider", "owner");
        }
    }

    @Test
    void schedulingFailureReleasesOwnedLease() {
        DistributedLeaseLock locks = mock(DistributedLeaseLock.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(),
                eq(TimeUnit.MILLISECONDS))).thenThrow(new RuntimeException("scheduler unavailable"));

        assertThrows(RuntimeException.class, () -> DistributedLeaseGuard.start(
                locks, "provider", "owner", 5_000, 1_000, scheduler));

        verify(locks).release("provider", "owner");
    }
}
