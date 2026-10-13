package com.example.backend.integration.search;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@SpringBootTest
class ProviderRequestBudgetMongoIntegrationTest {
    @Autowired MongoTemplate mongo;

    @BeforeEach
    void clear() { mongo.remove(new Query(), ProviderRequestBudget.COLLECTION); }

    @Test
    void grantsExactlyTheMonthlyLimitAndThenRefuses() {
        ProviderRequestBudget budget = new ProviderRequestBudget(mongo, fixed("2026-10-13T10:00:00Z"));
        List<Boolean> grants = new ArrayList<>();
        for (int request = 0; request < 5; request++) grants.add(budget.tryConsume("adzuna", 3));
        assertEquals(List.of(true, true, true, false, false), grants);
        assertEquals(3, budget.used("adzuna"));
        assertEquals(0, budget.used("jooble"));
        assertTrue(budget.tryConsume("jooble", 3), "budgets are per provider");
        assertFalse(budget.tryConsume("adzuna", 0));
    }

    @Test
    void aNewUtcMonthStartsAFreshBudgetAndKeepsThePreviousCounter() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-30T23:59:59Z"));
        ProviderRequestBudget budget = new ProviderRequestBudget(mongo, clock);
        assertTrue(budget.tryConsume("adzuna", 1));
        assertFalse(budget.tryConsume("adzuna", 1));

        clock.now = Instant.parse("2026-10-01T00:00:00Z");

        assertTrue(budget.tryConsume("adzuna", 1));
        assertFalse(budget.tryConsume("adzuna", 1));
        Document september = mongo.findById("adzuna:2026-09", Document.class, ProviderRequestBudget.COLLECTION);
        Document october = mongo.findById("adzuna:2026-10", Document.class, ProviderRequestBudget.COLLECTION);
        assertAll(
                () -> assertEquals(1, september.getInteger("used")),
                () -> assertEquals(1, october.getInteger("used")),
                () -> assertEquals("2026-10", october.getString("month")),
                () -> assertEquals(Instant.parse("2027-11-01T00:00:00Z"), october.getDate("expiresAt").toInstant()));
    }

    @Test
    void twoInstancesRacingForTheSameBudgetNeverExceedIt() throws Exception {
        Clock clock = fixed("2026-10-13T10:00:00Z");
        ProviderRequestBudget first = new ProviderRequestBudget(mongo, clock);
        ProviderRequestBudget second = new ProviderRequestBudget(mongo, clock);
        List<Callable<Boolean>> attempts = new ArrayList<>();
        for (int attempt = 0; attempt < 120; attempt++) {
            ProviderRequestBudget instance = attempt % 2 == 0 ? first : second;
            attempts.add(() -> instance.tryConsume("adzuna", 50));
        }
        var pool = Executors.newFixedThreadPool(12);
        List<Future<Boolean>> results = pool.invokeAll(attempts);
        pool.shutdown();
        long granted = 0;
        for (Future<Boolean> result : results) if (result.get()) granted++;
        assertEquals(50, granted);
        assertEquals(50, first.used("adzuna"));
    }

    @Test
    void retentionTtlIndexIsCreatedAtStartup() {
        assertTrue(mongo.indexOps(ProviderRequestBudget.COLLECTION).getIndexInfo().stream()
                .anyMatch(index -> "provider_request_budget_retention_ttl".equals(index.getName())));
    }

    private static Clock fixed(String instant) { return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC); }

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
