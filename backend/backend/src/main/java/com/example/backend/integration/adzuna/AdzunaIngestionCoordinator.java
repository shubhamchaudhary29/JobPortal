package com.example.backend.integration.adzuna;

import com.example.backend.integration.aggregation.DistributedLeaseLock;
import com.example.backend.integration.aggregation.SyncRunService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
        String owner = locks.acquire(LOCK, leaseMs);
        if (owner == null) {
            runs.finish(run, SyncRunService.Outcome.LOCKED, SyncRunService.Counts.empty(), null);
            return new Result(null, true, false, run.runId());
        }
        AtomicBoolean lost = new AtomicBoolean();
        ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (!locks.renew(LOCK, owner, leaseMs)) lost.set(true);
            } catch (RuntimeException failure) {
                lost.set(true);
            }
        }, renewalMs, renewalMs, TimeUnit.MILLISECONDS);
        try {
            AdzunaService.SyncResult sync;
            try {
                sync = ingestion.sync(() -> !lost.get());
            } catch (RuntimeException failure) {
                runs.finish(run, lost.get() ? SyncRunService.Outcome.LEASE_LOST : SyncRunService.Outcome.FAILED,
                        SyncRunService.Counts.empty(), failure);
                throw failure;
            }
            boolean leaseLost = lost.get();
            runs.finish(run, leaseLost ? SyncRunService.Outcome.LEASE_LOST : outcome(sync), counts(sync), null);
            return new Result(sync, false, leaseLost, run.runId());
        } finally {
            heartbeat.cancel(false);
            locks.release(LOCK, owner);
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
    public record Result(AdzunaService.SyncResult sync, boolean locked, boolean leaseLost, String runId) {
        public Result(AdzunaService.SyncResult sync, boolean locked, boolean leaseLost) {
            this(sync, locked, leaseLost, null);
        }
    }
}
