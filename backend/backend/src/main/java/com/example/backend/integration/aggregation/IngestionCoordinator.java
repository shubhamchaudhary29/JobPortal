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
    private final SyncRunService runs;
    public IngestionCoordinator(EmployerIngestionService ingestion, DistributedLeaseLock locks,
                                @Value("${job-aggregation.lock-lease-ms:300000}") long leaseMs,
                                @Value("${job-aggregation.lock-renewal-ms:60000}") long renewalMs,
                                @Qualifier("ingestionLeaseScheduler") ScheduledExecutorService scheduler,
                                SyncRunService runs) {
        if (leaseMs < 1_000) throw new IllegalArgumentException("job-aggregation.lock-lease-ms must be at least 1000");
        if (renewalMs < 250 || renewalMs >= leaseMs) throw new IllegalArgumentException("lock renewal must be positive and below the lease duration");
        this.ingestion = ingestion;
        this.locks = locks;
        this.leaseMs = leaseMs;
        this.renewalMs = renewalMs;
        this.scheduler = scheduler;
        this.runs = runs;
    }
    public Result run(EmployerRegistryProperties.Source source) {
        return run(source, SyncRunService.Trigger.MANUAL);
    }
    public Result run(EmployerRegistryProperties.Source source, SyncRunService.Trigger trigger) {
        return run(source, null, trigger);
    }
    public Result run(EmployerRegistryProperties.Source source, String employer, SyncRunService.Trigger trigger) {
        String selectedEmployer = employer == null || employer.isBlank()
                ? null : ingestion.requireEnabledEmployer(source, employer);
        SyncRunService.Handle run = runs.begin(source.name(), selectedEmployer, trigger);
        String name = "employer-ingestion:" + source;
        String owner = locks.acquire(name, leaseMs);
        if (owner == null) {
            runs.finish(run, SyncRunService.Outcome.LOCKED, SyncRunService.Counts.empty(), null);
            return new Result(null, true, false, run.runId());
        }
        AtomicBoolean lost = new AtomicBoolean();
        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(() -> {
            try { if (!locks.renew(name, owner, leaseMs)) lost.set(true); }
            catch (RuntimeException renewalFailure) { lost.set(true); }
        }, renewalMs, renewalMs, TimeUnit.MILLISECONDS);
        try {
            EmployerIngestionService.Result sync;
            try {
                sync = selectedEmployer == null
                        ? ingestion.sync(source, () -> !lost.get())
                        : ingestion.sync(source, selectedEmployer, () -> !lost.get());
            } catch (RuntimeException failure) {
                runs.finish(run, lost.get() ? SyncRunService.Outcome.LEASE_LOST : SyncRunService.Outcome.FAILED,
                        SyncRunService.Counts.empty(), failure);
                throw failure;
            }
            boolean leaseLost = lost.get();
            runs.finish(run, leaseLost ? SyncRunService.Outcome.LEASE_LOST : outcome(sync), counts(sync), null);
            return new Result(sync, false, leaseLost, run.runId());
        }
        finally { heartbeat.cancel(false); locks.release(name, owner); }
    }
    private SyncRunService.Outcome outcome(EmployerIngestionService.Result sync) {
        if (sync.failedEmployers() == 0 && sync.rejected() == 0) return SyncRunService.Outcome.COMPLETED;
        if (sync.inserted() + sync.updated() + sync.unchanged() == 0 && sync.failedEmployers() > 0) {
            return SyncRunService.Outcome.FAILED;
        }
        return SyncRunService.Outcome.PARTIAL;
    }
    private SyncRunService.Counts counts(EmployerIngestionService.Result sync) {
        return new SyncRunService.Counts(sync.inserted(), sync.updated(), sync.unchanged(), sync.rejected(),
                0, 0, sync.failedEmployers(), sync.lifecycleMatched(), sync.lifecycleModified(), 0,
                0, sync.attemptedEmployers());
    }
    public record Result(EmployerIngestionService.Result sync, boolean locked, boolean leaseLost, String runId) {
        public Result(EmployerIngestionService.Result sync, boolean locked, boolean leaseLost) {
            this(sync, locked, leaseLost, null);
        }
    }
}
