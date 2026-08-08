package com.example.backend.integration.adzuna;

import com.example.backend.job.JobMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class AdzunaJobMapperTest {
    @Test
    void providerDataMapsIntoAnInternalDocumentWithoutLeakingProviderIdentifiers() {
        var provider = new AdzunaResponse.AdzunaJob("provider-1", "Engineer", "<p>Build &amp; test</p>",
                "https://example.test/job", null, new AdzunaResponse.Company("Example"), null);
        var document = AdzunaJobMapper.toDocument(provider, LocalDateTime.of(2026, 1, 1, 0, 0)).orElseThrow();

        assertEquals("provider-1", document.getExternalId());
        assertEquals("Build & test", document.getDescription());
        assertEquals("India", document.getLocation());
        assertNull(document.getRecruiterId());
        assertFalse(Arrays.stream(JobMapper.toResponse(document).getClass().getRecordComponents())
                .anyMatch(component -> component.getName().equals("externalId")));
    }

    @Test
    void malformedProviderDataIsRejectedBeforeItReachesMongo() {
        assertTrue(AdzunaJobMapper.toDocument(new AdzunaResponse.AdzunaJob("", "Engineer", "x", null, null, null, null), LocalDateTime.now()).isEmpty());
    }
}
