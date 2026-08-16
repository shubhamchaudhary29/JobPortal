package com.example.backend.integration.reliability;

import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.integration.greenhouse.GreenhouseJobSource;
import com.example.backend.integration.jobs.JobFetchRequest;
import com.example.backend.integration.lever.LeverJobSource;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

class ProviderReliabilityMockServerTest {
    private HttpServer server;
    private String base;
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-16T12:00:00Z"), ZoneOffset.UTC);

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
    void greenhouseRetriesServerAndSecondsRateLimitThenReturnsTypedJobs() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/green/board/jobs", exchange -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) respond(exchange, 503, "unavailable");
            else if (attempt == 2) {
                exchange.getResponseHeaders().add("Retry-After", "1");
                respond(exchange, 429, "limited");
            } else respond(exchange, 200, "{\"jobs\":[{\"id\":1,\"title\":\"Engineer\","
                    + "\"content\":\"<p>Build</p>\",\"absolute_url\":\"https://jobs.example.test/1\","
                    + "\"location\":{\"name\":\"Remote\"}}]}");
        });
        List<Long> delays = new ArrayList<>();
        Fixture fixture = fixture(3, 2_000, 200, delays);
        GreenhouseJobSource source = fixture.greenhouse();

        var result = source.fetchWithMetadata(new JobFetchRequest(null, 1, "board", "Acme"));

        assertAll(
                () -> assertEquals(3, attempts.get()),
                () -> assertEquals(2, result.retries()),
                () -> assertEquals(List.of(200L, 1_000L), delays),
                () -> assertEquals(1, result.jobs().size()),
                () -> assertEquals("Engineer", result.jobs().get(0).title()),
                () -> assertEquals("https://jobs.example.test/1", result.jobs().get(0).applicationUrl()));
    }

    @Test
    void leverHonorsHttpDateRetryAfterAndRecovers() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/lever/board", exchange -> {
            if (attempts.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("Retry-After", ZonedDateTime.ofInstant(
                        clock.instant().plusSeconds(3), ZoneOffset.UTC).format(DateTimeFormatter.RFC_1123_DATE_TIME));
                respond(exchange, 429, "limited");
            } else respond(exchange, 200, "[{\"id\":\"one\",\"text\":\"Engineer\","
                    + "\"descriptionPlain\":\"Build\",\"hostedUrl\":\"https://jobs.example.test/one\","
                    + "\"categories\":{\"location\":\"Remote\",\"commitment\":\"Full-time\"}}]");
        });
        List<Long> delays = new ArrayList<>();
        Fixture fixture = fixture(3, 5_000, 100, delays);
        LeverJobSource source = fixture.lever();

        var result = source.fetchWithMetadata(new JobFetchRequest(null, 1, "board", "Acme"));

        assertAll(
                () -> assertEquals(2, attempts.get()),
                () -> assertEquals(1, result.retries()),
                () -> assertEquals(List.of(3_000L), delays),
                () -> assertEquals("one", result.jobs().get(0).externalId()));
    }

    @Test
    void permanentClientAndMalformedResponsesNeverRetryWhileServerErrorsExhaustBound() {
        AtomicInteger clientAttempts = new AtomicInteger();
        AtomicInteger malformedAttempts = new AtomicInteger();
        AtomicInteger serverAttempts = new AtomicInteger();
        server.createContext("/lever/client", exchange -> {
            clientAttempts.incrementAndGet();
            respond(exchange, 404, "missing");
        });
        server.createContext("/lever/malformed", exchange -> {
            malformedAttempts.incrementAndGet();
            respond(exchange, 200, "not-json");
        });
        server.createContext("/lever/server", exchange -> {
            serverAttempts.incrementAndGet();
            respond(exchange, 502, "bad gateway");
        });
        Fixture fixture = fixture(3, 1_000, 10, new ArrayList<>());
        LeverJobSource source = fixture.lever();

        ProviderFailureException client = assertThrows(ProviderFailureException.class,
                () -> source.fetch(new JobFetchRequest(null, 1, "client", "Acme")));
        ProviderFailureException malformed = assertThrows(ProviderFailureException.class,
                () -> source.fetch(new JobFetchRequest(null, 1, "malformed", "Acme")));
        ProviderFailureException exhausted = assertThrows(ProviderFailureException.class,
                () -> source.fetch(new JobFetchRequest(null, 1, "server", "Acme")));
        AtomicInteger policyAttempts = new AtomicInteger();
        assertThrows(ProviderFailureException.class, () -> fixture.retry().execute(() -> {
            policyAttempts.incrementAndGet();
            throw new ProviderFailureException("lever", ProviderFailureException.Kind.MALFORMED_RESPONSE,
                    false, null, null);
        }));

        assertAll(
                () -> assertEquals(ProviderFailureException.Kind.CLIENT_ERROR, client.kind()),
                () -> assertEquals(ProviderFailureException.Kind.MALFORMED_RESPONSE, malformed.kind()),
                () -> assertEquals(ProviderFailureException.Kind.SERVER_ERROR, exhausted.kind()),
                () -> assertEquals(1, clientAttempts.get()),
                () -> assertEquals(1, malformedAttempts.get()),
                () -> assertEquals(1, policyAttempts.get()),
                () -> assertEquals(3, serverAttempts.get()));
    }

    @Test
    void onlyStructurallyValidEmptyBoardsAreSuccessful() {
        AtomicInteger greenhouseAttempts = new AtomicInteger();
        AtomicInteger leverAttempts = new AtomicInteger();
        server.createContext("/green", exchange -> {
            greenhouseAttempts.incrementAndGet();
            String board = exchange.getRequestURI().getPath().split("/")[2];
            switch (board) {
                case "object" -> respond(exchange, 200, "{}");
                case "null" -> respond(exchange, 200, "null");
                case "missing" -> respond(exchange, 200, "{\"meta\":{}}");
                case "empty-body" -> respond(exchange, 200, "");
                case "no-content" -> noContent(exchange);
                default -> respond(exchange, 200, "{\"jobs\":[]}");
            }
        });
        server.createContext("/lever", exchange -> {
            leverAttempts.incrementAndGet();
            String board = exchange.getRequestURI().getPath().split("/")[2];
            switch (board) {
                case "null" -> respond(exchange, 200, "null");
                case "empty-body" -> respond(exchange, 200, "");
                case "no-content" -> noContent(exchange);
                default -> respond(exchange, 200, "[]");
            }
        });
        Fixture fixture = fixture(3, 1_000, 200, new ArrayList<>());

        for (String board : List.of("object", "null", "missing", "empty-body", "no-content")) {
            ProviderFailureException failure = assertThrows(ProviderFailureException.class,
                    () -> fixture.greenhouse().fetchWithMetadata(request(board)));
            assertAll(
                    () -> assertEquals(ProviderFailureException.Kind.MALFORMED_RESPONSE, failure.kind()),
                    () -> assertFalse(failure.retryable()),
                    () -> assertFalse(failure.getMessage().contains(base)));
        }
        for (String board : List.of("null", "empty-body", "no-content")) {
            ProviderFailureException failure = assertThrows(ProviderFailureException.class,
                    () -> fixture.lever().fetchWithMetadata(request(board)));
            assertAll(
                    () -> assertEquals(ProviderFailureException.Kind.MALFORMED_RESPONSE, failure.kind()),
                    () -> assertFalse(failure.retryable()),
                    () -> assertFalse(failure.getMessage().contains(base)));
        }

        assertAll(
                () -> assertTrue(fixture.greenhouse().fetchWithMetadata(request("valid")).jobs().isEmpty()),
                () -> assertTrue(fixture.lever().fetchWithMetadata(request("valid")).jobs().isEmpty()),
                () -> assertEquals(6, greenhouseAttempts.get()),
                () -> assertEquals(4, leverAttempts.get()));
    }

    @Test
    void leaseLossDuringRetryBackoffPreventsAnotherProviderRequest() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean valid = new AtomicBoolean(true);
        server.createContext("/green/cancel/jobs", exchange -> {
            attempts.incrementAndGet();
            respond(exchange, 503, "unavailable");
        });
        ProviderReliabilityProperties properties = properties(3, 1_000, 200);
        Fixture fixture = fixture(properties, delay -> valid.set(false));

        ProviderFailureException failure = assertThrows(ProviderFailureException.class,
                () -> fixture.greenhouse().fetchWithMetadata(request("cancel"), valid::get));

        assertAll(
                () -> assertEquals(ProviderFailureException.Kind.CANCELLED, failure.kind()),
                () -> assertEquals(1, attempts.get()));
    }

    @Test
    void leverLeaseLossDuringRetryBackoffPreventsAnotherProviderRequest() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean valid = new AtomicBoolean(true);
        server.createContext("/lever/cancel", exchange -> {
            attempts.incrementAndGet();
            respond(exchange, 503, "unavailable");
        });
        ProviderReliabilityProperties properties = properties(3, 1_000, 200);
        Fixture fixture = fixture(properties, delay -> valid.set(false));

        ProviderFailureException failure = assertThrows(ProviderFailureException.class,
                () -> fixture.lever().fetchWithMetadata(request("cancel"), valid::get));

        assertAll(
                () -> assertEquals(ProviderFailureException.Kind.CANCELLED, failure.kind()),
                () -> assertEquals(1, attempts.get()));
    }

    @Test
    void leaseLossDuringRateLimitWaitPreventsHttpRequest() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicBoolean valid = new AtomicBoolean(true);
        server.createContext("/lever/rate", exchange -> {
            attempts.incrementAndGet();
            respond(exchange, 200, "[]");
        });
        ProviderReliabilityProperties properties = properties(1, 1_000, 200);
        HttpClient transport = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(200);
        ProviderHttpClient client = new ProviderHttpClient(new RestTemplate(factory), clock);
        ProviderRequestLimiter limiter = new ProviderRequestLimiter(properties, clock,
                delay -> valid.set(false));
        LeverJobSource source = new LeverJobSource(client,
                new ProviderRetryExecutor(properties, ignored -> { }, ignored -> 0), properties,
                new ProviderCircuitBreaker(properties, clock), limiter);

        assertTrue(source.fetchWithMetadata(request("rate"), valid::get).jobs().isEmpty());
        ProviderFailureException failure = assertThrows(ProviderFailureException.class,
                () -> source.fetchWithMetadata(request("rate"), valid::get));

        assertAll(
                () -> assertEquals(ProviderFailureException.Kind.CANCELLED, failure.kind()),
                () -> assertEquals(1, attempts.get()));
    }

    @Test
    void readTimeoutIsTransientAndBounded() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/green/slow/jobs", exchange -> {
            attempts.incrementAndGet();
            try { Thread.sleep(250); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            respond(exchange, 200, "{\"jobs\":[]}");
        });
        Fixture fixture = fixture(2, 1_000, 75, new ArrayList<>());
        GreenhouseJobSource source = fixture.greenhouse();

        ProviderFailureException failure = assertThrows(ProviderFailureException.class,
                () -> source.fetch(new JobFetchRequest(null, 1, "slow", "Acme")));

        assertEquals(ProviderFailureException.Kind.TIMEOUT, failure.kind());
        assertEquals(2, attempts.get());
    }

    @Test
    void interruptedBackoffRestoresInterruptAndStopsRetries() {
        ProviderReliabilityProperties properties = properties(3, 1_000, 10);
        ProviderRetryExecutor executor = new ProviderRetryExecutor(properties,
                delay -> { throw new InterruptedException("stop"); }, bound -> 0);
        AtomicInteger attempts = new AtomicInteger();

        ProviderFailureException failure = assertThrows(ProviderFailureException.class, () -> executor.execute(() -> {
            attempts.incrementAndGet();
            throw new ProviderFailureException("lever", ProviderFailureException.Kind.SERVER_ERROR,
                    true, null, null);
        }));

        assertAll(
                () -> assertEquals(ProviderFailureException.Kind.INTERRUPTED, failure.kind()),
                () -> assertEquals(1, attempts.get()),
                () -> assertTrue(Thread.currentThread().isInterrupted()));
    }

    @Test
    void interruptedRateLimitWaitRestoresInterruptAndPreventsRequest() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/lever/interrupted", exchange -> {
            attempts.incrementAndGet();
            respond(exchange, 200, "[]");
        });
        ProviderReliabilityProperties properties = properties(1, 1_000, 200);
        HttpClient transport = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(200);
        ProviderRequestLimiter limiter = new ProviderRequestLimiter(properties, clock,
                delay -> { throw new InterruptedException("stop"); });
        limiter.acquire("lever", "interrupted");
        LeverJobSource source = new LeverJobSource(
                new ProviderHttpClient(new RestTemplate(factory), clock),
                new ProviderRetryExecutor(properties, ignored -> { }, ignored -> 0), properties,
                new ProviderCircuitBreaker(properties, clock),
                limiter);

        ProviderFailureException failure = assertThrows(ProviderFailureException.class,
                () -> source.fetchWithMetadata(request("interrupted")));

        assertAll(
                () -> assertEquals(ProviderFailureException.Kind.INTERRUPTED, failure.kind()),
                () -> assertEquals(0, attempts.get()),
                () -> assertTrue(Thread.currentThread().isInterrupted()));
    }

    @Test
    void configurationRejectsUnsafeBoundsAndBaseUrls() {
        assertThrows(IllegalArgumentException.class, () -> new ProviderReliabilityProperties(
                0, 100, 3, 10, 100, base, base));
        assertThrows(IllegalArgumentException.class, () -> new ProviderReliabilityProperties(
                100, 100, 6, 10, 100, base, base));
        assertThrows(IllegalArgumentException.class, () -> new ProviderReliabilityProperties(
                100, 100, 3, 10, 100, "file:///tmp/provider", base));
    }

    private Fixture fixture(int attempts, long maxBackoff, int readTimeout, List<Long> delays) {
        return fixture(properties(attempts, maxBackoff, readTimeout), delays::add);
    }

    private Fixture fixture(ProviderReliabilityProperties properties, ProviderRetryExecutor.Sleeper sleeper) {
        HttpClient transport = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(properties.readTimeoutMs());
        ProviderHttpClient client = new ProviderHttpClient(new RestTemplate(factory), clock);
        ProviderRetryExecutor retry = new ProviderRetryExecutor(properties, sleeper, bound -> 0);
        ProviderCircuitBreaker circuit = new ProviderCircuitBreaker(properties, clock);
        ProviderRequestLimiter limiter = new ProviderRequestLimiter(properties, clock, ignored -> { });
        return new Fixture(properties, client, retry, circuit, limiter);
    }

    private ProviderReliabilityProperties properties(int attempts, long maxBackoff, int readTimeout) {
        return new ProviderReliabilityProperties(200, readTimeout, attempts, 200, maxBackoff,
                base + "/green", base + "/lever");
    }

    private JobFetchRequest request(String board) {
        return new JobFetchRequest(null, 1, board, "Acme");
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private void noContent(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }

    private record Fixture(ProviderReliabilityProperties properties, ProviderHttpClient client,
                           ProviderRetryExecutor retry, ProviderCircuitBreaker circuit,
                           ProviderRequestLimiter limiter) {
        GreenhouseJobSource greenhouse() {
            return new GreenhouseJobSource(client, retry, properties, circuit, limiter);
        }
        LeverJobSource lever() {
            return new LeverJobSource(client, retry, properties, circuit, limiter);
        }
    }
}
