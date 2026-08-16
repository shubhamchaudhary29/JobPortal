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
        GreenhouseJobSource source = new GreenhouseJobSource(fixture.client(), fixture.retry(), fixture.properties());

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
        LeverJobSource source = new LeverJobSource(fixture.client(), fixture.retry(), fixture.properties());

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
        LeverJobSource source = new LeverJobSource(fixture.client(), fixture.retry(), fixture.properties());

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
    void readTimeoutIsTransientAndBounded() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/green/slow/jobs", exchange -> {
            attempts.incrementAndGet();
            try { Thread.sleep(250); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            respond(exchange, 200, "{\"jobs\":[]}");
        });
        Fixture fixture = fixture(2, 1_000, 75, new ArrayList<>());
        GreenhouseJobSource source = new GreenhouseJobSource(fixture.client(), fixture.retry(), fixture.properties());

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
    void configurationRejectsUnsafeBoundsAndBaseUrls() {
        assertThrows(IllegalArgumentException.class, () -> new ProviderReliabilityProperties(
                0, 100, 3, 10, 100, base, base));
        assertThrows(IllegalArgumentException.class, () -> new ProviderReliabilityProperties(
                100, 100, 6, 10, 100, base, base));
        assertThrows(IllegalArgumentException.class, () -> new ProviderReliabilityProperties(
                100, 100, 3, 10, 100, "file:///tmp/provider", base));
    }

    private Fixture fixture(int attempts, long maxBackoff, int readTimeout, List<Long> delays) {
        ProviderReliabilityProperties properties = properties(attempts, maxBackoff, readTimeout);
        HttpClient transport = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(properties.readTimeoutMs());
        ProviderHttpClient client = new ProviderHttpClient(new RestTemplate(factory), clock);
        ProviderRetryExecutor retry = new ProviderRetryExecutor(properties, delays::add, bound -> 0);
        return new Fixture(properties, client, retry);
    }

    private ProviderReliabilityProperties properties(int attempts, long maxBackoff, int readTimeout) {
        return new ProviderReliabilityProperties(200, readTimeout, attempts, 200, maxBackoff,
                base + "/green", base + "/lever");
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private record Fixture(ProviderReliabilityProperties properties, ProviderHttpClient client,
                           ProviderRetryExecutor retry) { }
}
