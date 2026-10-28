package com.example.backend.integration.adzuna;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.backend.integration.jobs.ExternalJob;
import com.example.backend.integration.jobs.JobFetchRequest;
import com.example.backend.job.domain.LocationHint;
import java.util.List;
import org.junit.jupiter.api.Test;

/** GEO-10A: the live Adzuna path carries {@code location.area[]} and the point to the resolver. */
class AdzunaLocationHintTest {
    @Test
    void areaAndPointBecomeTheLocationHint() {
        AdzunaClient client = mock(AdzunaClient.class);
        when(client.fetchPage(anyString(), anyInt())).thenReturn(new AdzunaResponse(List.of(new AdzunaResponse.AdzunaJob("a1",
                "Engineer", "desc", "https://www.adzuna.in/land/ad/1", null, null, null, null,
                new AdzunaResponse.Company("Acme"), new AdzunaResponse.Location("Whitefield, Bangalore",
                        List.of("India", "Karnataka", "Bangalore", "Whitefield")), 12.97, 77.75))));

        ExternalJob job = new AdzunaJobSource(client).fetch(new JobFetchRequest("java", 1)).get(0);

        assertEquals(new LocationHint(List.of("India", "Karnataka", "Bangalore", "Whitefield"), 12.97, 77.75), job.locationHint());
    }

    @Test
    void missingLocationGivesAnEmptyAreaAndNoPoint() {
        AdzunaClient client = mock(AdzunaClient.class);
        when(client.fetchPage(anyString(), anyInt())).thenReturn(new AdzunaResponse(List.of(new AdzunaResponse.AdzunaJob("a2",
                "Engineer", "desc", "https://www.adzuna.in/land/ad/2", null, null, null))));

        LocationHint hint = new AdzunaJobSource(client).fetch(new JobFetchRequest("java", 1)).get(0).locationHint();

        assertEquals(new LocationHint(List.of(), null, null), hint);
    }
}
