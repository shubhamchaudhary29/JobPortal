package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.backend.integration.adzuna.AdzunaJobStore;
import com.example.backend.integration.adzuna.UpsertOutcome;
import com.example.backend.integration.jobs.ExternalJob;
import com.example.backend.integration.jobs.JobSource;
import com.example.backend.integration.reliability.ProviderFailureException;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmployerIngestionServiceTest {
    @Test
    void disabledAndFailingEmployersDoNotBlockAnotherEmployer() {
        JobSource greenhouse = mock(JobSource.class);
        JobSource lever = mock(JobSource.class);
        AdzunaJobStore store = mock(AdzunaJobStore.class);
        ImportedJobLifecycleService lifecycle = mock(ImportedJobLifecycleService.class);
        when(greenhouse.sourceName()).thenReturn("greenhouse");
        when(greenhouse.fetch(any())).thenThrow(new RuntimeException("rate limit"));
        when(lever.sourceName()).thenReturn("lever");
        when(lever.fetch(any())).thenReturn(List.of(new ExternalJob("id", "Engineer", null, "Acme", null,
                "Full Time", null, null, "https://example.test/job", null, null, "fingerprint")));
        when(store.upsert(any(), any(), any())).thenReturn(UpsertOutcome.INSERTED);
        EmployerRegistryProperties registry = new EmployerRegistryProperties(List.of(
                new EmployerRegistryProperties.Employer("Broken", EmployerRegistryProperties.Source.GREENHOUSE, "broken", true),
                new EmployerRegistryProperties.Employer("Healthy", EmployerRegistryProperties.Source.LEVER, "healthy", true),
                new EmployerRegistryProperties.Employer("Disabled", EmployerRegistryProperties.Source.LEVER, "disabled", false)));

        var result = new EmployerIngestionService(greenhouse, lever, store, registry, lifecycle).sync();

        assertEquals(1, result.failedEmployers());
        assertEquals(1, result.inserted());
        verify(store).upsert(any(), any(), eq("healthy"));
        verify(lifecycle).completeSuccessfulRun(eq("lever"), eq("healthy"),
                eq(java.util.Set.of("lever:healthy:id")), any());
        verify(lifecycle, never()).completeSuccessfulRun(eq("greenhouse"), any(), any(), any());
        verify(lever, never()).fetch(argThat(request -> "disabled".equals(request.boardId())));
    }

    @Test
    void invalidRegistryIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new EmployerRegistryProperties(List.of(
                new EmployerRegistryProperties.Employer("Acme", EmployerRegistryProperties.Source.LEVER, "not/a-board", true))));
    }

    @Test
    void emptySuccessfulBoardAdvancesLifecycleButMalformedBoardDoesNot() {
        JobSource greenhouse = mock(JobSource.class);
        JobSource lever = mock(JobSource.class);
        AdzunaJobStore store = mock(AdzunaJobStore.class);
        ImportedJobLifecycleService lifecycle = mock(ImportedJobLifecycleService.class);
        when(greenhouse.sourceName()).thenReturn("greenhouse");
        when(lever.sourceName()).thenReturn("lever");
        when(greenhouse.fetch(any())).thenReturn(List.of());
        when(lever.fetch(any())).thenReturn(List.of(new ExternalJob(null, "bad", null, "Acme", null,
                null, null, null, "https://example.test/bad", null, null, "fingerprint")));
        EmployerRegistryProperties registry = new EmployerRegistryProperties(List.of(
                new EmployerRegistryProperties.Employer("Empty", EmployerRegistryProperties.Source.GREENHOUSE, "empty", true),
                new EmployerRegistryProperties.Employer("Malformed", EmployerRegistryProperties.Source.LEVER, "malformed", true)));

        var result = new EmployerIngestionService(greenhouse, lever, store, registry, lifecycle).sync();

        assertEquals(1, result.rejected());
        verify(lifecycle).completeSuccessfulRun(eq("greenhouse"), eq("empty"), eq(java.util.Set.of()), any());
        verify(lifecycle, never()).completeSuccessfulRun(eq("lever"), eq("malformed"), any(), any());
    }

    @Test
    void leaseLossAfterWritesPreventsMissingDetection() {
        JobSource greenhouse = mock(JobSource.class);
        JobSource lever = mock(JobSource.class);
        AdzunaJobStore store = mock(AdzunaJobStore.class);
        ImportedJobLifecycleService lifecycle = mock(ImportedJobLifecycleService.class);
        when(greenhouse.sourceName()).thenReturn("greenhouse");
        when(greenhouse.fetch(any())).thenReturn(List.of(new ExternalJob("one", "Engineer", null, "Acme", null,
                null, null, null, "https://example.test/one", null, null, "fingerprint")));
        when(store.upsert(any(), any(), any())).thenReturn(UpsertOutcome.INSERTED);
        EmployerRegistryProperties registry = new EmployerRegistryProperties(List.of(
                new EmployerRegistryProperties.Employer("Board", EmployerRegistryProperties.Source.GREENHOUSE, "board", true)));
        java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();

        new EmployerIngestionService(greenhouse, lever, store, registry, lifecycle)
                .sync(EmployerRegistryProperties.Source.GREENHOUSE, () -> checks.incrementAndGet() < 3);

        verify(store).upsert(any(), any(), eq("board"));
        verifyNoInteractions(lifecycle);
    }

    @Test
    void employerSpecificSyncFetchesOnlyTheEnabledSelectedBoard() {
        JobSource greenhouse = mock(JobSource.class);
        JobSource lever = mock(JobSource.class);
        AdzunaJobStore store = mock(AdzunaJobStore.class);
        ImportedJobLifecycleService lifecycle = mock(ImportedJobLifecycleService.class);
        when(greenhouse.sourceName()).thenReturn("greenhouse");
        when(greenhouse.fetch(any())).thenReturn(List.of());
        EmployerRegistryProperties registry = new EmployerRegistryProperties(List.of(
                new EmployerRegistryProperties.Employer("One", EmployerRegistryProperties.Source.GREENHOUSE, "one", true),
                new EmployerRegistryProperties.Employer("Two", EmployerRegistryProperties.Source.GREENHOUSE, "two", true),
                new EmployerRegistryProperties.Employer("Disabled", EmployerRegistryProperties.Source.GREENHOUSE, "disabled", false)));
        EmployerIngestionService service = new EmployerIngestionService(
                greenhouse, lever, store, registry, lifecycle);

        EmployerIngestionService.Result result = service.sync(
                EmployerRegistryProperties.Source.GREENHOUSE, "TWO", () -> true);

        assertEquals(1, result.attemptedEmployers());
        verify(greenhouse).fetch(argThat(request -> "two".equals(request.boardId())));
        verify(greenhouse, never()).fetch(argThat(request -> "one".equals(request.boardId())));
        assertThrows(com.example.backend.shared.error.BadRequestException.class,
                () -> service.sync(EmployerRegistryProperties.Source.GREENHOUSE, "missing", () -> true));
        assertThrows(com.example.backend.shared.error.BadRequestException.class,
                () -> service.sync(EmployerRegistryProperties.Source.GREENHOUSE, "disabled", () -> true));
    }

    @Test
    void providerRetryMetadataIsIncludedInTheIngestionOutcome() {
        JobSource greenhouse = mock(JobSource.class);
        JobSource lever = mock(JobSource.class);
        AdzunaJobStore store = mock(AdzunaJobStore.class);
        ImportedJobLifecycleService lifecycle = mock(ImportedJobLifecycleService.class);
        when(greenhouse.sourceName()).thenReturn("greenhouse");
        when(greenhouse.fetchWithMetadata(any())).thenReturn(new JobSource.FetchResult(List.of(), 2));
        EmployerRegistryProperties registry = new EmployerRegistryProperties(List.of(
                new EmployerRegistryProperties.Employer("Board", EmployerRegistryProperties.Source.GREENHOUSE,
                        "board", true)));

        EmployerIngestionService.Result result = new EmployerIngestionService(
                greenhouse, lever, store, registry, lifecycle).sync(EmployerRegistryProperties.Source.GREENHOUSE);

        assertEquals(2, result.retries());
        assertEquals(1, result.attemptedEmployers());
        verify(lifecycle).completeSuccessfulRun(eq("greenhouse"), eq("board"), eq(java.util.Set.of()), any());
    }

    @Test
    void isolatedMalformedProviderItemMakesRunPartialAndProtectsLifecycle() {
        JobSource greenhouse = mock(JobSource.class);
        JobSource lever = mock(JobSource.class);
        AdzunaJobStore store = mock(AdzunaJobStore.class);
        ImportedJobLifecycleService lifecycle = mock(ImportedJobLifecycleService.class);
        when(greenhouse.sourceName()).thenReturn("greenhouse");
        ExternalJob valid = new ExternalJob("one", "Engineer", null, "Acme", null, null,
                null, null, "https://jobs.test/one", null, null, "fingerprint");
        when(greenhouse.fetchWithMetadata(any())).thenReturn(new JobSource.FetchResult(List.of(valid), 0, 1));
        when(store.upsert(any(), any(), any())).thenReturn(UpsertOutcome.INSERTED);
        EmployerRegistryProperties registry = new EmployerRegistryProperties(List.of(
                new EmployerRegistryProperties.Employer("Board", EmployerRegistryProperties.Source.GREENHOUSE,
                        "board", true)));

        var result = new EmployerIngestionService(greenhouse, lever, store, registry, lifecycle)
                .sync(EmployerRegistryProperties.Source.GREENHOUSE);

        assertEquals(1, result.inserted());
        assertEquals(1, result.rejected());
        verifyNoInteractions(lifecycle);
    }

    @Test
    void malformedProviderResponseFailsEmployerWithoutLifecycleProgressOrRetryFallback() {
        JobSource greenhouse = mock(JobSource.class);
        JobSource lever = mock(JobSource.class);
        AdzunaJobStore store = mock(AdzunaJobStore.class);
        ImportedJobLifecycleService lifecycle = mock(ImportedJobLifecycleService.class);
        when(greenhouse.sourceName()).thenReturn("greenhouse");
        when(greenhouse.fetchWithMetadata(any(), any())).thenThrow(new ProviderFailureException(
                "greenhouse", ProviderFailureException.Kind.MALFORMED_RESPONSE, false, null, null));
        EmployerIngestionService service = new EmployerIngestionService(greenhouse, lever, store,
                new EmployerRegistryProperties(List.of(new EmployerRegistryProperties.Employer(
                        "Board", EmployerRegistryProperties.Source.GREENHOUSE, "board", true))), lifecycle);

        EmployerIngestionService.Result result = service.sync(
                EmployerRegistryProperties.Source.GREENHOUSE, () -> true);

        assertAll(
                () -> assertEquals(1, result.failedEmployers()),
                () -> assertEquals(0, result.retries()));
        verify(greenhouse, times(1)).fetchWithMetadata(any(), any());
        verifyNoInteractions(store, lifecycle);
    }

    @Test
    void leaseLossAfterFirstItemPreventsEveryLaterWriteAndLifecycleProgress() {
        JobSource greenhouse = mock(JobSource.class);
        JobSource lever = mock(JobSource.class);
        AdzunaJobStore store = mock(AdzunaJobStore.class);
        ImportedJobLifecycleService lifecycle = mock(ImportedJobLifecycleService.class);
        when(greenhouse.sourceName()).thenReturn("greenhouse");
        when(greenhouse.fetch(any())).thenReturn(List.of(
                new ExternalJob("one", "One", null, "Acme", null, null, null, null,
                        "https://example.test/one", null, null, "one"),
                new ExternalJob("two", "Two", null, "Acme", null, null, null, null,
                        "https://example.test/two", null, null, "two")));
        java.util.concurrent.atomic.AtomicBoolean valid = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(store.upsert(any(), any(), any())).thenAnswer(invocation -> {
            valid.set(false);
            return UpsertOutcome.INSERTED;
        });
        EmployerIngestionService service = new EmployerIngestionService(greenhouse, lever, store,
                new EmployerRegistryProperties(List.of(new EmployerRegistryProperties.Employer(
                        "Board", EmployerRegistryProperties.Source.GREENHOUSE, "board", true))), lifecycle);

        EmployerIngestionService.Result result = service.sync(
                EmployerRegistryProperties.Source.GREENHOUSE, valid::get);

        assertEquals(1, result.inserted());
        verify(store, times(1)).upsert(any(), any(), any());
        verifyNoInteractions(lifecycle);
    }
}
