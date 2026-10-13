package com.example.backend.integration.adzuna;

import com.example.backend.integration.jobs.ExternalJob;
import com.example.backend.integration.jobs.JobFetchRequest;
import com.example.backend.integration.jobs.JobSource;
import com.example.backend.integration.search.SearchProvider;
import com.example.backend.integration.search.SearchQuery;
import com.example.backend.integration.search.SearchQueryPlan;
import com.example.backend.integration.search.SearchSyncResult;
import java.util.List;
import java.util.function.LongUnaryOperator;

/** Adzuna on the shared search engine, keeping its own client, retry policy, circuit, and counters. */
final class AdzunaSearchProvider implements SearchProvider {
    private final JobSource source;
    private final AdzunaProperties properties;
    private final AdzunaCircuitBreaker circuit;
    private final AdzunaSyncMetrics metrics;
    private final AdzunaService.Sleeper sleeper;
    private final LongUnaryOperator jitter;

    AdzunaSearchProvider(JobSource source, AdzunaProperties properties, AdzunaCircuitBreaker circuit,
                         AdzunaSyncMetrics metrics, AdzunaService.Sleeper sleeper, LongUnaryOperator jitter) {
        this.source = source;
        this.properties = properties;
        this.circuit = circuit;
        this.metrics = metrics;
        this.sleeper = sleeper;
        this.jitter = jitter;
    }

    @Override public String name() { return source.sourceName(); }
    @Override public SearchQueryPlan plan() {
        return SearchQueryPlan.keywords(properties.keywords(), null, properties.pagesPerKeyword());
    }
    @Override public List<ExternalJob> fetch(SearchQuery query) {
        return source.fetch(new JobFetchRequest(query.keyword(), query.page()));
    }
    @Override public int maxAttempts() { return properties.maxAttempts(); }
    @Override public long backoffMs(int attempt) {
        long base = properties.initialBackoffMs() * (1L << (attempt - 1));
        return base + jitter.applyAsLong(Math.max(1, base / 2));
    }
    @Override public void sleep(long millis) throws InterruptedException { sleeper.sleep(millis); }
    @Override public boolean allowRequest() { return circuit.allowRequest(); }
    @Override public void recordSuccess() { circuit.recordSuccess(); }
    @Override public void recordFailure() { circuit.recordFailure(); }
    @Override public int monthlyRequestBudget() { return properties.monthlyRequestBudget(); }
    @Override public void skipped() { metrics.record(AdzunaSyncMetrics.Outcome.REJECTED, 0); }
    @Override public void completed(SearchSyncResult result, long elapsedMs) {
        metrics.record(switch (result.outcome()) {
            case FULL_SUCCESS -> AdzunaSyncMetrics.Outcome.FULL_SUCCESS;
            case PARTIAL_FAILURE -> AdzunaSyncMetrics.Outcome.PARTIAL_FAILURE;
            default -> AdzunaSyncMetrics.Outcome.COMPLETE_FAILURE;
        }, elapsedMs);
    }
}
