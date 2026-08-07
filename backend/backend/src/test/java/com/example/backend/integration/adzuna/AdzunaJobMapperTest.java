package com.example.backend.integration.adzuna;

import com.example.backend.job.JobMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class AdzunaJobMapperTest {
    @Test
    void providerDataMapsIntoAnInternalDocumentWithoutLeakingProviderIdentifiers() {
        var provider = new AdzunaResponse.AdzunaJob("provider-1", "Engineer", "<p>Build &amp; test</p>",
                "https://example.test/job", null, new AdzunaResponse.Company("Example"), null);
        var document = AdzunaJobMapper.toDocument(provider);

        assertEquals("provider-1", document.getExternalId());
        assertEquals("Build & test", document.getDescription());
        assertEquals("India", document.getLocation());
        assertNull(document.getRecruiterId());
        assertFalse(Arrays.stream(JobMapper.toResponse(document).getClass().getRecordComponents())
                .anyMatch(component -> component.getName().equals("externalId")));
    }
}
