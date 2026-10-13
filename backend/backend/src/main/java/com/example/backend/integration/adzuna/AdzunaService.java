package com.example.backend.integration.adzuna;

import com.example.backend.integration.aggregation.ImportedJobLifecycleService;
import com.example.backend.integration.jobs.JobSource;
import com.example.backend.integration.search.MongoQueryRotation;
import com.example.backend.integration.search.ProviderRequestBudget;
import com.example.backend.integration.search.QueryRotation;
import com.example.backend.integration.search.RequestBudget;
import com.example.backend.integration.search.SearchProviderIngestionService;
import com.example.backend.integration.search.SearchSyncResult;
import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.LongUnaryOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Adzuna keyword ingestion; the loop, budget, persistence, and lifecycle rules live in the shared search engine. */
@Service
public class AdzunaService {
    private final SearchProviderIngestionService engine;
    private final AdzunaSearchProvider provider;
    @Autowired
    public AdzunaService(@Qualifier("adzunaJobSource") JobSource source, AdzunaJobStore jobs, AdzunaProperties properties,
                         AdzunaCircuitBreaker circuit, AdzunaSyncMetrics metrics,
                         ImportedJobLifecycleService lifecycle, ProviderRequestBudget budget,
                         MongoQueryRotation rotation) {
        this(source, jobs, properties, circuit, metrics, lifecycle, Clock.systemUTC(), Thread::sleep,
                bound -> ThreadLocalRandom.current().nextLong(Math.max(1, bound)), budget, rotation);
    }
    AdzunaService(JobSource source, AdzunaJobStore jobs, AdzunaProperties properties,
                  AdzunaCircuitBreaker circuit, AdzunaSyncMetrics metrics, ImportedJobLifecycleService lifecycle,
                  Clock clock, Sleeper sleeper, LongUnaryOperator jitter) {
        this(source, jobs, properties, circuit, metrics, lifecycle, clock, sleeper, jitter, RequestBudget.unlimited());
    }
    AdzunaService(JobSource source, AdzunaJobStore jobs, AdzunaProperties properties,
                  AdzunaCircuitBreaker circuit, AdzunaSyncMetrics metrics, ImportedJobLifecycleService lifecycle,
                  Clock clock, Sleeper sleeper, LongUnaryOperator jitter, RequestBudget budget) {
        this(source, jobs, properties, circuit, metrics, lifecycle, clock, sleeper, jitter, budget, null);
    }
    AdzunaService(JobSource source, AdzunaJobStore jobs, AdzunaProperties properties,
                  AdzunaCircuitBreaker circuit, AdzunaSyncMetrics metrics, ImportedJobLifecycleService lifecycle,
                  Clock clock, Sleeper sleeper, LongUnaryOperator jitter, RequestBudget budget, QueryRotation rotation) {
        this.engine = new SearchProviderIngestionService(jobs, lifecycle, budget, clock, rotation);
        this.provider = new AdzunaSearchProvider(source, properties, circuit, metrics, sleeper, jitter);
    }

    public SyncResult sync() { return sync(() -> true); }
    /** Runs the keyword plan on the shared search engine; see {@link SearchProviderIngestionService}. */
    public SyncResult sync(BooleanSupplier leaseValid) {
        return SyncResult.from(engine.sync(provider, leaseValid));
    }
    public enum Outcome { FULL_SUCCESS, PARTIAL_FAILURE, COMPLETE_FAILURE, OVERLAP_REJECTED }
    public record SyncResult(int inserted, int updated, int unchanged, int rejected, int failedBatches,
                             int failedItems, int retries, long lifecycleMatched,
                             long lifecycleModified, int attemptedBatches, Outcome outcome, boolean budgetExhausted) {
        public SyncResult(int inserted, int updated, int unchanged, int rejected, int failedBatches,
                          int failedItems, int retries, long lifecycleMatched,
                          long lifecycleModified, int attemptedBatches, Outcome outcome) {
            this(inserted, updated, unchanged, rejected, failedBatches, failedItems, retries, lifecycleMatched,
                    lifecycleModified, attemptedBatches, outcome, false);
        }
        public SyncResult(int inserted, int updated, int unchanged, int rejected, int failedBatches,
                          int failedItems, Outcome outcome) {
            this(inserted, updated, unchanged, rejected, failedBatches, failedItems, 0, 0, 0, 0, outcome);
        }
        public boolean skipped() { return outcome == Outcome.OVERLAP_REJECTED; }
        static SyncResult from(SearchSyncResult result) {
            return new SyncResult(result.inserted(), result.updated(), result.unchanged(), result.rejected(),
                    result.failedBatches(), result.failedItems(), result.retries(), result.lifecycleMatched(),
                    result.lifecycleModified(), result.attemptedBatches(), Outcome.valueOf(result.outcome().name()),
                    result.budgetExhausted());
        }
        SearchSyncResult toSearchResult() {
            return new SearchSyncResult(inserted, updated, unchanged, rejected, failedBatches, failedItems, retries,
                    lifecycleMatched, lifecycleModified, attemptedBatches,
                    SearchSyncResult.Outcome.valueOf(outcome.name()), budgetExhausted);
        }
    }
    @FunctionalInterface interface Sleeper { void sleep(long millis) throws InterruptedException; }
}
