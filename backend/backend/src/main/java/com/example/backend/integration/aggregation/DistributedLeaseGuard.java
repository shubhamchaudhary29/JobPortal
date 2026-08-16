package com.example.backend.integration.aggregation;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one lease heartbeat and exposes a cancellation token to the guarded operation. */
public final class DistributedLeaseGuard implements AutoCloseable {
    private final DistributedLeaseLock locks;
    private final String name;
    private final String owner;
    private final AtomicBoolean lost = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledFuture<?> heartbeat;

    private DistributedLeaseGuard(DistributedLeaseLock locks, String name, String owner, long leaseMs,
                                  long renewalMs, ScheduledExecutorService scheduler) {
        this.locks = locks;
        this.name = name;
        this.owner = owner;
        try {
            heartbeat = scheduler.scheduleAtFixedRate(() -> {
                try {
                    if (!locks.renew(name, owner, leaseMs)) lost.set(true);
                } catch (RuntimeException renewalFailure) {
                    lost.set(true);
                }
            }, renewalMs, renewalMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException schedulingFailure) {
            lost.set(true);
            locks.release(name, owner);
            throw schedulingFailure;
        }
    }

    public static DistributedLeaseGuard start(DistributedLeaseLock locks, String name, String owner,
                                               long leaseMs, long renewalMs,
                                               ScheduledExecutorService scheduler) {
        return new DistributedLeaseGuard(locks, name, owner, leaseMs, renewalMs, scheduler);
    }

    public boolean isValid() {
        return !lost.get() && !closed.get();
    }

    public boolean isLost() {
        return lost.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        lost.set(true);
        heartbeat.cancel(true);
        locks.release(name, owner);
    }
}
