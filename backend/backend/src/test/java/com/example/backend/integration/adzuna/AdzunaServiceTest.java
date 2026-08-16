package com.example.backend.integration.adzuna;

import com.example.backend.integration.aggregation.ImportedJobLifecycleService;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdzunaServiceTest {
    @Test
    void retriesOnlyTransientFailuresWithinConfiguredBound() {
        AdzunaClient client = mock(AdzunaClient.class); AdzunaJobStore store = mock(AdzunaJobStore.class);
        when(client.fetchPage(anyString(), anyInt())).thenThrow(new AdzunaProviderException("timeout", true, null))
                .thenThrow(new AdzunaProviderException("timeout", true, null)).thenReturn(response("one"));
        when(store.upsert(any(), any())).thenReturn(UpsertOutcome.INSERTED);
        AtomicInteger sleeps = new AtomicInteger();
        AdzunaService service = service(client, store, 3, "java", sleeps);
        AdzunaService.SyncResult result = service.sync();
        assertEquals(1, result.inserted()); assertEquals(2, sleeps.get()); verify(client, times(3)).fetchPage("java", 1); verify(store).upsert(any(), any());
    }
    @Test
    void doesNotRetryAuthenticationOrInvalidProviderRequest() {
        AdzunaClient client = mock(AdzunaClient.class); AdzunaJobStore store = mock(AdzunaJobStore.class);
        when(client.fetchPage(anyString(), anyInt())).thenThrow(new AdzunaProviderException("HTTP 401", false, null));
        AdzunaService.SyncResult result = service(client, store, 3, "java", new AtomicInteger()).sync();
        assertEquals(1, result.failedBatches()); verify(client, times(1)).fetchPage("java", 1); verifyNoInteractions(store);
    }
    @Test
    void keepsSuccessfulBatchesWhenAnotherKeywordFailsAndRejectsMalformedRecords() {
        AdzunaClient client = mock(AdzunaClient.class); AdzunaJobStore store = mock(AdzunaJobStore.class);
        ImportedJobLifecycleService lifecycle = mock(ImportedJobLifecycleService.class);
        when(client.fetchPage(eq("java"), eq(1))).thenThrow(new AdzunaProviderException("HTTP 503", false, null));
        when(client.fetchPage(eq("python"), eq(1))).thenReturn(new AdzunaResponse(List.of(valid("good"), new AdzunaResponse.AdzunaJob("", "bad", "x", null, null, null, null))));
        when(store.upsert(any(), any())).thenReturn(UpsertOutcome.INSERTED);
        AdzunaService.SyncResult result = service(client, store, 1, "java,python", new AtomicInteger(), lifecycle).sync();
        assertEquals(1, result.failedBatches()); assertEquals(1, result.inserted()); assertEquals(1, result.rejected()); verify(store, times(1)).upsert(any(), any());
        verifyNoInteractions(lifecycle);
    }
    @Test
    void skipsOverlappingRuns() {
        AdzunaClient client = mock(AdzunaClient.class); AdzunaJobStore store = mock(AdzunaJobStore.class); AtomicReference<AdzunaService.SyncResult> nested = new AtomicReference<>();
        AdzunaService service = service(client, store, 1, "java", new AtomicInteger());
        when(store.upsert(any(), any())).thenReturn(UpsertOutcome.INSERTED);
        when(client.fetchPage(anyString(), anyInt())).thenAnswer(invocation -> { nested.set(service.sync()); return response("one"); });
        assertFalse(service.sync().skipped()); assertTrue(nested.get().skipped()); verify(client, times(1)).fetchPage("java", 1);
    }
    @Test
    void onlyCompleteLeaseValidRunAdvancesLifecycle() {
        AdzunaClient client = mock(AdzunaClient.class);
        AdzunaJobStore store = mock(AdzunaJobStore.class);
        ImportedJobLifecycleService lifecycle = mock(ImportedJobLifecycleService.class);
        when(client.fetchPage(anyString(), anyInt())).thenReturn(response("one"));
        when(store.upsert(any(), any())).thenReturn(UpsertOutcome.INSERTED);
        AdzunaService service = service(client, store, 1, "java", new AtomicInteger(), lifecycle);

        assertEquals(AdzunaService.Outcome.FULL_SUCCESS, service.sync().outcome());

        verify(lifecycle).completeSuccessfulRun(eq("adzuna"), isNull(),
                eq(java.util.Set.of("adzuna:one")), any());
    }
    @Test
    void leaseLossAfterStoredItemDoesNotAdvanceLifecycle() {
        AdzunaClient client = mock(AdzunaClient.class);
        AdzunaJobStore store = mock(AdzunaJobStore.class);
        ImportedJobLifecycleService lifecycle = mock(ImportedJobLifecycleService.class);
        when(client.fetchPage(anyString(), anyInt())).thenReturn(response("one"));
        when(store.upsert(any(), any())).thenReturn(UpsertOutcome.INSERTED);
        AdzunaService service = service(client, store, 1, "java", new AtomicInteger(), lifecycle);
        AtomicInteger leaseChecks = new AtomicInteger();

        service.sync(() -> leaseChecks.incrementAndGet() < 4);

        verify(store).upsert(any(), any());
        verifyNoInteractions(lifecycle);
    }
    private static AdzunaService service(AdzunaClient client, AdzunaJobStore store, int attempts, String keywords, AtomicInteger sleeps) {
        return service(client, store, attempts, keywords, sleeps, mock(ImportedJobLifecycleService.class));
    }
    private static AdzunaService service(AdzunaClient client, AdzunaJobStore store, int attempts, String keywords,
                                         AtomicInteger sleeps, ImportedJobLifecycleService lifecycle) {
        AdzunaProperties p = properties(attempts, keywords); AdzunaCircuitBreaker circuit = new AdzunaCircuitBreaker(p);
        return new AdzunaService(new AdzunaJobSource(client), store, p, circuit, new AdzunaSyncMetrics(),
                lifecycle, java.time.Clock.systemUTC(),
                millis -> sleeps.incrementAndGet(), bound -> 0);
    }
    static AdzunaProperties properties(int attempts, String keywords) { return new AdzunaProperties("id-not-secret", "key-not-secret", 1, 1, attempts, 1, 2, 100, 1, 1, keywords); }
    private static AdzunaResponse response(String id) { return new AdzunaResponse(List.of(valid(id))); }
    private static AdzunaResponse.AdzunaJob valid(String id) { return new AdzunaResponse.AdzunaJob(id, "Engineer", "description", "https://example.test/" + id, null, null, null, null, null, null); }
}
