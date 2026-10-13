package com.example.backend.integration.reliability;

import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.integration.ashby.AshbyJobSource;
import com.example.backend.integration.jobs.ExternalJob;
import com.example.backend.integration.jobs.JobFetchRequest;
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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

class AshbyJobSourceMockServerTest {
    private static final String LISTED = "{\"id\":\"a1\",\"title\":\" Backend  Engineer \",\"location\":\"Bengaluru, Karnataka, India\","
            + "\"isListed\":true,\"isRemote\":false,\"descriptionHtml\":\"<p>Build <b>APIs</b></p><script>x()</script>\","
            + "\"descriptionPlain\":\"plain\",\"publishedAt\":\"2026-09-01T10:15:30.000+00:00\",\"employmentType\":\"FullTime\","
            + "\"jobUrl\":\"https://jobs.ashbyhq.com/acme/a1\",\"applyUrl\":\"https://jobs.ashbyhq.com/acme/a1/application\","
            + "\"compensation\":{\"summaryComponents\":["
            + "{\"compensationType\":\"EquityPercentage\",\"interval\":\"NONE\",\"currencyCode\":null,\"minValue\":0.1,\"maxValue\":0.5},"
            + "{\"compensationType\":\"Salary\",\"interval\":\"1 YEAR\",\"currencyCode\":\"INR\",\"minValue\":2500000,\"maxValue\":4000000}]}}";
    private static final String FOREIGN_SALARY = "{\"id\":\"a2\",\"title\":\"Designer\",\"location\":\"Remote - India\","
            + "\"isListed\":true,\"descriptionPlain\":\"Design\",\"employmentType\":\"Intern\","
            + "\"applyUrl\":\"https://jobs.ashbyhq.com/acme/a2/application\",\"compensation\":{\"summaryComponents\":["
            + "{\"compensationType\":\"Salary\",\"interval\":\"1 YEAR\",\"currencyCode\":\"EUR\",\"minValue\":50000,\"maxValue\":70000}]}}";
    private static final String UNLISTED = "{\"id\":\"hidden\",\"title\":\"Secret\",\"isListed\":false,"
            + "\"jobUrl\":\"https://jobs.ashbyhq.com/acme/hidden\"}";

    private HttpServer server;
    private String base;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-13T12:00:00Z"), ZoneOffset.UTC);

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
    void retriesTransientFailuresThenMapsListedPostingsAndSkipsUnlisted() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/ashby/acme", exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) respond(exchange, 503, "unavailable");
            else if (attempt == 2) {
                exchange.getResponseHeaders().add("Retry-After", "1");
                respond(exchange, 429, "limited");
            } else respond(exchange, 200, "{\"apiVersion\":\"1\",\"jobs\":[" + LISTED + "," + UNLISTED + ","
                    + FOREIGN_SALARY + "]}");
        });
        List<Long> delays = new ArrayList<>();
        AshbyJobSource source = fixture(properties(3, 2_000, 8_192, 20, 3), delays::add);

        var result = source.fetchWithMetadata(request("acme"));

        ExternalJob listed = result.jobs().get(0);
        ExternalJob foreign = result.jobs().get(1);
        assertAll(
                () -> assertEquals(3, attempts.get()),
                () -> assertEquals(2, result.retries()),
                () -> assertEquals(List.of(200L, 1_000L), delays),
                () -> assertEquals("includeCompensation=true", query.get()),
                () -> assertEquals(2, result.jobs().size()),
                () -> assertEquals(0, result.rejectedItems()),
                () -> assertEquals("a1", listed.externalId()),
                () -> assertEquals("Backend Engineer", listed.title()),
                () -> assertEquals("Build APIs", listed.description()),
                () -> assertEquals("Acme", listed.company()),
                () -> assertEquals("Bengaluru, Karnataka, India", listed.location()),
                () -> assertEquals("Full-time", listed.employmentType()),
                () -> assertEquals(2_500_000d, listed.salaryMin()),
                () -> assertEquals(4_000_000d, listed.salaryMax()),
                () -> assertEquals("https://jobs.ashbyhq.com/acme/a1", listed.applicationUrl()),
                () -> assertEquals(LocalDateTime.of(2026, 9, 1, 10, 15, 30), listed.publishedAt()),
                () -> assertNotNull(listed.fingerprint()),
                () -> assertEquals("Internship", foreign.employmentType()),
                () -> assertEquals("Remote - India", foreign.location()),
                () -> assertNull(foreign.salaryMin()),
                () -> assertNull(foreign.salaryMax()),
                () -> assertEquals("https://jobs.ashbyhq.com/acme/a2/application", foreign.applicationUrl()),
                () -> assertTrue(result.jobs().stream().noneMatch(job -> "hidden".equals(job.externalId()))));
    }

    @Test
    void onlyAJobsArrayIsAValidEmptyBoard() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/ashby", exchange -> {
            attempts.incrementAndGet();
            switch (exchange.getRequestURI().getPath().split("/")[2]) {
                case "object" -> respond(exchange, 200, "{}");
                case "null" -> respond(exchange, 200, "null");
                case "missing" -> respond(exchange, 200, "{\"apiVersion\":\"1\"}");
                case "jobs-null" -> respond(exchange, 200, "{\"jobs\":null}");
                case "not-json" -> respond(exchange, 200, "not-json");
                case "empty-body" -> respond(exchange, 200, "");
                case "no-content" -> noContent(exchange);
                case "only-unlisted" -> respond(exchange, 200, "{\"jobs\":[" + UNLISTED + "]}");
                default -> respond(exchange, 200, "{\"apiVersion\":\"1\",\"jobs\":[]}");
            }
        });
        AshbyJobSource source = fixture(properties(3, 1_000, 8_192, 20, 3), ignored -> { });

        List<String> malformed = List.of("object", "null", "missing", "jobs-null", "not-json", "empty-body",
                "no-content");
        for (String board : malformed) {
            ProviderFailureException failure = assertThrows(ProviderFailureException.class,
                    () -> source.fetchWithMetadata(request(board)), board);
            assertAll(board,
                    () -> assertEquals(ProviderFailureException.Kind.MALFORMED_RESPONSE, failure.kind()),
                    () -> assertFalse(failure.retryable()),
                    () -> assertFalse(failure.getMessage().contains(base)));
        }
        var empty = source.fetchWithMetadata(request("valid"));
        var unlisted = source.fetchWithMetadata(request("only-unlisted"));
        assertAll(
                () -> assertTrue(empty.jobs().isEmpty()),
                () -> assertEquals(0, empty.rejectedItems()),
                () -> assertTrue(unlisted.jobs().isEmpty()),
                () -> assertEquals(0, unlisted.rejectedItems()),
                () -> assertEquals(malformed.size() + 2, attempts.get()));
    }

    @Test
    void permanentClientErrorsDoNotRetryAndServerErrorsExhaustTheBound() {
        AtomicInteger missing = new AtomicInteger();
        AtomicInteger failing = new AtomicInteger();
        server.createContext("/ashby/missing", exchange -> {
            missing.incrementAndGet();
            respond(exchange, 404, "Not Found");
        });
        server.createContext("/ashby/failing", exchange -> {
            failing.incrementAndGet();
            respond(exchange, 502, "bad gateway");
        });
        AshbyJobSource source = fixture(properties(3, 1_000, 8_192, 20, 10), ignored -> { });

        ProviderFailureException client = assertThrows(ProviderFailureException.class,
                () -> source.fetch(request("missing")));
        ProviderFailureException server = assertThrows(ProviderFailureException.class,
                () -> source.fetch(request("failing")));

        assertAll(
                () -> assertEquals(ProviderFailureException.Kind.CLIENT_ERROR, client.kind()),
                () -> assertEquals(1, missing.get()),
                () -> assertEquals(ProviderFailureException.Kind.SERVER_ERROR, server.kind()),
                () -> assertEquals(3, failing.get()));
    }

    @Test
    void responseBytesAndItemCountsAreBoundedWithoutRetry() {
        AtomicInteger bytesAttempts = new AtomicInteger();
        server.createContext("/ashby/huge", exchange -> {
            bytesAttempts.incrementAndGet();
            respond(exchange, 200, "{\"jobs\":[{\"id\":\"" + "x".repeat(2_000) + "\"}]}");
        });
        server.createContext("/ashby/many", exchange -> respond(exchange, 200,
                "{\"jobs\":[" + LISTED + "," + LISTED + "," + LISTED + "]}"));
        server.createContext("/ashby/two", exchange -> respond(exchange, 200,
                "{\"jobs\":[" + LISTED + "," + FOREIGN_SALARY + "]}"));
        AshbyJobSource smallBodies = fixture(properties(3, 1_000, 1_024, 20, 3), ignored -> { });
        AshbyJobSource fewItems = fixture(properties(3, 1_000, 8_192, 2, 3), ignored -> { });

        ProviderFailureException bytes = assertThrows(ProviderFailureException.class,
                () -> smallBodies.fetch(request("huge")));
        ProviderFailureException items = assertThrows(ProviderFailureException.class,
                () -> fewItems.fetch(request("many")));
        assertEquals(2, fewItems.fetch(request("two")).size());

        assertAll(
                () -> assertEquals(ProviderFailureException.Kind.PAYLOAD_LIMIT, bytes.kind()),
                () -> assertEquals(1, bytesAttempts.get()),
                () -> assertEquals(ProviderFailureException.Kind.PAYLOAD_LIMIT, items.kind()));
    }

    @Test
    void circuitOpensPerBoardWithoutAffectingAnotherBoard() {
        AtomicInteger broken = new AtomicInteger();
        server.createContext("/ashby/broken", exchange -> {
            broken.incrementAndGet();
            respond(exchange, 503, "unavailable");
        });
        server.createContext("/ashby/healthy", exchange -> respond(exchange, 200, "{\"jobs\":[]}"));
        AshbyJobSource source = fixture(properties(1, 1_000, 8_192, 20, 2), ignored -> { });

        assertThrows(ProviderFailureException.class, () -> source.fetch(request("broken")));
        assertThrows(ProviderFailureException.class, () -> source.fetch(request("broken")));
        ProviderFailureException open = assertThrows(ProviderFailureException.class,
                () -> source.fetch(request("broken")));

        assertAll(
                () -> assertEquals(ProviderFailureException.Kind.CIRCUIT_OPEN, open.kind()),
                () -> assertEquals(2, broken.get()),
                () -> assertTrue(source.fetch(request("healthy")).isEmpty()));
    }

    @Test
    void cancelledLeaseMakesNoHttpRequestAndLeaseLossDuringBackoffStopsRetries() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/ashby/cancel", exchange -> {
            attempts.incrementAndGet();
            respond(exchange, 503, "unavailable");
        });
        AshbyJobSource idle = fixture(properties(3, 1_000, 8_192, 20, 10), ignored -> { });
        ProviderFailureException before = assertThrows(ProviderFailureException.class,
                () -> idle.fetchWithMetadata(request("cancel"), () -> false));
        assertEquals(0, attempts.get());

        AtomicBoolean valid = new AtomicBoolean(true);
        AshbyJobSource backoff = fixture(properties(3, 1_000, 8_192, 20, 10), delay -> valid.set(false));
        ProviderFailureException during = assertThrows(ProviderFailureException.class,
                () -> backoff.fetchWithMetadata(request("cancel"), valid::get));

        assertAll(
                () -> assertEquals(ProviderFailureException.Kind.CANCELLED, before.kind()),
                () -> assertEquals(ProviderFailureException.Kind.CANCELLED, during.kind()),
                () -> assertEquals(1, attempts.get()));
    }

    @Test
    void malformedItemsAreCountedAsRejectedWhileValidSiblingsProceed() {
        server.createContext("/ashby/mixed", exchange -> respond(exchange, 200,
                "{\"jobs\":[" + LISTED + ",null," + FOREIGN_SALARY + "," + UNLISTED + "]}"));
        AshbyJobSource source = fixture(properties(3, 1_000, 8_192, 20, 3), ignored -> { });

        var result = source.fetchWithMetadata(request("mixed"));

        assertAll(
                () -> assertEquals(List.of("a1", "a2"), result.jobs().stream().map(ExternalJob::externalId).toList()),
                () -> assertEquals(1, result.rejectedItems()));
    }

    private AshbyJobSource fixture(ProviderReliabilityProperties properties, ProviderRetryExecutor.Sleeper sleeper) {
        HttpClient transport = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(properties.readTimeoutMs());
        RestTemplate template = new RestTemplate(factory);
        template.getInterceptors().add(new ProviderPayloadLimitInterceptor(properties.maxResponseBytes()));
        return new AshbyJobSource(new ProviderHttpClient(template, clock),
                new ProviderRetryExecutor(properties, sleeper, bound -> 0), properties,
                new ProviderCircuitBreaker(properties, clock),
                new ProviderRequestLimiter(properties, clock, ignored -> { }));
    }

    private ProviderReliabilityProperties properties(int attempts, long maxBackoff, int maxBytes, int maxItems,
                                                     int circuitThreshold) {
        Map<String, String> urls = new HashMap<>();
        for (ProviderCatalog.Provider provider : ProviderCatalog.ofKind(ProviderCatalog.Kind.ATS_BOARD)) {
            urls.put(provider.name(), base + "/" + provider.name());
        }
        return new ProviderReliabilityProperties(500, 500, attempts, 200, maxBackoff, urls, maxBytes, maxItems,
                5, circuitThreshold, 60_000);
    }

    private JobFetchRequest request(String board) {
        return new JobFetchRequest(null, 1, board, "Acme");
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

    private void noContent(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }
}
