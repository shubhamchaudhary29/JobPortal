package com.example.backend.integration.reliability;

import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.integration.jobs.ExternalJob;
import com.example.backend.integration.jobs.JobFetchRequest;
import com.example.backend.integration.smartrecruiters.SmartRecruitersDetailCache;
import com.example.backend.integration.smartrecruiters.SmartRecruitersJobSource;
import com.example.backend.integration.smartrecruiters.SmartRecruitersProperties;
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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

class SmartRecruitersJobSourceMockServerTest {
    private HttpServer server;
    private String base;
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-13T12:00:00Z"), ZoneOffset.UTC);
    private final InMemoryCache cache = new InMemoryCache();
    private final Map<String, AtomicInteger> detailRequests = new ConcurrentHashMap<>();
    private volatile Runnable afterDetail = () -> { };

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
    void pagesThroughTheBoardFetchesEachDetailOnceAndMapsPostings() {
        AtomicInteger listAttempts = new AtomicInteger();
        List<String> offsets = new ArrayList<>();
        board("acme", exchange -> {
            if (listAttempts.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("Retry-After", "1");
                respond(exchange, 429, "limited");
                return;
            }
            String query = exchange.getRequestURI().getQuery();
            offsets.add(query);
            if (query.contains("offset=0")) respond(exchange, 200, page(3, posting("1", "2026-09-01T00:00:00.000Z",
                    "\"city\":\"bangalore\",\"region\":\"KA\",\"country\":\"in\",\"remote\":false", "Full-time"),
                    posting("2", "2026-09-02T00:00:00.000Z", "\"country\":\"in\",\"remote\":true", "Internship")));
            else respond(exchange, 200, page(3, posting("3", null, "\"city\":\"Pune\"", null)));
        });
        List<Long> delays = new ArrayList<>();
        SmartRecruitersJobSource source = fixture(sr(2, 20, 100), properties(3, 8_192, 20, 5), delays::add);

        JobSourceResult first = run(source, "acme");
        JobSourceResult second = run(source, "acme");

        ExternalJob bangalore = first.jobs().get(0);
        ExternalJob remote = first.jobs().get(1);
        assertAll(
                () -> assertTrue(first.complete()),
                () -> assertEquals(List.of("offset=0&limit=2", "offset=2&limit=2"), offsets.subList(0, 2)),
                () -> assertEquals(List.of(1_000L), delays),
                () -> assertEquals(1, first.retries()),
                () -> assertEquals(List.of("1", "2", "3"), first.jobs().stream().map(ExternalJob::externalId).toList()),
                () -> assertEquals("Engineer 1", bangalore.title()),
                () -> assertEquals("Acme", bangalore.company()),
                () -> assertEquals("bangalore, KA, India", bangalore.location()),
                () -> assertEquals("Full-time", bangalore.employmentType()),
                () -> assertEquals("Job Description Build things for 1 Qualifications Java", bangalore.description()),
                () -> assertEquals("https://jobs.smartrecruiters.com/acme/1-engineer", bangalore.applicationUrl()),
                () -> assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0), bangalore.publishedAt()),
                () -> assertEquals("Remote - India", remote.location()),
                () -> assertEquals("Internship", remote.employmentType()),
                () -> assertEquals("Pune", first.jobs().get(2).location()),
                () -> assertEquals(Map.of("1", 1, "2", 1, "3", 1), detailCounts()),
                () -> assertTrue(second.complete()),
                () -> assertEquals(3, second.jobs().size()),
                () -> assertEquals(first.jobs(), second.jobs()));
    }

    @Test
    void rereleasedPostingIsFetchedAgainWhileUnchangedPostingsUseTheCache() {
        AtomicInteger runs = new AtomicInteger();
        board("acme", exchange -> respond(exchange, 200, page(2,
                posting("1", "2026-09-01T00:00:00.000Z", "\"city\":\"Pune\"", null),
                posting("2", runs.get() == 0 ? "2026-09-01T00:00:00.000Z" : "2026-10-01T00:00:00.000Z",
                        "\"city\":\"Pune\"", null))));
        SmartRecruitersJobSource source = fixture(sr(100, 20, 100), properties(1, 8_192, 20, 5), ignored -> { });

        run(source, "acme");
        runs.incrementAndGet();
        JobSourceResult second = run(source, "acme");

        assertAll(
                () -> assertTrue(second.complete()),
                () -> assertEquals(Map.of("1", 1, "2", 2), detailCounts()));
    }

    @Test
    void pageCapItemCapAndDetailBudgetReturnIncompleteResults() {
        board("big", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query.contains("offset=0")) respond(exchange, 200, page(4, posting("1", null, "", null),
                    posting("2", null, "", null)));
            else respond(exchange, 200, page(4, posting("3", null, "", null), posting("4", null, "", null)));
        });
        JobSourceResult pageCapped = run(fixture(sr(2, 1, 100), properties(1, 8_192, 20, 5), ignored -> { }), "big");
        JobSourceResult itemCapped = run(fixture(sr(2, 20, 100), properties(1, 8_192, 3, 5), ignored -> { }), "big");
        cache.entries.clear();
        detailRequests.clear();
        SmartRecruitersJobSource budgeted = fixture(sr(2, 20, 1), properties(1, 8_192, 20, 5), ignored -> { });
        JobSourceResult firstBudget = run(budgeted, "big");
        JobSourceResult secondBudget = run(budgeted, "big");

        assertAll(
                () -> assertFalse(pageCapped.complete()),
                () -> assertEquals(List.of("1", "2"), ids(pageCapped)),
                () -> assertFalse(itemCapped.complete()),
                () -> assertEquals(List.of("1", "2", "3"), ids(itemCapped)),
                () -> assertFalse(firstBudget.complete()),
                () -> assertEquals(List.of("1"), ids(firstBudget)),
                () -> assertEquals(0, firstBudget.rejected()),
                () -> assertFalse(secondBudget.complete()),
                () -> assertEquals(List.of("1", "2"), ids(secondBudget)),
                () -> assertEquals(Map.of("1", 1, "2", 1), detailCounts()));
    }

    @Test
    void failureOnALaterPageKeepsEarlierPagesButIsIncomplete() {
        AtomicInteger laterPage = new AtomicInteger();
        board("flaky", exchange -> {
            if (exchange.getRequestURI().getQuery().contains("offset=0")) {
                respond(exchange, 200, page(3, posting("1", null, "", null), posting("2", null, "", null)));
            } else {
                laterPage.incrementAndGet();
                respond(exchange, 502, "bad gateway");
            }
        });
        board("garbled", exchange -> {
            if (exchange.getRequestURI().getQuery().contains("offset=0")) {
                respond(exchange, 200, page(3, posting("1", null, "", null), posting("2", null, "", null)));
            } else respond(exchange, 200, "{\"offset\":2}");
        });
        SmartRecruitersJobSource source = fixture(sr(2, 20, 100), properties(2, 8_192, 20, 10), ignored -> { });

        JobSourceResult failed = run(source, "flaky");
        JobSourceResult malformed = run(source, "garbled");

        assertAll(
                () -> assertFalse(failed.complete()),
                () -> assertEquals(List.of("1", "2"), ids(failed)),
                () -> assertEquals(2, laterPage.get()),
                () -> assertFalse(malformed.complete()),
                () -> assertEquals(List.of("1", "2"), ids(malformed)));
    }

    @Test
    void onlyAContentArrayIsAValidEmptyBoard() {
        AtomicInteger attempts = new AtomicInteger();
        server.createContext("/smartrecruiters", exchange -> {
            attempts.incrementAndGet();
            switch (exchange.getRequestURI().getPath().split("/")[2]) {
                case "object" -> respond(exchange, 200, "{}");
                case "null" -> respond(exchange, 200, "null");
                case "missing" -> respond(exchange, 200, "{\"offset\":0,\"totalFound\":0}");
                case "not-json" -> respond(exchange, 200, "not-json");
                case "empty-body" -> respond(exchange, 200, "");
                case "no-content" -> noContent(exchange);
                default -> respond(exchange, 200, "{\"offset\":0,\"limit\":100,\"totalFound\":0,\"content\":[]}");
            }
        });
        SmartRecruitersJobSource source = fixture(sr(100, 20, 100), properties(3, 8_192, 20, 5), ignored -> { });

        List<String> malformed = List.of("object", "null", "missing", "not-json", "empty-body", "no-content");
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
                () -> assertTrue(empty.complete()),
                () -> assertEquals(0, empty.rejectedItems()),
                () -> assertEquals(malformed.size() + 1, attempts.get()));
    }

    @Test
    void firstPageClientErrorsAndOversizedBodiesFailWithoutRetry() {
        AtomicInteger missing = new AtomicInteger();
        AtomicInteger huge = new AtomicInteger();
        server.createContext("/smartrecruiters/missing", exchange -> {
            missing.incrementAndGet();
            respond(exchange, 404, "missing");
        });
        server.createContext("/smartrecruiters/huge", exchange -> {
            huge.incrementAndGet();
            respond(exchange, 200, "{\"content\":[{\"id\":\"" + "x".repeat(2_000) + "\"}]}");
        });
        SmartRecruitersJobSource source = fixture(sr(100, 20, 100), properties(3, 1_024, 20, 5), ignored -> { });

        ProviderFailureException client = assertThrows(ProviderFailureException.class,
                () -> source.fetch(request("missing")));
        ProviderFailureException bytes = assertThrows(ProviderFailureException.class,
                () -> source.fetch(request("huge")));

        assertAll(
                () -> assertEquals(ProviderFailureException.Kind.CLIENT_ERROR, client.kind()),
                () -> assertEquals(1, missing.get()),
                () -> assertEquals(ProviderFailureException.Kind.PAYLOAD_LIMIT, bytes.kind()),
                () -> assertEquals(1, huge.get()));
    }

    @Test
    void circuitOpensPerBoardWithoutAffectingAnotherBoard() {
        AtomicInteger broken = new AtomicInteger();
        server.createContext("/smartrecruiters/broken", exchange -> {
            broken.incrementAndGet();
            respond(exchange, 503, "unavailable");
        });
        board("healthy", exchange -> respond(exchange, 200, page(0)));
        SmartRecruitersJobSource source = fixture(sr(100, 20, 100), properties(1, 8_192, 20, 2), ignored -> { });

        assertThrows(ProviderFailureException.class, () -> source.fetch(request("broken")));
        assertThrows(ProviderFailureException.class, () -> source.fetch(request("broken")));
        ProviderFailureException open = assertThrows(ProviderFailureException.class,
                () -> source.fetch(request("broken")));

        assertAll(
                () -> assertEquals(ProviderFailureException.Kind.CIRCUIT_OPEN, open.kind()),
                () -> assertEquals(2, broken.get()),
                () -> assertTrue(source.fetchWithMetadata(request("healthy")).complete()));
    }

    @Test
    void cancellationBeforeHttpAndDuringDetailFetchingStopsTheRun() {
        AtomicInteger lists = new AtomicInteger();
        AtomicBoolean valid = new AtomicBoolean(true);
        board("acme", exchange -> {
            lists.incrementAndGet();
            respond(exchange, 200, page(2, posting("1", null, "", null), posting("2", null, "", null)));
        });
        afterDetail = () -> valid.set(false);
        SmartRecruitersJobSource source = fixture(sr(100, 20, 100), properties(1, 8_192, 20, 5), ignored -> { });

        ProviderFailureException before = assertThrows(ProviderFailureException.class,
                () -> source.fetchWithMetadata(request("acme"), () -> false));
        ProviderFailureException during = assertThrows(ProviderFailureException.class,
                () -> source.fetchWithMetadata(request("acme"), valid::get));

        assertAll(
                () -> assertEquals(ProviderFailureException.Kind.CANCELLED, before.kind()),
                () -> assertEquals(ProviderFailureException.Kind.CANCELLED, during.kind()),
                () -> assertEquals(1, lists.get()),
                () -> assertEquals(Map.of("1", 1), detailCounts()));
    }

    @Test
    void malformedItemsAndMissingDetailsAreRejectedWhileValidSiblingsProceed() {
        board("mixed", exchange -> respond(exchange, 200, page(4, posting("1", null, "", null), "null",
                "{\"id\":\"../escape\",\"name\":\"Bad\"}", posting("gone", null, "", null))));
        SmartRecruitersJobSource source = fixture(sr(100, 20, 100), properties(1, 8_192, 20, 5), ignored -> { });

        JobSourceResult result = run(source, "mixed");

        assertAll(
                () -> assertEquals(List.of("1"), ids(result)),
                () -> assertEquals(3, result.rejected()),
                () -> assertTrue(result.complete()));
    }

    private void board(String board, Handler listHandler) {
        server.createContext("/smartrecruiters/" + board + "/postings", exchange -> {
            String[] path = exchange.getRequestURI().getPath().split("/");
            if (path.length == 5) {
                String id = path[4];
                detailRequests.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
                afterDetail.run();
                if ("gone".equals(id)) respond(exchange, 404, "missing");
                else respond(exchange, 200, detail(board, id));
                return;
            }
            listHandler.handle(exchange);
        });
    }

    private static String page(int total, String... postings) {
        return "{\"offset\":0,\"limit\":100,\"totalFound\":" + total + ",\"content\":[" + String.join(",", postings) + "]}";
    }

    private static String posting(String id, String released, String location, String employment) {
        return "{\"id\":\"" + id + "\",\"name\":\"Engineer " + id + "\","
                + (released == null ? "" : "\"releasedDate\":\"" + released + "\",")
                + "\"location\":{" + location + "}"
                + (employment == null ? "" : ",\"typeOfEmployment\":{\"id\":\"x\",\"label\":\"" + employment + "\"}")
                + "}";
    }

    private static String detail(String board, String id) {
        return "{\"id\":\"" + id + "\",\"postingUrl\":\"https://jobs.smartrecruiters.com/" + board + "/" + id
                + "-engineer\",\"applyUrl\":\"https://jobs.smartrecruiters.com/" + board + "/" + id
                + "-engineer?oga=true\",\"jobAd\":{\"sections\":{"
                + "\"companyDescription\":{\"title\":\"Company\",\"text\":\"<p>About us</p>\"},"
                + "\"jobDescription\":{\"title\":\"Job Description\",\"text\":\"<p>Build things for " + id + "</p>\"},"
                + "\"qualifications\":{\"title\":\"Qualifications\",\"text\":\"<ul><li>Java</li></ul>\"},"
                + "\"additionalInformation\":{\"title\":\"Additional Information\",\"text\":\"\"}}}}";
    }

    private Map<String, Integer> detailCounts() {
        Map<String, Integer> counts = new HashMap<>();
        detailRequests.forEach((id, count) -> counts.put(id, count.get()));
        return counts;
    }

    private JobSourceResult run(SmartRecruitersJobSource source, String board) {
        var result = source.fetchWithMetadata(request(board));
        return new JobSourceResult(result.jobs(), result.retries(), result.rejectedItems(), result.complete());
    }

    private static List<String> ids(JobSourceResult result) {
        return result.jobs().stream().map(ExternalJob::externalId).toList();
    }

    private SmartRecruitersJobSource fixture(SmartRecruitersProperties bounds, ProviderReliabilityProperties properties,
                                             ProviderRetryExecutor.Sleeper sleeper) {
        HttpClient transport = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(500);
        RestTemplate template = new RestTemplate(factory);
        template.getInterceptors().add(new ProviderPayloadLimitInterceptor(properties.maxResponseBytes()));
        return new SmartRecruitersJobSource(new ProviderHttpClient(template, clock),
                new ProviderRetryExecutor(properties, sleeper, bound -> 0), properties,
                new ProviderCircuitBreaker(properties, clock),
                new ProviderRequestLimiter(properties, clock, ignored -> { }), bounds, cache);
    }

    private static SmartRecruitersProperties sr(int pageSize, int maxPages, int maxDetails) {
        return new SmartRecruitersProperties(pageSize, maxPages, maxDetails, 30);
    }

    private ProviderReliabilityProperties properties(int attempts, int maxBytes, int maxItems, int circuitThreshold) {
        Map<String, String> urls = new HashMap<>();
        for (ProviderCatalog.Provider provider : ProviderCatalog.ofKind(ProviderCatalog.Kind.ATS_BOARD)) {
            urls.put(provider.name(), base + "/" + provider.name());
        }
        return new ProviderReliabilityProperties(500, 500, attempts, 200, 2_000, urls, maxBytes, maxItems,
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

    @FunctionalInterface
    private interface Handler { void handle(HttpExchange exchange) throws IOException; }

    private record JobSourceResult(List<ExternalJob> jobs, int retries, int rejected, boolean complete) { }

    private static final class InMemoryCache implements SmartRecruitersDetailCache {
        private final Map<String, Map.Entry<String, Detail>> entries = new ConcurrentHashMap<>();
        @Override public Optional<Detail> find(String board, String postingId, String releasedDate) {
            var entry = entries.get(board + ":" + postingId);
            return entry == null || !java.util.Objects.equals(entry.getKey(), releasedDate)
                    ? Optional.empty() : Optional.of(entry.getValue());
        }
        @Override public void save(String board, String postingId, String releasedDate, Detail detail) {
            entries.put(board + ":" + postingId, new java.util.AbstractMap.SimpleEntry<>(releasedDate, detail));
        }
    }
}
