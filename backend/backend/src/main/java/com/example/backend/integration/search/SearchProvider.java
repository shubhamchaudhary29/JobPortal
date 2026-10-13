package com.example.backend.integration.search;

import com.example.backend.integration.jobs.ExternalJob;
import java.util.List;

/**
 * A keyword/location search API driven by {@link SearchProviderIngestionService}. Implementations own their HTTP
 * client, retry classification, and circuit; the engine owns the query loop, request budget, persistence,
 * lifecycle, and outcome rules.
 */
public interface SearchProvider {
    /** Catalog name; also the imported-job source and sync-history provider. */
    String name();
    SearchQueryPlan plan();
    /** One page. Throws {@link SearchProviderException} for provider failures. */
    List<ExternalJob> fetch(SearchQuery query);
    int maxAttempts();
    long backoffMs(int attempt);
    /** Upper bound for a provider-requested {@code Retry-After} wait. */
    default long maxBackoffMs() { return Long.MAX_VALUE; }
    void sleep(long millis) throws InterruptedException;
    boolean allowRequest();
    void recordSuccess();
    void recordFailure();
    /** Maximum provider requests per UTC calendar month, counted across all instances. */
    int monthlyRequestBudget();
    /**
     * First-attempt requests one run may plan. A larger plan is rotated across runs and lifecycle then advances
     * once per complete rotation cycle instead of per run.
     */
    default int requestsPerRun() { return Integer.MAX_VALUE; }
    /** Minimum spacing between request attempts in one run, for per-minute provider limits. */
    default long minRequestIntervalMs() { return 0; }
    default void completed(SearchSyncResult result, long elapsedMs) { }
    default void skipped() { }
}
