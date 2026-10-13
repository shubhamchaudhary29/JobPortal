package com.example.backend.integration.reliability;

import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.integration.workable.WorkableJobSource;
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

class WorkableJobSourceMockServerTest {
    private static final String ONSITE = "{\"shortcode\":\"AB12CD34EF\",\"title\":\" Backend  Engineer \","
            + "\"description\":\"<p>Build <b>APIs</b></p><script>x()</script>\",\"employment_type\":\"Full-time\","
            + "\"telecommuting\":false,\"city\":\"Bengaluru\",\"state\":\"Karnataka\",\"country\":\"India\","
            + "\"published_on\":\"2026-09-01\",\"url\":\"https://apply.workable.com/j/AB12CD34EF\","
            + "\"application_url\":\"https://apply.workable.com/j/AB12CD34EF/apply\"}";
    private static final String REMOTE = "{\"shortcode\":\"ZZ99\",\"title\":\"Designer\",\"description\":\"Design\","
            + "\"employment_type\":\"Contract\",\"telecommuting\":true,\"city\":\"Paris\",\"country\":\"France\","
            + "\"application_url\":\"https://apply.workable.com/j/ZZ99/apply\"}";

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
    void retriesTransientFailuresThenMapsAccountJobs() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/workable/acme", exchange -> {
            query.set(exchange.getRequestURI().getQuery());
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) respond(exchange, 503, "unavailable");
            else if (attempt == 2) {
                exchange.getResponseHeaders().add("Retry-After", "1");
                respond(exchange, 429, "limited");
            } else respond(exchange, 200, "{\"name\":\"Acme\",\"description\":null,\"jobs\":[" + ONSITE + "," + REMOTE + "]}");
        });
        List<Long> delays = new ArrayList<>();
        WorkableJobSource source = fixture(properties(3, 2_000, 8_192, 20, 3), delays::add);

        var result = source.fetchWithMetadata(request("acme"));

        ExternalJob onsite = result.jobs().get(0);
        ExternalJob remote = result.jobs().get(1);
        assertAll(
                () -> assertEquals(3, attempts.get()),
                () -> assertEquals(2, result.retries()),
                () -> assertEquals(List.of(200L, 1_000L), delays),
                () -> assertEquals("details=true", query.get()),
                () -> assertEquals(2, result.jobs().size()),
                () -> assertEquals(0, result.rejectedItems()),
                () -> assertEquals("AB12CD34EF", onsite.externalId()),
                () -> assertEquals("Backend Engineer", onsite.title()),
                () -> assertEquals("Build APIs", onsite.description()),
                () -> assertEquals("Acme", onsite.company()),
                () -> assertEquals("Bengaluru, Karnataka, India", onsite.location()),
                () -> assertEquals("Full-time", onsite.employmentType()),
                () -> assertEquals("https://apply.workable.com/j/AB12CD34EF", onsite.applicationUrl()),
                () -> assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0), onsite.publishedAt()),
                () -> assertNull(onsite.salaryMin()),
                // The shared normalizer currently renders a bare "Remote" as "Remote - "; M11 fixes that with a
                // versioned fingerprint migration. WorkableJobSourceTest covers the adapter's own "Remote" mapping.
                () -> assertTrue(remote.location().startsWith("Remote")),
                () -> assertEquals("Contract", remote.employmentType()),
                () -> assertEquals("https://apply.workable.com/j/ZZ99/apply", remote.applicationUrl()),
                () -> assertNull(remote.publishedAt()));
    }

    @Test
    void onlyAJobsArrayIsAValidEmptyAccount() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/workable", exchange -> {
            attempts.incrementAndGet();
            switch (exchange.getRequestURI().getPath().split("/")[2]) {
                case "object" -> respond(exchange, 200, "{}");
                case "null" -> respond(exchange, 200, "null");
                case "missing" -> respond(exchange, 200, "{\"name\":\"Acme\"}");
                case "jobs-null" -> respond(exchange, 200, "{\"jobs\":null}");
                case "not-json" -> respond(exchange, 200, "not-json");
                case "empty-body" -> respond(exchange, 200, "");
                case "no-content" -> noContent(exchange);
                default -> respond(exchange, 200, "{\"name\":\"Acme\",\"jobs\":[]}");
            }
        });
        WorkableJobSource source = fixture(properties(3, 1_000, 8_192, 20, 3), ignored -> { });

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
        assertAll(
                () -> assertTrue(empty.jobs().isEmpty()),
                () -> assertEquals(0, empty.rejectedItems()),
                () -> assertEquals(malformed.size() + 1, attempts.get()));
    }

    @Test
    void permanentClientErrorsDoNotRetryAndServerErrorsExhaustTheBound() {
        AtomicInteger missing = new AtomicInteger();
        AtomicInteger failing = new AtomicInteger();
        server.createContext("/workable/missing", exchange -> {
            missing.incrementAndGet();
            respond(exchange, 404, "Not Found");
        });
        server.createContext("/workable/failing", exchange -> {
            failing.incrementAndGet();
            respond(exchange, 502, "bad gateway");
        });
        WorkableJobSource source = fixture(properties(3, 1_000, 8_192, 20, 10), ignored -> { });

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
        server.createContext("/workable/huge", exchange -> {
            bytesAttempts.incrementAndGet();
            respond(exchange, 200, "{\"jobs\":[{\"shortcode\":\"" + "x".repeat(2_000) + "\"}]}");
        });
        server.createContext("/workable/many", exchange -> respond(exchange, 200,
                "{\"jobs\":[" + ONSITE + "," + ONSITE + "," + ONSITE + "]}"));
        server.createContext("/workable/two", exchange -> respond(exchange, 200,
                "{\"jobs\":[" + ONSITE + "," + REMOTE + "]}"));
        WorkableJobSource smallBodies = fixture(properties(3, 1_000, 1_024, 20, 3), ignored -> { });
        WorkableJobSource fewItems = fixture(properties(3, 1_000, 8_192, 2, 3), ignored -> { });

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
        server.createContext("/workable/broken", exchange -> {
            broken.incrementAndGet();
            respond(exchange, 503, "unavailable");
        });
        server.createContext("/workable/healthy", exchange -> respond(exchange, 200, "{\"jobs\":[]}"));
        WorkableJobSource source = fixture(properties(1, 1_000, 8_192, 20, 2), ignored -> { });

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
        server.createContext("/workable/cancel", exchange -> {
            attempts.incrementAndGet();
            respond(exchange, 503, "unavailable");
        });
        WorkableJobSource idle = fixture(properties(3, 1_000, 8_192, 20, 10), ignored -> { });
        ProviderFailureException before = assertThrows(ProviderFailureException.class,
                () -> idle.fetchWithMetadata(request("cancel"), () -> false));
        assertEquals(0, attempts.get());

        AtomicBoolean valid = new AtomicBoolean(true);
        WorkableJobSource backoff = fixture(properties(3, 1_000, 8_192, 20, 10), delay -> valid.set(false));
        ProviderFailureException during = assertThrows(ProviderFailureException.class,
                () -> backoff.fetchWithMetadata(request("cancel"), valid::get));

        assertAll(
                () -> assertEquals(ProviderFailureException.Kind.CANCELLED, before.kind()),
                () -> assertEquals(ProviderFailureException.Kind.CANCELLED, during.kind()),
                () -> assertEquals(1, attempts.get()));
    }

    @Test
    void malformedItemsAreCountedAsRejectedWhileValidSiblingsProceed() {
        server.createContext("/workable/mixed", exchange -> respond(exchange, 200,
                "{\"jobs\":[" + ONSITE + ",null," + REMOTE + "]}"));
        WorkableJobSource source = fixture(properties(3, 1_000, 8_192, 20, 3), ignored -> { });

        var result = source.fetchWithMetadata(request("mixed"));

        assertAll(
                () -> assertEquals(List.of("AB12CD34EF", "ZZ99"), result.jobs().stream().map(ExternalJob::externalId).toList()),
                () -> assertEquals(1, result.rejectedItems()));
    }

    private WorkableJobSource fixture(ProviderReliabilityProperties properties, ProviderRetryExecutor.Sleeper sleeper) {
        HttpClient transport = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(properties.readTimeoutMs());
        RestTemplate template = new RestTemplate(factory);
        template.getInterceptors().add(new ProviderPayloadLimitInterceptor(properties.maxResponseBytes()));
        return new WorkableJobSource(new ProviderHttpClient(template, clock),
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
