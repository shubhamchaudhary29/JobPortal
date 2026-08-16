package com.example.backend.integration.reliability;

import static org.junit.jupiter.api.Assertions.*;

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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

class ProviderPayloadProtectionMockServerTest {
    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void contentLengthAndChunkedBodiesAreBoundedWithoutRetry() {
        AtomicInteger fixedAttempts = new AtomicInteger();
        AtomicInteger chunkedAttempts = new AtomicInteger();
        server.createContext("/lever/fixed", exchange -> {
            fixedAttempts.incrementAndGet();
            respond(exchange, "[\"" + "x".repeat(1_100) + "\"]", false);
        });
        server.createContext("/lever/chunked", exchange -> {
            chunkedAttempts.incrementAndGet();
            respond(exchange, "[\"" + "x".repeat(1_100) + "\"]", true);
        });
        Fixture fixture = fixture(1_024, 10, 1, 3, 1_000);

        ProviderFailureException fixed = assertThrows(ProviderFailureException.class,
                () -> fixture.source().fetch(request("fixed")));
        ProviderFailureException chunked = assertThrows(ProviderFailureException.class,
                () -> fixture.source().fetch(request("chunked")));

        assertAll(
                () -> assertEquals(ProviderFailureException.Kind.PAYLOAD_LIMIT, fixed.kind()),
                () -> assertEquals(ProviderFailureException.Kind.PAYLOAD_LIMIT, chunked.kind()),
                () -> assertEquals(1, fixedAttempts.get()),
                () -> assertEquals(1, chunkedAttempts.get()));
    }

    @Test
    void itemLimitMalformedItemIsolationAndLegitimateEmptyPayloadAreDistinct() {
        String valid = "{\"id\":\"one\",\"text\":\"Engineer\",\"hostedUrl\":\"https://jobs.test/one\"}";
        server.createContext("/lever/items", exchange -> respond(exchange,
                "[" + valid + "," + valid.replace("one", "two") + "," + valid.replace("one", "three")
                        + "," + valid.replace("one", "four") + "]", false));
        server.createContext("/lever/mixed", exchange -> respond(exchange,
                "[" + valid + ",null," + valid.replace("one", "two") + "]", false));
        server.createContext("/lever/empty", exchange -> respond(exchange, "[]", false));
        Fixture fixture = fixture(8_192, 3, 1, 3, 1_000);

        ProviderFailureException limit = assertThrows(ProviderFailureException.class,
                () -> fixture.source().fetch(request("items")));
        var mixed = fixture.source().fetchWithMetadata(request("mixed"));
        var empty = fixture.source().fetchWithMetadata(request("empty"));

        assertAll(
                () -> assertEquals(ProviderFailureException.Kind.PAYLOAD_LIMIT, limit.kind()),
                () -> assertEquals(2, mixed.jobs().size()),
                () -> assertEquals(1, mixed.rejectedItems()),
                () -> assertTrue(empty.jobs().isEmpty()),
                () -> assertEquals(0, empty.rejectedItems()));
    }

    @Test
    void circuitOpensRecoversAndNeverAffectsAnotherEmployer() {
        MutableClock clock = new MutableClock();
        ProviderReliabilityProperties properties = properties(8_192, 20, 1, 2, 500);
        ProviderCircuitBreaker circuit = new ProviderCircuitBreaker(properties, clock);
        AtomicInteger calls = new AtomicInteger();

        for (int count = 0; count < 2; count++) {
            assertThrows(ProviderFailureException.class, () -> circuit.execute("lever", "broken", () -> {
                calls.incrementAndGet();
                throw transientFailure();
            }));
        }
        ProviderFailureException open = assertThrows(ProviderFailureException.class,
                () -> circuit.execute("lever", "broken", () -> { calls.incrementAndGet(); return "no"; }));
        String isolated = circuit.execute("lever", "healthy", () -> "healthy");
        clock.advance(501);
        String recovered = circuit.execute("lever", "broken", () -> "recovered");
        String closed = circuit.execute("lever", "broken", () -> "closed");

        assertAll(
                () -> assertEquals(ProviderFailureException.Kind.CIRCUIT_OPEN, open.kind()),
                () -> assertEquals(2, calls.get()),
                () -> assertEquals("healthy", isolated),
                () -> assertEquals("recovered", recovered),
                () -> assertEquals("closed", closed));
    }

    @Test
    void requestRateSlotsAreBoundedPerEmployer() {
        MutableClock clock = new MutableClock();
        ProviderReliabilityProperties properties = properties(8_192, 20, 1, 3, 1_000);
        List<Long> waits = new ArrayList<>();
        ProviderRequestLimiter limiter = new ProviderRequestLimiter(properties, clock, waits::add);

        limiter.acquire("lever", "one");
        limiter.acquire("lever", "one");
        limiter.acquire("lever", "two");
        limiter.acquire("lever", "one");

        assertEquals(List.of(200L, 400L), waits);
    }

    private Fixture fixture(int maxBytes, int maxItems, int maxAttempts, int threshold, long openMs) {
        MutableClock clock = new MutableClock();
        ProviderReliabilityProperties properties = properties(maxBytes, maxItems, maxAttempts, threshold, openMs);
        HttpClient transport = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(500);
        RestTemplate template = new RestTemplate(factory);
        template.getInterceptors().add(new ProviderPayloadLimitInterceptor(maxBytes));
        ProviderHttpClient client = new ProviderHttpClient(template, clock);
        ProviderRetryExecutor retry = new ProviderRetryExecutor(properties, ignored -> { }, ignored -> 0);
        ProviderCircuitBreaker circuit = new ProviderCircuitBreaker(properties, clock);
        ProviderRequestLimiter limiter = new ProviderRequestLimiter(properties, clock, ignored -> { });
        return new Fixture(new LeverJobSource(client, retry, properties, circuit, limiter));
    }

    private ProviderReliabilityProperties properties(int maxBytes, int maxItems, int maxAttempts,
                                                      int threshold, long openMs) {
        return new ProviderReliabilityProperties(500, 500, maxAttempts, 1, 10,
                base, base + "/lever", maxBytes, maxItems, 5, threshold, openMs);
    }

    private JobFetchRequest request(String board) { return new JobFetchRequest(null, 1, board, "Acme"); }
    private ProviderFailureException transientFailure() {
        return new ProviderFailureException("lever", ProviderFailureException.Kind.SERVER_ERROR,
                true, null, null);
    }
    private void respond(HttpExchange exchange, String body, boolean chunked) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Connection", "close");
        exchange.sendResponseHeaders(200, chunked ? 0 : bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private record Fixture(LeverJobSource source) { }

    private static final class MutableClock extends Clock {
        private long millis = Instant.parse("2026-08-16T12:00:00Z").toEpochMilli();
        void advance(long amount) { millis += amount; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }
}
