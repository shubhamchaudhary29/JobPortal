package com.example.backend.integration.reliability;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.example.backend.integration.adzuna.AdzunaJobStore;
import com.example.backend.integration.aggregation.ImportedJobLifecycleService;
import com.example.backend.integration.jooble.JoobleProperties;
import com.example.backend.integration.jooble.JoobleSearchProvider;
import com.example.backend.integration.search.RequestBudget;
import com.example.backend.integration.search.SearchProviderIngestionService;
import com.example.backend.integration.search.SearchSyncResult;
import com.example.backend.shared.provider.ProviderCatalog;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/** The Jooble key lives in the request path; no failure path may print it. */
@ExtendWith(OutputCaptureExtension.class)
class JoobleKeyRedactionTest {
    private static final String KEY = "test-only-secret-jooble-key-9f3a";

    @Test
    void connectionFailuresNeverLogTheKeyOrTheRequestUrl(CapturedOutput output) throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) { closedPort = socket.getLocalPort(); }
        String base = "http://127.0.0.1:" + closedPort + "/api";
        Map<String, String> urls = new HashMap<>();
        for (ProviderCatalog.Provider ats : ProviderCatalog.ofKind(ProviderCatalog.Kind.ATS_BOARD)) urls.put(ats.name(), base);
        ProviderReliabilityProperties reliability = new ProviderReliabilityProperties(200, 200, 2, 1, 10, urls,
                8_192, 20, 5, 10, 60_000);
        Clock clock = Clock.systemUTC();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build());
        factory.setReadTimeout(200);
        JoobleSearchProvider provider = new JoobleSearchProvider(
                new JoobleProperties(KEY, base, "engineer,analyst", "India", 1, 20, 100), reliability,
                new ProviderHttpClient(new RestTemplate(factory), clock), new ProviderCircuitBreaker(reliability, clock),
                new ProviderRequestLimiter(reliability, clock, ignored -> { }), ignored -> { }, bound -> 0);

        SearchSyncResult result = new SearchProviderIngestionService(mock(AdzunaJobStore.class),
                mock(ImportedJobLifecycleService.class), RequestBudget.unlimited(), clock).sync(provider, () -> true);

        assertEquals(SearchSyncResult.Outcome.COMPLETE_FAILURE, result.outcome());
        assertTrue(output.getAll().contains("event=jooble_batch_failed provider=jooble retryable=true"));
        assertFalse(output.getAll().contains(KEY));
        assertFalse(output.getAll().contains("127.0.0.1:" + closedPort));
        assertFalse(new JoobleProperties(KEY, base, "engineer", "India", 1, 20, 100).toString().contains(KEY));
    }
}
