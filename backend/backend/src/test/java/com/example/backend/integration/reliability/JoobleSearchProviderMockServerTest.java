package com.example.backend.integration.reliability;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.backend.integration.adzuna.AdzunaJobStore;
import com.example.backend.integration.adzuna.UpsertOutcome;
import com.example.backend.integration.aggregation.ImportedJobLifecycleService;
import com.example.backend.integration.jobs.ExternalJob;
import com.example.backend.integration.jooble.JoobleProperties;
import com.example.backend.integration.jooble.JoobleSearchProvider;
import com.example.backend.integration.search.RequestBudget;
import com.example.backend.integration.search.SearchProviderException;
import com.example.backend.integration.search.SearchProviderIngestionService;
import com.example.backend.integration.search.SearchQuery;
import com.example.backend.integration.search.SearchSyncResult;
import com.example.backend.shared.provider.ProviderCatalog;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

class JoobleSearchProviderMockServerTest {
    static final String KEY = "test-only-jooble-key-0001";
    private static final String JOB = "{\"id\":-7155,\"title\":\" Backend  Engineer \",\"location\":\"Bangalore, Karnataka\","
            + "\"snippet\":\"&nbsp;Build <b>payments</b>...\",\"salary\":\"₹ 12,00,000 - ₹ 18,00,000 per year\","
            + "\"source\":\"naukri\",\"type\":\"Full-time\",\"link\":\"https://in.jooble.org/desc/-7155\","
            + "\"company\":\"Razorpay Software Pvt Ltd\",\"updated\":\"2026-09-15T12:55:35.3870000\"}";
    private static final String NO_COMPANY = "{\"id\":2,\"title\":\"Analyst\",\"location\":\"Pune\",\"link\":\"https://in.jooble.org/desc/2\"}";

    private HttpServer server;
    private String base;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-13T12:00:00Z"), ZoneOffset.UTC);
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        Thread.interrupted();
    }

    @Test
    void postsTheQueryAndMapsJobsKeepingJoobleLinksAndSkippingCompanylessPostings() {
        handle(exchange -> respond(exchange, 200, "{\"totalCount\":2,\"jobs\":[" + JOB + "," + NO_COMPANY + "]}"));
        JoobleSearchProvider provider = provider(KEY, 3, 8_192, 20, 3, ignored -> { });

        List<ExternalJob> jobs = provider.fetch(new SearchQuery("backend engineer", "Bengaluru", 2));

        ExternalJob job = jobs.get(0);
        assertAll(
                () -> assertEquals(1, jobs.size()),
                () -> assertEquals(List.of("/api/" + KEY), paths),
                () -> assertTrue(bodies.get(0).contains("\"keywords\":\"backend engineer\"")),
                () -> assertTrue(bodies.get(0).contains("\"location\":\"Bengaluru\"")),
                () -> assertTrue(bodies.get(0).contains("\"page\":\"2\"")),
                () -> assertTrue(bodies.get(0).contains("\"ResultOnPage\":20")),
                () -> assertEquals("-7155", job.externalId()),
                () -> assertEquals("Backend Engineer", job.title()),
                () -> assertEquals("Build payments...", job.description()),
                () -> assertEquals("Razorpay Software Pvt Ltd", job.company()),
                () -> assertEquals("Bangalore, Karnataka", job.location()),
                () -> assertEquals("Full-time", job.employmentType()),
                () -> assertEquals(1_200_000d, job.salaryMin()),
                () -> assertEquals(1_800_000d, job.salaryMax()),
                () -> assertEquals("https://in.jooble.org/desc/-7155", job.applicationUrl()),
                () -> assertEquals(LocalDateTime.of(2026, 9, 15, 12, 55, 35, 387_000_000), job.publishedAt()),
                () -> assertNotNull(job.fingerprint()));
    }

    @Test
    void onlyAJobsArrayIsValidAndMalformedResponsesAreNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        List<String> payloads = List.of("{}", "null", "{\"totalCount\":1}", "not-json", "");
        handle(exchange -> {
            int index = attempts.getAndIncrement();
            if (index < payloads.size()) respond(exchange, 200, payloads.get(index));
            else respond(exchange, 200, "{\"totalCount\":0,\"jobs\":[]}");
        });
        JoobleSearchProvider provider = provider(KEY, 3, 8_192, 20, 3, ignored -> { });

        for (String payload : payloads) {
            SearchProviderException failure = assertThrows(SearchProviderException.class,
                    () -> provider.fetch(query()), payload);
            assertFalse(failure.retryable(), payload);
            assertFalse(failure.getMessage().contains(KEY), payload);
        }
        assertTrue(provider.fetch(query()).isEmpty());
        assertEquals(payloads.size() + 1, attempts.get());
    }

    @Test
    void engineRetriesServerErrorsAndHonorsRetryAfterWithinBoundsThenStores() {
        AtomicInteger attempts = new AtomicInteger();
        handle(exchange -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) respond(exchange, 503, "unavailable");
            else if (attempt == 2) {
                exchange.getResponseHeaders().add("Retry-After", "1");
                respond(exchange, 429, "slow down");
            } else respond(exchange, 200, "{\"totalCount\":1,\"jobs\":[" + JOB + "]}");
        });
        List<Long> sleeps = new ArrayList<>();
        AdzunaJobStore store = mock(AdzunaJobStore.class);
        when(store.upsert(any(), any())).thenReturn(UpsertOutcome.INSERTED);
        ImportedJobLifecycleService lifecycle = mock(ImportedJobLifecycleService.class);
        JoobleSearchProvider provider = provider(KEY, 3, 8_192, 20, 5, sleeps::add);

        SearchSyncResult result = new SearchProviderIngestionService(store, lifecycle, RequestBudget.unlimited(), clock)
                .sync(provider, () -> true);

        assertAll(
                () -> assertEquals(SearchSyncResult.Outcome.FULL_SUCCESS, result.outcome()),
                () -> assertEquals(3, attempts.get()),
                () -> assertEquals(2, result.retries()),
                () -> assertEquals(2, sleeps.size()),
                () -> assertEquals(1_000L, sleeps.get(1)),
                () -> assertEquals(1, result.inserted()));
        verify(store).upsert(argThat(job -> "jooble".equals(job.getSource()) && "-7155".equals(job.getExternalId())), any());
        verify(lifecycle).completeSuccessfulRun(any(), any(), any(), any());
    }

    @Test
    void invalidKeyIsAPermanentClientErrorAndTheBudgetStopsFurtherRequests() {
        AtomicInteger attempts = new AtomicInteger();
        handle(exchange -> {
            attempts.incrementAndGet();
            respond(exchange, 403, "Access denied");
        });
        JoobleSearchProvider provider = provider(KEY, 3, 8_192, 20, 10, ignored -> { });

        SearchProviderException failure = assertThrows(SearchProviderException.class, () -> provider.fetch(query()));
        AtomicInteger granted = new AtomicInteger();
        JoobleSearchProvider twoKeywords = provider(KEY, "engineer,analyst", 3, 8_192, 20, 10, ignored -> { });
        SearchSyncResult limited = new SearchProviderIngestionService(mock(AdzunaJobStore.class),
                mock(ImportedJobLifecycleService.class), (name, limit) -> granted.getAndIncrement() < 1, clock)
                .sync(twoKeywords, () -> true);

        assertAll(
                () -> assertFalse(failure.retryable()),
                () -> assertTrue(failure.getMessage().contains("client_error")),
                () -> assertEquals(2, attempts.get()),
                () -> assertEquals(1, limited.failedBatches()),
                () -> assertTrue(limited.budgetExhausted()));
    }

    @Test
    void circuitOpensWithoutFurtherRequestsAndCancelledRunsMakeNone() {
        AtomicInteger attempts = new AtomicInteger();
        handle(exchange -> {
            attempts.incrementAndGet();
            respond(exchange, 503, "unavailable");
        });
        JoobleSearchProvider provider = provider(KEY, 1, 8_192, 20, 2, ignored -> { });

        assertThrows(SearchProviderException.class, () -> provider.fetch(query()));
        assertThrows(SearchProviderException.class, () -> provider.fetch(query()));
        SearchProviderException open = assertThrows(SearchProviderException.class, () -> provider.fetch(query()));
        SearchSyncResult cancelled = new SearchProviderIngestionService(mock(AdzunaJobStore.class),
                mock(ImportedJobLifecycleService.class), RequestBudget.unlimited(), clock).sync(provider, () -> false);

        assertAll(
                () -> assertTrue(open.getMessage().contains("circuit_open")),
                () -> assertFalse(open.retryable()),
                () -> assertEquals(2, attempts.get()),
                () -> assertEquals(0, cancelled.attemptedBatches()));
    }

    @Test
    void payloadAndItemLimitsAreEnforcedAndDisabledProviderSendsNothing() {
        AtomicInteger attempts = new AtomicInteger();
        handle(exchange -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) respond(exchange, 200, "{\"jobs\":[{\"title\":\"" + "x".repeat(2_000) + "\"}]}");
            else respond(exchange, 200, "{\"jobs\":[" + JOB + "," + JOB + "," + JOB + "]}");
        });

        SearchProviderException bytes = assertThrows(SearchProviderException.class,
                () -> provider(KEY, 3, 1_024, 20, 3, ignored -> { }).fetch(query()));
        SearchProviderException items = assertThrows(SearchProviderException.class,
                () -> provider(KEY, 3, 8_192, 2, 3, ignored -> { }).fetch(query()));
        SearchProviderException disabled = assertThrows(SearchProviderException.class,
                () -> provider("", 3, 8_192, 20, 3, ignored -> { }).fetch(query()));

        assertAll(
                () -> assertTrue(bytes.getMessage().contains("payload_limit")),
                () -> assertTrue(items.getMessage().contains("payload_limit")),
                () -> assertEquals("Jooble is not configured", disabled.getMessage()),
                () -> assertEquals(2, attempts.get()));
    }

    private SearchQuery query() { return new SearchQuery("engineer", "India", 1); }

    private void handle(Handler handler) {
        server.createContext("/api", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            handler.handle(exchange);
        });
    }

    JoobleSearchProvider provider(String key, int attempts, int maxBytes, int maxItems, int circuitThreshold,
                                  JoobleSearchProvider.Sleeper sleeper) {
        return provider(key, "engineer", attempts, maxBytes, maxItems, circuitThreshold, sleeper);
    }

    JoobleSearchProvider provider(String key, String keywords, int attempts, int maxBytes, int maxItems,
                                  int circuitThreshold, JoobleSearchProvider.Sleeper sleeper) {
        Map<String, String> urls = new HashMap<>();
        for (ProviderCatalog.Provider ats : ProviderCatalog.ofKind(ProviderCatalog.Kind.ATS_BOARD)) {
            urls.put(ats.name(), base + "/" + ats.name());
        }
        ProviderReliabilityProperties reliability = new ProviderReliabilityProperties(500, 500, attempts, 200, 2_000,
                urls, maxBytes, maxItems, 5, circuitThreshold, 60_000);
        HttpClient transport = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(500);
        RestTemplate template = new RestTemplate(factory);
        template.getInterceptors().add(new ProviderPayloadLimitInterceptor(maxBytes));
        JoobleProperties properties = new JoobleProperties(key, base + "/api", keywords, "India", 1, 20, 100);
        return new JoobleSearchProvider(properties, reliability, new ProviderHttpClient(template, clock),
                new ProviderCircuitBreaker(reliability, clock), new ProviderRequestLimiter(reliability, clock, ignored -> { }),
                sleeper, bound -> 0);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length == 0) {
            exchange.close();
            return;
        }
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    @FunctionalInterface
    interface Handler { void handle(HttpExchange exchange) throws IOException; }
}
