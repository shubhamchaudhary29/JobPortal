package com.example.backend.integration.adzuna;

import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.integration.aggregation.ImportedJobLifecycleService;
import com.example.backend.integration.search.MongoQueryRotation;
import com.example.backend.integration.search.RequestBudget;
import com.example.backend.job.infrastructure.ImportedSourceListing;
import com.example.backend.job.infrastructure.JobDocument;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/** Keyword × city rotation stays inside the monthly budget, covers every query, and never fakes a miss. */
@SpringBootTest(properties = "job-aggregation.lifecycle.missing-threshold=2")
class AdzunaRotationMongoIntegrationTest {
    private static final String KEYWORDS = "java developer,python developer,react developer,data analyst,backend engineer,"
            + "frontend developer,full stack developer,machine learning engineer,software engineer,devops engineer,"
            + "product manager,business analyst";
    private static final String CITIES = "Bengaluru,Delhi,Gurugram,Noida,Mumbai,Pune,Hyderabad,Chennai,Kolkata,Ahmedabad";
    @Autowired AdzunaJobStore store;
    @Autowired ImportedJobLifecycleService lifecycle;
    @Autowired MongoTemplate mongo;
    @Autowired MongoQueryRotation rotation;

    @BeforeEach
    void clear() {
        mongo.remove(new Query(), JobDocument.class);
        mongo.getCollection("search_query_rotation").deleteMany(new org.bson.Document());
    }

    @Test
    void aSimulatedMonthOfDefaultRunsCoversEveryKeywordCityAndPageWithinTheBudget() {
        AdzunaProperties properties = properties(KEYWORDS, CITIES, 2, 0);
        Map<String, Integer> coverage = new HashMap<>();
        AdzunaClient client = new AdzunaClient() {
            @Override public AdzunaResponse fetchPage(String keyword, int page) { return fetchPage(keyword, null, page); }
            @Override public AdzunaResponse fetchPage(String keyword, String location, int page) {
                coverage.merge(keyword + "|" + location + "|" + page, 1, Integer::sum);
                return new AdzunaResponse(List.of());
            }
        };
        AtomicInteger used = new AtomicInteger();
        RequestBudget monthly = (provider, limit) -> used.get() < limit && used.incrementAndGet() > 0;
        AdzunaService service = service(client, properties, monthly);

        for (int run = 0; run < 60; run++) assertFalse(service.sync().budgetExhausted(), "run " + run);

        int combinations = 12 * 11 * 2;
        assertAll(
                () -> assertEquals(33, properties.requestsPerRun()),
                () -> assertTrue(33 * 60 <= properties.monthlyRequestBudget(), "projected monthly requests exceed budget"),
                () -> assertEquals(combinations, coverage.size()),
                () -> assertTrue(coverage.containsKey("product manager|Pune|2")),
                () -> assertTrue(coverage.containsKey("java developer|null|1")),
                () -> assertTrue(coverage.values().stream().allMatch(count -> count >= 6), coverage.toString()),
                () -> assertTrue(used.get() <= 2_000, "used " + used.get()));
    }

    @Test
    void jobsReturnedOnlyByRotatedQueriesAreNeverDeactivatedByRotation() {
        AdzunaProperties properties = properties("k1,k2,k3", "Pune,Delhi", 1, 2);
        AtomicBoolean stillListed = new AtomicBoolean(true);
        AtomicBoolean failNext = new AtomicBoolean(false);
        AdzunaClient client = new AdzunaClient() {
            @Override public AdzunaResponse fetchPage(String keyword, int page) { return fetchPage(keyword, null, page); }
            @Override public AdzunaResponse fetchPage(String keyword, String location, int page) {
                if (failNext.getAndSet(false)) throw new AdzunaProviderException("HTTP 503", false, null);
                if ("k1".equals(keyword) && "Pune".equals(location)) return response("pune-only", "Backend Engineer", "Pune");
                if ("k3".equals(keyword) && location == null && stillListed.get()) {
                    return response("country-wide", "Data Analyst", "India");
                }
                return new AdzunaResponse(List.of());
            }
        };
        AdzunaService service = service(client, properties, RequestBudget.unlimited());

        for (int run = 0; run < 30; run++) service.sync();
        assertTrue(job("pune-only").getActive());
        assertEquals(0, listing("pune-only").getConsecutiveMissingRuns());
        assertTrue(job("country-wide").getActive());

        stillListed.set(false);
        for (int run = 0; run < 5; run++) service.sync();      // one complete cycle without it: first miss
        assertEquals(1, listing("country-wide").getConsecutiveMissingRuns());
        assertTrue(job("country-wide").getActive());

        failNext.set(true);
        for (int run = 0; run < 5; run++) service.sync();      // a cycle with a failure never advances lifecycle
        assertEquals(1, listing("country-wide").getConsecutiveMissingRuns());

        for (int run = 0; run < 5; run++) service.sync();      // second complete miss reaches the threshold
        assertFalse(job("country-wide").getActive());
        assertTrue(job("pune-only").getActive(), "a job only one city query returns survives every cycle");
        assertEquals(0, listing("pune-only").getConsecutiveMissingRuns());
    }

    private AdzunaService service(AdzunaClient client, AdzunaProperties properties, RequestBudget budget) {
        return new AdzunaService(new AdzunaJobSource(client), store, properties, new AdzunaCircuitBreaker(properties),
                new AdzunaSyncMetrics(), lifecycle, Clock.systemUTC(), ignored -> { }, bound -> 0, budget, rotation);
    }

    private static AdzunaProperties properties(String keywords, String cities, int pages, int requestsPerRun) {
        return new AdzunaProperties("id-not-secret", "key-not-secret", 1, 1, 1, 1, 3, 100, pages, 20, keywords,
                2_000, cities, requestsPerRun, 43_200_000L);
    }

    private static AdzunaResponse response(String id, String title, String location) {
        return new AdzunaResponse(List.of(new AdzunaResponse.AdzunaJob(id, title, "Build", "https://www.adzuna.in/land/ad/" + id,
                null, new AdzunaResponse.Company("Acme"), new AdzunaResponse.Location(location))));
    }

    private JobDocument job(String externalId) {
        return mongo.findAll(JobDocument.class).stream()
                .filter(job -> job.getSourceListings().stream().anyMatch(listing -> externalId.equals(listing.getExternalId())))
                .findFirst().orElseThrow();
    }

    private ImportedSourceListing listing(String externalId) {
        return job(externalId).getSourceListings().stream().filter(item -> externalId.equals(item.getExternalId()))
                .findFirst().orElseThrow();
    }
}
