package com.example.backend.geo.application;

import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.geo.infrastructure.UnresolvedLocationRecorder;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

@SpringBootTest
class GeoCoverageMongoIntegrationTest {
    @Autowired GeoResolver resolver;
    @Autowired MongoTemplate mongo;
    @Autowired java.time.Clock clock;

    @BeforeEach
    void clear() {
        mongo.getCollection("jobs").deleteMany(new Document());
        mongo.getCollection(UnresolvedLocationRecorder.COLLECTION).deleteMany(new Document());
    }

    @Test
    void countsEachActiveJobByItsBestActiveListingStatus() {
        String current = resolver.version();
        int id = 0;
        for (String status : List.of("PLACE", "REMOTE", "STATE", "COUNTRY", "FOREIGN")) {
            insert(job("job-" + id++, true, listing(true, status, current)));
        }
        insert(job("unresolved-stale", true, listing(true, "UNRESOLVED", "0-old")));
        insert(job("manual-empty", true).append("geo", geo("EMPTY", current)));
        insert(job("manual-unprocessed", true));
        // Only the inactive listing has a place, so this job must count as COUNTRY, not PLACE.
        insert(job("multi", true, listing(false, "PLACE", current), listing(true, "COUNTRY", current)));
        insert(job("inactive-job", false, listing(true, "PLACE", current)));
        mongo.getCollection(UnresolvedLocationRecorder.COLLECTION).insertMany(List.of(
                new Document("_id", "multiple locations").append("sample", "Multiple Locations").append("count", 5)
                        .append("lastSeenAt", new Date()),
                new Document("_id", "hq").append("sample", "HQ").append("count", 9).append("lastSeenAt", new Date())));

        GeoCoverageService.Coverage coverage = new GeoCoverageService(mongo, resolver, clock).coverage();

        Map<String, Long> expected = new java.util.LinkedHashMap<>();
        expected.put("PLACE", 1L); expected.put("REMOTE", 1L); expected.put("STATE", 1L); expected.put("COUNTRY", 2L);
        expected.put("FOREIGN", 1L); expected.put("UNRESOLVED", 1L); expected.put("EMPTY", 1L); expected.put("UNPROCESSED", 1L);
        assertAll(
                () -> assertEquals(current, coverage.resolverVersion()),
                () -> assertEquals(9, coverage.activeJobs()),
                () -> assertEquals(expected, coverage.byStatus()),
                () -> assertEquals(List.copyOf(expected.keySet()), List.copyOf(coverage.byStatus().keySet())),
                () -> assertEquals(11.1, coverage.placeResolvedPercent()),
                () -> assertEquals(2, coverage.staleJobs()),
                () -> assertEquals(List.of("HQ", "Multiple Locations"),
                        coverage.topUnresolved().stream().map(GeoCoverageService.UnresolvedLocation::location).toList()),
                () -> assertEquals(9, coverage.topUnresolved().get(0).count()));
    }

    @Test
    void responseIsCachedForAMinute() {
        GeoCoverageService service = new GeoCoverageService(mongo, resolver, clock);
        assertEquals(0, service.coverage().activeJobs());
        insert(job("job-1", true, listing(true, "PLACE", resolver.version())));
        assertEquals(0, service.coverage().activeJobs(), "second call within the TTL is served from memory");
        assertEquals(1, new GeoCoverageService(mongo, resolver, clock).coverage().activeJobs());
    }

    private void insert(Document job) { mongo.getCollection("jobs").insertOne(job); }

    private static Document job(String id, boolean active, Document... listings) {
        Document job = new Document("_id", id).append("title", "Engineer").append("active", active);
        if (listings.length > 0) job.append("sourceListings", new ArrayList<>(List.of(listings)));
        return job;
    }

    private static Document listing(boolean active, String status, String version) {
        return new Document("identity", "provider:" + status + active).append("active", active)
                .append("geo", geo(status, version));
    }

    private static Document geo(String status, String version) {
        return new Document("status", status).append("placeIds", List.of()).append("remote", false).append("version", version);
    }
}
