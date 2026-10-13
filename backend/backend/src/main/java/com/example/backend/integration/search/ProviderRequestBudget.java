package com.example.backend.integration.search;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Per-provider monthly request counters in {@code provider_request_budgets}, one document per provider and UTC
 * month. A conditional {@code $inc} grants a request only while {@code used < limit}, so concurrent runs on any
 * number of instances can never exceed the limit. A new month starts a new document.
 */
@Component
public class ProviderRequestBudget implements RequestBudget {
    static final String COLLECTION = "provider_request_budgets";
    private final MongoTemplate mongo;
    private final Clock clock;

    public ProviderRequestBudget(MongoTemplate mongo, Clock clock) {
        this.mongo = mongo;
        this.clock = clock;
    }

    @Override
    public boolean tryConsume(String provider, int monthlyLimit) {
        if (monthlyLimit < 1) return false;
        Instant now = clock.instant();
        YearMonth month = YearMonth.from(now.atZone(ZoneOffset.UTC));
        String id = provider + ":" + month;
        try {
            mongo.upsert(Query.query(Criteria.where("_id").is(id)), new Update()
                    .setOnInsert("provider", provider).setOnInsert("month", month.toString()).setOnInsert("used", 0)
                    .setOnInsert("expiresAt", month.plusMonths(13).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant()),
                    COLLECTION);
        } catch (DuplicateKeyException concurrentInsert) {
            // Another instance created this month's counter first.
        }
        return mongo.updateFirst(Query.query(Criteria.where("_id").is(id).and("used").lt(monthlyLimit)),
                new Update().inc("used", 1).set("updatedAt", now.truncatedTo(ChronoUnit.MILLIS)), COLLECTION)
                .getModifiedCount() == 1;
    }

    /** Requests reserved for the provider in the current UTC month. */
    public long used(String provider) {
        String id = provider + ":" + YearMonth.from(clock.instant().atZone(ZoneOffset.UTC));
        org.bson.Document counter = mongo.findById(id, org.bson.Document.class, COLLECTION);
        return counter == null ? 0 : ((Number) counter.get("used")).longValue();
    }
}
