package com.example.backend.integration.adzuna;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestTemplate;

class AdzunaQueryPlanTest {
    @Test
    void requestsPerRunSpreadsTheMonthlyBudgetOverScheduledRuns() {
        assertAll(
                () -> assertEquals(33, AdzunaProperties.automaticRequestsPerRun(2_000, 43_200_000L, 2)),
                () -> assertEquals(16, AdzunaProperties.automaticRequestsPerRun(2_000, 21_600_000L, 2)),
                () -> assertEquals(2, AdzunaProperties.automaticRequestsPerRun(50, 43_200_000L, 2)),
                () -> assertEquals(2_000, AdzunaProperties.automaticRequestsPerRun(2_000, 30L * 86_400_000L, 2)));
        for (long delay : new long[] {3_600_000L, 21_600_000L, 43_200_000L, 86_400_000L}) {
            long runs = (30L * 86_400_000L + delay - 1) / delay;
            assertTrue(AdzunaProperties.automaticRequestsPerRun(2_000, delay, 2) * runs <= 2_000, "delay " + delay);
        }
    }

    @Test
    void defaultsAddTenIndianCitiesAndValidateBounds() {
        AdzunaProperties defaults = new AdzunaProperties("id", "key", 1, 1, 1, 1, 3, 100, 2, 20, "java", 2_000,
                "Bengaluru, Delhi ,,Delhi,Pune", 0, 43_200_000L);
        assertArrayEquals(new String[] {"Bengaluru", "Delhi", "Pune"}, defaults.cities());
        assertEquals(0, AdzunaServiceTest.properties(1, "java").cities().length);
        assertThrows(IllegalArgumentException.class, () -> new AdzunaProperties("id", "key", 1, 1, 1, 1, 3, 100, 2, 20,
                "java", 2_000, "", -1, 43_200_000L));
        assertEquals(2_500, new AdzunaProperties("id", "key", 1, 1, 1, 1, 3, 100, 2, 20, "java", 2_000, "", 0,
                43_200_000L, 2_500).minRequestIntervalMs());
        assertEquals(0, AdzunaServiceTest.properties(1, "java").minRequestIntervalMs());
        assertThrows(IllegalArgumentException.class, () -> new AdzunaProperties("id", "key", 1, 1, 1, 1, 3, 100, 2, 20,
                "java", 2_000, "", 0, 43_200_000L, 60_001));
    }

    @Test
    void cityQueriesSendAnEncodedWhereAndCountryWideQueriesDoNot() {
        RestTemplate http = mock(RestTemplate.class);
        when(http.getForObject(any(URI.class), eq(AdzunaResponse.class))).thenReturn(new AdzunaResponse(java.util.List.of()));
        RestTemplateAdzunaClient client = new RestTemplateAdzunaClient(http, AdzunaServiceTest.properties(1, "java"));

        client.fetchPage("java developer", "Navi Mumbai", 2);
        client.fetchPage("java developer", 1);

        ArgumentCaptor<URI> uris = ArgumentCaptor.forClass(URI.class);
        verify(http, times(2)).getForObject(uris.capture(), eq(AdzunaResponse.class));
        String city = uris.getAllValues().get(0).toString();
        String countryWide = uris.getAllValues().get(1).toString();
        assertAll(
                () -> assertTrue(city.contains("/search/2?"), city),
                () -> assertTrue(city.contains("what=java%20developer"), city),
                () -> assertTrue(city.contains("where=Navi%20Mumbai"), city),
                () -> assertFalse(countryWide.contains("where="), countryWide),
                () -> assertTrue(countryWide.contains("/search/1?"), countryWide));
    }
}
