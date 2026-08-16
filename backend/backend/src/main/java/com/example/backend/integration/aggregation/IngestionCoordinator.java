package com.example.backend.integration.aggregation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import java.util.concurrent.ScheduledExecutorService;

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
        String owner;
        try {
            owner = locks.acquire(name, leaseMs);
        } catch (RuntimeException lockFailure) {
            runs.finish(run, SyncRunService.Outcome.FAILED, SyncRunService.Counts.empty(), lockFailure);
            throw lockFailure;
        }
        if (owner == null) {
            runs.finish(run, SyncRunService.Outcome.LOCKED, SyncRunService.Counts.empty(), null);
            return new Result(null, true, false, run.runId());
        }
        DistributedLeaseGuard guard;
        try {
            guard = DistributedLeaseGuard.start(locks, name, owner, leaseMs, renewalMs, scheduler);
        } catch (RuntimeException heartbeatFailure) {
            runs.finish(run, SyncRunService.Outcome.FAILED, SyncRunService.Counts.empty(), heartbeatFailure);
            throw heartbeatFailure;
        }
        try (guard) {
            EmployerIngestionService.Result sync;
            try {
                sync = selectedEmployer == null
                        ? ingestion.sync(source, guard::isValid)
                        : ingestion.sync(source, selectedEmployer, guard::isValid);
            } catch (RuntimeException failure) {
                runs.finish(run, guard.isLost() ? SyncRunService.Outcome.LEASE_LOST : SyncRunService.Outcome.FAILED,
                        SyncRunService.Counts.empty(), failure);
                throw failure;
            }
            boolean leaseLost = guard.isLost();
            runs.finish(run, leaseLost ? SyncRunService.Outcome.LEASE_LOST : outcome(sync), counts(sync), null);
            return new Result(sync, false, leaseLost, run.runId());
        }
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
    public record Result(EmployerIngestionService.Result sync, boolean locked, boolean leaseLost, String runId,
                         SyncRunService.Outcome outcome) {
        public Result(EmployerIngestionService.Result sync, boolean locked, boolean leaseLost, String runId) {
            this(sync, locked, leaseLost, runId, derive(sync, locked, leaseLost));
        }
        public Result(EmployerIngestionService.Result sync, boolean locked, boolean leaseLost) {
            this(sync, locked, leaseLost, null, derive(sync, locked, leaseLost));
        }
        private static SyncRunService.Outcome derive(EmployerIngestionService.Result sync,
                                                      boolean locked, boolean leaseLost) {
            if (locked) return SyncRunService.Outcome.LOCKED;
            if (leaseLost) return SyncRunService.Outcome.LEASE_LOST;
            if (sync == null) return SyncRunService.Outcome.FAILED;
            if (sync.failedEmployers() == 0 && sync.rejected() == 0) return SyncRunService.Outcome.COMPLETED;
            if (sync.inserted() + sync.updated() + sync.unchanged() == 0 && sync.failedEmployers() > 0) {
                return SyncRunService.Outcome.FAILED;
            }
            return SyncRunService.Outcome.PARTIAL;
        }
    }
}
