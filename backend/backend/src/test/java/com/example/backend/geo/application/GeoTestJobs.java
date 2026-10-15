package com.example.backend.geo.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.backend.geo.domain.Place;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bson.Document;

/** Job documents for geo integration tests, shaped like ingested jobs (listings with geo, or top-level geo). */
final class GeoTestJobs {
    private GeoTestJobs() { }

    static String placeId(Gazetteer gazetteer, String name) {
        List<Place> matches = gazetteer.primaryMatches(Gazetteer.key(name));
        assertEquals(1, matches.size(), name);
        return matches.get(0).id();
    }

    static Document job(String id, String company, int day, String... placeIds) {
        return new Document("_id", id).append("title", "Engineer " + id).append("description", "about " + id)
                .append("company", company).append("location", "Somewhere").append("source", "adzuna")
                .append("active", true)
                .append("createdAt", Date.from(Instant.parse("2026-10-01T00:00:00Z").plusSeconds(86_400L * day)))
                .append("sourceListings", new ArrayList<>(List.of(new Document("identity", "adzuna:" + id)
                        .append("active", true).append("geo", new Document("status", placeIds.length == 0 ? "REMOTE" : "PLACE")
                                .append("placeIds", List.of(placeIds)).append("remote", false).append("version", "test")))));
    }
}
