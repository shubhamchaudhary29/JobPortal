package com.example.backend.integration.search;

import com.example.backend.integration.aggregation.DistributedLeaseGuard;
import com.example.backend.integration.aggregation.DistributedLeaseLock;
import com.example.backend.integration.aggregation.SyncRunService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Shared Mongo lease ({@code provider-ingestion:<provider>}) and durable sync-run history for one search
 * provider, used by scheduled and manual runs alike. A budget-stopped run is recorded as {@code PARTIAL} with
 * reason {@code BUDGET_EXHAUSTED}.
 */
public class SearchIngestionCoordinator implements SearchCoordinatorSource {
    private final String provider;
    private final Function<BooleanSupplier, SearchSyncResult> ingestion;
    private final DistributedLeaseLock locks;
    private final long leaseMs;
    private final long renewalMs;
    private final ScheduledExecutorService scheduler;
    private final SyncRunService runs;

    public SearchIngestionCoordinator(String provider, Function<BooleanSupplier, SearchSyncResult> ingestion,
                                      DistributedLeaseLock locks, long leaseMs, long renewalMs,
                                      ScheduledExecutorService scheduler, SyncRunService runs) {
        if (leaseMs < 1_000 || renewalMs < 250 || renewalMs >= leaseMs) {
            throw new IllegalArgumentException("invalid search-provider lease/renewal configuration");
        }
        this.provider = provider;
        this.ingestion = ingestion;
        this.locks = locks;
        this.leaseMs = leaseMs;
        this.renewalMs = renewalMs;
        this.scheduler = scheduler;
        this.runs = runs;
    }

    public String provider() { return provider; }

    @Override
    public SearchIngestionCoordinator searchCoordinator() { return this; }

    public Result run(SyncRunService.Trigger trigger) {
        String lock = "provider-ingestion:" + provider;
        SyncRunService.Handle run = runs.begin(provider, null, trigger);
        String owner;
        try {
            owner = locks.acquire(lock, leaseMs);
        } catch (RuntimeException lockFailure) {
            runs.finish(run, SyncRunService.Outcome.FAILED, SyncRunService.Counts.empty(), lockFailure);
            throw lockFailure;
        }
        if (owner == null) {
            runs.finish(run, SyncRunService.Outcome.LOCKED, SyncRunService.Counts.empty(), null);
            return new Result(null, true, false, run.runId(), SyncRunService.Outcome.LOCKED);
        }
        DistributedLeaseGuard guard;
        try {
            guard = DistributedLeaseGuard.start(locks, lock, owner, leaseMs, renewalMs, scheduler);
        } catch (RuntimeException heartbeatFailure) {
            runs.finish(run, SyncRunService.Outcome.FAILED, SyncRunService.Counts.empty(), heartbeatFailure);
            throw heartbeatFailure;
        }
        try (guard) {
            SearchSyncResult sync;
            try {
                sync = ingestion.apply(guard::isValid);
            } catch (RuntimeException failure) {
                runs.finish(run, guard.isLost() ? SyncRunService.Outcome.LEASE_LOST : SyncRunService.Outcome.FAILED,
                        SyncRunService.Counts.empty(), failure);
                throw failure;
            }
            boolean leaseLost = guard.isLost();
            SyncRunService.Outcome outcome = leaseLost ? SyncRunService.Outcome.LEASE_LOST : outcome(sync);
            SyncRunService.Reason reason = !leaseLost && sync != null && sync.budgetExhausted()
                    ? SyncRunService.Reason.BUDGET_EXHAUSTED : null;
            runs.finish(run, outcome, counts(sync), null, reason);
            return new Result(sync, false, leaseLost, run.runId(), outcome);
        }
    }

    static SyncRunService.Outcome outcome(SearchSyncResult sync) {
        if (sync == null) return SyncRunService.Outcome.FAILED;
        return switch (sync.outcome()) {
            case FULL_SUCCESS -> SyncRunService.Outcome.COMPLETED;
            case PARTIAL_FAILURE -> SyncRunService.Outcome.PARTIAL;
            case COMPLETE_FAILURE -> SyncRunService.Outcome.FAILED;
            case OVERLAP_REJECTED -> SyncRunService.Outcome.LOCKED;
        };
    }

    private static SyncRunService.Counts counts(SearchSyncResult sync) {
        if (sync == null) return SyncRunService.Counts.empty();
        return new SyncRunService.Counts(sync.inserted(), sync.updated(), sync.unchanged(), sync.rejected(),
                sync.failedItems(), sync.failedBatches(), 0, sync.lifecycleMatched(),
                sync.lifecycleModified(), sync.retries(), sync.attemptedBatches(), 0);
    }

    public record Result(SearchSyncResult sync, boolean locked, boolean leaseLost, String runId,
                         SyncRunService.Outcome outcome) { }
}
