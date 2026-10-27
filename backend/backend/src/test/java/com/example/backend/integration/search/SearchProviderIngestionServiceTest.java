package com.example.backend.integration.search;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.example.backend.integration.adzuna.AdzunaJobStore;
import com.example.backend.integration.adzuna.UpsertOutcome;
import com.example.backend.integration.aggregation.ExternalJobNormalizer;
import com.example.backend.integration.aggregation.ImportedJobLifecycleService;
import com.example.backend.integration.jobs.ExternalJob;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SearchProviderIngestionServiceTest {
    private final AdzunaJobStore store = mock(AdzunaJobStore.class);
    private final ImportedJobLifecycleService lifecycle = mock(ImportedJobLifecycleService.class);

    @Test
    void exhaustedBudgetStopsTheRunAsPartialWithoutAdvancingLifecycle() {
        when(store.upsert(any(), any())).thenReturn(UpsertOutcome.INSERTED);
        FakeProvider provider = new FakeProvider(2, List.of("java", "python"), 2);
        CountingBudget budget = new CountingBudget(2);

        SearchSyncResult result = engine(budget).sync(provider, () -> true);

        assertAll(
                () -> assertTrue(result.budgetExhausted()),
                () -> assertEquals(SearchSyncResult.Outcome.PARTIAL_FAILURE, result.outcome()),
                () -> assertEquals(2, result.inserted()),
                () -> assertEquals(3, result.attemptedBatches()),
                () -> assertEquals(List.of("java:1", "java:2"), provider.requests),
                () -> assertEquals(3, budget.calls.get()));
        verifyNoInteractions(lifecycle);
    }

    @Test
    void everyRetryAttemptConsumesBudgetAndABudgetStopDuringRetriesIsPartial() {
        FakeProvider provider = new FakeProvider(3, List.of("java"), 1);
        provider.failuresBeforeSuccess = 5;
        CountingBudget budget = new CountingBudget(2);

        SearchSyncResult result = engine(budget).sync(provider, () -> true);

        assertAll(
                () -> assertTrue(result.budgetExhausted()),
                () -> assertEquals(SearchSyncResult.Outcome.PARTIAL_FAILURE, result.outcome()),
                () -> assertEquals(2, provider.requests.size()),
                // Like the original Adzuna loop, a retry counts when it is scheduled; the budget then blocks it.
                () -> assertEquals(2, result.retries()),
                () -> assertEquals(0, result.failedBatches()));
        verifyNoInteractions(lifecycle);
    }

    @Test
    void completeRunWithinBudgetAdvancesLifecycleForTheProvider() {
        when(store.upsert(any(), any())).thenReturn(UpsertOutcome.INSERTED);
        FakeProvider provider = new FakeProvider(1, List.of("java"), 2);

        SearchSyncResult result = engine(new CountingBudget(10)).sync(provider, () -> true);

        assertAll(
                () -> assertFalse(result.budgetExhausted()),
                () -> assertEquals(SearchSyncResult.Outcome.FULL_SUCCESS, result.outcome()),
                () -> assertEquals(2, result.inserted()),
                () -> assertSame(result, provider.completed));
        verify(lifecycle).completeSuccessfulRun(eq("adzuna"), eq(null),
                eq(java.util.Set.of("adzuna:java-1", "adzuna:java-2")), any());
    }

    @Test
    void theProvidersLocationHintReachesTheStore() {
        when(store.upsert(any(), any())).thenReturn(UpsertOutcome.INSERTED);
        FakeProvider provider = new FakeProvider(1, List.of("java"), 1);
        provider.hint = new com.example.backend.job.domain.LocationHint(List.of("India", "Karnataka", "Bangalore"), 12.97, 77.6);

        engine(new CountingBudget(10)).sync(provider, () -> true);

        org.mockito.ArgumentCaptor<com.example.backend.job.infrastructure.JobDocument> stored =
                org.mockito.ArgumentCaptor.forClass(com.example.backend.job.infrastructure.JobDocument.class);
        verify(store).upsert(stored.capture(), any());
        assertEquals(provider.hint, stored.getValue().getLocationHint());
    }

    @Test
    void zeroBudgetMakesNoRequest() {
        FakeProvider provider = new FakeProvider(1, List.of("java"), 1);
        SearchSyncResult result = engine(new CountingBudget(0)).sync(provider, () -> true);
        assertTrue(result.budgetExhausted());
        assertTrue(provider.requests.isEmpty());
        verifyNoInteractions(store, lifecycle);
    }

    @Test
    void consecutiveRequestsAreSpacedByTheProvidersMinimumInterval() {
        when(store.upsert(any(), any())).thenReturn(UpsertOutcome.INSERTED);
        FakeProvider provider = new FakeProvider(1, List.of("java", "python"), 2);
        provider.intervalMs = 2_500;

        engine(new CountingBudget(10)).sync(provider, () -> true);

        assertEquals(4, provider.requests.size());
        assertEquals(3, provider.sleeps.size(), "no wait before the first request");
        assertTrue(provider.sleeps.stream().allMatch(wait -> wait > 2_000 && wait <= 2_500), provider.sleeps.toString());
        assertTrue(60_000 / provider.intervalMs <= 25, "Adzuna allows 25 requests per minute");
    }

    private SearchProviderIngestionService engine(RequestBudget budget) {
        return new SearchProviderIngestionService(store, lifecycle, budget, Clock.systemUTC());
    }

    private static final class CountingBudget implements RequestBudget {
        private final int limit;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger used = new AtomicInteger();
        CountingBudget(int limit) { this.limit = limit; }
        @Override public boolean tryConsume(String provider, int monthlyLimit) {
            calls.incrementAndGet();
            if (used.get() >= limit) return false;
            used.incrementAndGet();
            return true;
        }
    }

    private static final class FakeProvider implements SearchProvider {
        private final int attempts;
        private final List<String> keywords;
        private final int pages;
        private final List<String> requests = new ArrayList<>();
        private int failuresBeforeSuccess;
        private long intervalMs;
        private final List<Long> sleeps = new ArrayList<>();
        private SearchSyncResult completed;
        private com.example.backend.job.domain.LocationHint hint;
        FakeProvider(int attempts, List<String> keywords, int pages) {
            this.attempts = attempts;
            this.keywords = keywords;
            this.pages = pages;
        }
        @Override public String name() { return "adzuna"; }
        @Override public SearchQueryPlan plan() {
            return SearchQueryPlan.keywords(keywords.toArray(String[]::new), null, pages);
        }
        @Override public List<ExternalJob> fetch(SearchQuery query) {
            requests.add(query.keyword() + ":" + query.page());
            if (failuresBeforeSuccess-- > 0) throw new SearchProviderException("HTTP 503", true, null);
            return List.of(ExternalJobNormalizer.normalize(query.keyword() + "-" + query.page(), "Engineer " + query.page(),
                    null, "Acme", "Pune", null, "https://jobs.example.test/" + query.keyword() + "/" + query.page(), null,
                    null, null, hint));
        }
        @Override public int maxAttempts() { return attempts; }
        @Override public long backoffMs(int attempt) { return 0; }
        @Override public void sleep(long millis) { sleeps.add(millis); }
        @Override public long minRequestIntervalMs() { return intervalMs; }
        @Override public boolean allowRequest() { return true; }
        @Override public void recordSuccess() { }
        @Override public void recordFailure() { }
        @Override public int monthlyRequestBudget() { return 100; }
        @Override public void completed(SearchSyncResult result, long elapsedMs) { completed = result; }
    }
}
