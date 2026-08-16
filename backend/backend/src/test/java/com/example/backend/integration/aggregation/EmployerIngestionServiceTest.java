package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.backend.integration.adzuna.AdzunaJobStore;
import com.example.backend.integration.adzuna.UpsertOutcome;
import com.example.backend.integration.jobs.ExternalJob;
import com.example.backend.integration.jobs.JobSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmployerIngestionServiceTest {
    @Test
    void disabledAndFailingEmployersDoNotBlockAnotherEmployer() {
        JobSource greenhouse = mock(JobSource.class);
        JobSource lever = mock(JobSource.class);
        AdzunaJobStore store = mock(AdzunaJobStore.class);
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

        var result = new EmployerIngestionService(greenhouse, lever, store, registry).sync();

        assertEquals(1, result.failedEmployers());
        assertEquals(1, result.inserted());
        verify(store).upsert(any(), any(), eq("healthy"));
        verify(lever, never()).fetch(argThat(request -> "disabled".equals(request.boardId())));
    }

    @Test
    void invalidRegistryIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new EmployerRegistryProperties(List.of(
                new EmployerRegistryProperties.Employer("Acme", EmployerRegistryProperties.Source.LEVER, "not/a-board", true))));
    }
}
