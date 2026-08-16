package com.example.backend.integration.adzuna;

import com.example.backend.integration.aggregation.DistributedLeaseLock;
import com.example.backend.integration.aggregation.SyncRunService;
import com.example.backend.integration.aggregation.DistributedLeaseGuard;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.concurrent.ScheduledExecutorService;

/** The same Mongo lease protects scheduled and operator Adzuna execution across instances. */
@Service
public class AdzunaIngestionCoordinator {
    private static final String LOCK = "provider-ingestion:adzuna";
    private final AdzunaService ingestion;
    private final DistributedLeaseLock locks;
    private final long leaseMs;
    private final long renewalMs;
    private final ScheduledExecutorService scheduler;
    private final SyncRunService runs;
    public AdzunaIngestionCoordinator(AdzunaService ingestion, DistributedLeaseLock locks,
            @Value("${job-aggregation.lock-lease-ms:300000}") long leaseMs,
            @Value("${job-aggregation.lock-renewal-ms:60000}") long renewalMs,
            @Qualifier("ingestionLeaseScheduler") ScheduledExecutorService scheduler,
            SyncRunService runs) {
        if (leaseMs < 1_000 || renewalMs < 250 || renewalMs >= leaseMs) {
            throw new IllegalArgumentException("invalid Adzuna lease/renewal configuration");
        }
        this.ingestion = ingestion;
        this.locks = locks;
        this.leaseMs = leaseMs;
        this.renewalMs = renewalMs;
        this.scheduler = scheduler;
        this.runs = runs;
    }
    public Result run() { return run(SyncRunService.Trigger.MANUAL); }
    public Result run(SyncRunService.Trigger trigger) {
        SyncRunService.Handle run = runs.begin("adzuna", null, trigger);
        String owner;
        try {
            owner = locks.acquire(LOCK, leaseMs);
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
            guard = DistributedLeaseGuard.start(locks, LOCK, owner, leaseMs, renewalMs, scheduler);
        } catch (RuntimeException heartbeatFailure) {
            runs.finish(run, SyncRunService.Outcome.FAILED, SyncRunService.Counts.empty(), heartbeatFailure);
            throw heartbeatFailure;
        }
        try (guard) {
            AdzunaService.SyncResult sync;
            try {
                sync = ingestion.sync(guard::isValid);
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
    private SyncRunService.Outcome outcome(AdzunaService.SyncResult sync) {
        return switch (sync.outcome()) {
            case FULL_SUCCESS -> SyncRunService.Outcome.COMPLETED;
            case PARTIAL_FAILURE -> SyncRunService.Outcome.PARTIAL;
            case COMPLETE_FAILURE -> SyncRunService.Outcome.FAILED;
            case OVERLAP_REJECTED -> SyncRunService.Outcome.LOCKED;
        };
    }
    private SyncRunService.Counts counts(AdzunaService.SyncResult sync) {
        return new SyncRunService.Counts(sync.inserted(), sync.updated(), sync.unchanged(), sync.rejected(),
                sync.failedItems(), sync.failedBatches(), 0, sync.lifecycleMatched(),
                sync.lifecycleModified(), sync.retries(), sync.attemptedBatches(), 0);
    }
    @Scheduled(fixedDelayString = "${adzuna.schedule-delay-ms:43200000}")
    void scheduledSync() { run(SyncRunService.Trigger.SCHEDULED); }
    public record Result(AdzunaService.SyncResult sync, boolean locked, boolean leaseLost, String runId,
                         SyncRunService.Outcome outcome) {
        public Result(AdzunaService.SyncResult sync, boolean locked, boolean leaseLost, String runId) {
            this(sync, locked, leaseLost, runId, derive(sync, locked, leaseLost));
        }
        public Result(AdzunaService.SyncResult sync, boolean locked, boolean leaseLost) {
            this(sync, locked, leaseLost, null, derive(sync, locked, leaseLost));
        }
        private static SyncRunService.Outcome derive(AdzunaService.SyncResult sync,
                                                      boolean locked, boolean leaseLost) {
            if (locked) return SyncRunService.Outcome.LOCKED;
            if (leaseLost) return SyncRunService.Outcome.LEASE_LOST;
            if (sync == null) return SyncRunService.Outcome.FAILED;
            return switch (sync.outcome()) {
                case FULL_SUCCESS -> SyncRunService.Outcome.COMPLETED;
                case PARTIAL_FAILURE -> SyncRunService.Outcome.PARTIAL;
                case COMPLETE_FAILURE -> SyncRunService.Outcome.FAILED;
                case OVERLAP_REJECTED -> SyncRunService.Outcome.LOCKED;
            };
        }
    }
}
