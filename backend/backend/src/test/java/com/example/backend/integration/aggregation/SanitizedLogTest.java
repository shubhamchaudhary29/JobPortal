package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.backend.integration.adzuna.AdzunaJobStore;
import com.example.backend.integration.jobs.JobSource;
import com.example.backend.integration.reliability.ProviderFailureException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class SanitizedLogTest {
    @Test
    void providerFailureLogsOnlyConfiguredProviderAndBoard(CapturedOutput output) {
        String sensitive = "https://user:password@provider.test/jobs?token=raw-secret";
        JobSource greenhouse = mock(JobSource.class);
        JobSource lever = mock(JobSource.class);
        when(greenhouse.sourceName()).thenReturn("greenhouse");
        when(greenhouse.fetchWithMetadata(any())).thenThrow(new ProviderFailureException("greenhouse",
                ProviderFailureException.Kind.SERVER_ERROR, true, null, new RuntimeException(sensitive)));
        EmployerRegistryProperties registry = new EmployerRegistryProperties(List.of(
                new EmployerRegistryProperties.Employer(sensitive, EmployerRegistryProperties.Source.GREENHOUSE,
                        "safe-board", true)));

        var result = new EmployerIngestionService(greenhouse, lever, mock(AdzunaJobStore.class), registry,
                mock(ImportedJobLifecycleService.class)).sync(EmployerRegistryProperties.Source.GREENHOUSE);

        assertEquals(1, result.failedEmployers());
        assertTrue(output.getOut().contains("event=employer_board_failed provider=greenhouse board=safe-board"));
        assertFalse(output.getAll().contains("raw-secret"));
        assertFalse(output.getAll().contains("password"));
        assertFalse(output.getAll().contains("provider.test"));
    }
}
