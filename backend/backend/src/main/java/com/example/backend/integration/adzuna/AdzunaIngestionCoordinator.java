package com.example.backend.integration.adzuna;

import com.example.backend.integration.aggregation.DistributedLeaseLock;
import com.example.backend.integration.aggregation.SyncRunService;
import com.example.backend.integration.search.SearchCoordinatorSource;
import com.example.backend.integration.search.SearchIngestionCoordinator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.concurrent.ScheduledExecutorService;

/**
 * The same Mongo lease protects scheduled and operator Adzuna execution across instances. Lease, history, and
 * scheduling come from the shared {@link SearchIngestionCoordinator}; this class keeps the Adzuna result types
 * used by the legacy recruiter trigger.
 */
@Service
public class AdzunaIngestionCoordinator implements SearchCoordinatorSource {
    private final SearchIngestionCoordinator search;
    public AdzunaIngestionCoordinator(AdzunaService ingestion, DistributedLeaseLock locks,
            @Value("${job-aggregation.lock-lease-ms:300000}") long leaseMs,
            @Value("${job-aggregation.lock-renewal-ms:60000}") long renewalMs,
            @Qualifier("ingestionLeaseScheduler") ScheduledExecutorService scheduler,
            SyncRunService runs) {
        if (leaseMs < 1_000 || renewalMs < 250 || renewalMs >= leaseMs) {
            throw new IllegalArgumentException("invalid Adzuna lease/renewal configuration");
        }
        this.search = new SearchIngestionCoordinator("adzuna", leaseValid -> {
            AdzunaService.SyncResult result = ingestion.sync(leaseValid);
            return result == null ? null : result.toSearchResult();
        }, locks, leaseMs, renewalMs, scheduler, runs);
    }
    @Override
    public SearchIngestionCoordinator searchCoordinator() { return search; }
    public Result run() { return run(SyncRunService.Trigger.MANUAL); }
    public Result run(SyncRunService.Trigger trigger) {
        SearchIngestionCoordinator.Result result = search.run(trigger);
        AdzunaService.SyncResult sync = result.sync() == null ? null : AdzunaService.SyncResult.from(result.sync());
        return new Result(sync, result.locked(), result.leaseLost(), result.runId(), result.outcome());
    }
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
