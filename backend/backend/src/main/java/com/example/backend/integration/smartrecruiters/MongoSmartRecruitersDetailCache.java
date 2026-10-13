package com.example.backend.integration.smartrecruiters;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** Entries expire through the {@code smartrecruiters_detail_retention_ttl} index after the retention period. */
@Component
class MongoSmartRecruitersDetailCache implements SmartRecruitersDetailCache {
    static final String COLLECTION = "smartrecruiters_posting_details";
    private final MongoTemplate mongo;
    private final Clock clock;
    private final SmartRecruitersProperties properties;

    MongoSmartRecruitersDetailCache(MongoTemplate mongo, Clock clock, SmartRecruitersProperties properties) {
        this.mongo = mongo;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public Optional<Detail> find(String board, String postingId, String releasedDate) {
        Document cached = mongo.findOne(Query.query(Criteria.where("_id").is(key(board, postingId))),
                Document.class, COLLECTION);
        if (cached == null || !Objects.equals(cached.getString("releasedDate"), releasedDate)) return Optional.empty();
        Object expiresAt = cached.get("expiresAt");
        if (expiresAt instanceof java.util.Date date && !date.toInstant().isAfter(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(new Detail(cached.getString("description"), cached.getString("postingUrl"),
                cached.getString("applyUrl")));
    }

    @Override
    public void save(String board, String postingId, String releasedDate, Detail detail) {
        Instant now = clock.instant();
        mongo.upsert(Query.query(Criteria.where("_id").is(key(board, postingId))), new Update()
                .set("releasedDate", releasedDate)
                .set("description", detail.description())
                .set("postingUrl", detail.postingUrl())
                .set("applyUrl", detail.applyUrl())
                .set("fetchedAt", now)
                .set("expiresAt", now.plus(properties.detailRetentionDays(), ChronoUnit.DAYS)), COLLECTION);
    }

    static String key(String board, String postingId) {
        return board.toLowerCase(Locale.ROOT) + ":" + postingId;
    }
}
