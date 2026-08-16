package com.example.backend.integration.aggregation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared entry point so scheduled and operator initiated runs cannot overlap. */
@Service
public class IngestionCoordinator {
    private final EmployerIngestionService ingestion;
    private final DistributedLeaseLock locks;
    private final long leaseMs;
    private final long renewalMs;
    private final ScheduledExecutorService scheduler;
    public IngestionCoordinator(EmployerIngestionService ingestion, DistributedLeaseLock locks,
                                @Value("${job-aggregation.lock-lease-ms:300000}") long leaseMs,
                                @Value("${job-aggregation.lock-renewal-ms:60000}") long renewalMs,
                                @Qualifier("ingestionLeaseScheduler") ScheduledExecutorService scheduler) {
        if (leaseMs < 1_000) throw new IllegalArgumentException("job-aggregation.lock-lease-ms must be at least 1000");
        if (renewalMs < 250 || renewalMs >= leaseMs) throw new IllegalArgumentException("lock renewal must be positive and below the lease duration");
        this.ingestion = ingestion; this.locks = locks; this.leaseMs = leaseMs; this.renewalMs = renewalMs; this.scheduler = scheduler;
    }
    public Result run(EmployerRegistryProperties.Source source) {
        String name = "employer-ingestion:" + source;
        String owner = locks.acquire(name, leaseMs);
        if (owner == null) return new Result(null, true, false);
        AtomicBoolean lost = new AtomicBoolean();
        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(() -> {
            if (!locks.renew(name, owner, leaseMs)) lost.set(true);
        }, renewalMs, renewalMs, TimeUnit.MILLISECONDS);
        try { return new Result(ingestion.sync(source), false, lost.get()); }
        finally { heartbeat.cancel(false); locks.release(name, owner); }
    }
    public record Result(EmployerIngestionService.Result sync, boolean locked, boolean leaseLost) { }
}
