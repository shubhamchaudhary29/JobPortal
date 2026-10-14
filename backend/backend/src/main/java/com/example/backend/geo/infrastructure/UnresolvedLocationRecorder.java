package com.example.backend.geo.infrastructure;

import com.example.backend.geo.application.Gazetteer;
import java.time.Clock;
import java.time.Instant;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** Counts location strings the resolver could not place, so aliases can be added where they matter most. */
@Component
public class UnresolvedLocationRecorder {
    public static final String COLLECTION = "unresolved_locations";
    static final int MAX_LENGTH = 200;
    private final MongoTemplate mongo;
    private final Clock clock;

    public UnresolvedLocationRecorder(MongoTemplate mongo, Clock clock) {
        this.mongo = mongo;
        this.clock = clock;
    }

    public void record(String rawLocation, String provider) {
        String key = truncate(Gazetteer.key(rawLocation));
        if (key.isEmpty()) return;
        Instant now = clock.instant();
        Update update = new Update().inc("count", 1).set("lastSeenAt", now)
                .set("sample", truncate(rawLocation.trim())).setOnInsert("firstSeenAt", now);
        if (provider != null) update.addToSet("providers", provider);
        mongo.upsert(Query.query(Criteria.where("_id").is(key)), update, COLLECTION);
    }

    private static String truncate(String value) {
        return value.length() > MAX_LENGTH ? value.substring(0, MAX_LENGTH) : value;
    }
}
