package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.geo.application.GeoResolver;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

@SpringBootTest
class GeoReResolutionMongoIntegrationTest {
    @Autowired GeoReResolutionService service;
    @Autowired DistributedLeaseLock locks;
    @Autowired GeoResolver resolver;
    @Autowired MongoTemplate mongo;

    @BeforeEach
    void clear() {
        // Raw documents keep fixtures exact: saving JobDocument would run the geo persistence callback.
        mongo.getCollection("jobs").deleteMany(new Document());
        mongo.getCollection("ingestion_locks").deleteMany(new Document());
    }

    @Test
    void legacyListingIsSeededFromCanonicalLocationOnceAndThenLeftAlone() {
        insert(job("job-1", true, "Pune", listing("lever:one", null, geo("0-old"))));

        GeoReResolutionService.Result first = service.runOnce();
        Document listing = listings("job-1").get(0);
        Document geo = listing.get("geo", Document.class);
        assertAll(
                () -> assertTrue(first.acquired()),
                () -> assertEquals(1, first.scanned()),
                () -> assertEquals(1, first.updated()),
                () -> assertEquals(0, first.remainingEstimate()),
                () -> assertEquals("Pune", listing.getString("rawLocation")),
                () -> assertTrue(listing.getBoolean("rawLocationInferred")),
                () -> assertEquals(resolver.version(), geo.getString("version")),
                () -> assertEquals("PLACE", geo.getString("status")));

        GeoReResolutionService.Result second = service.runOnce();
        assertEquals(0, second.scanned());
        assertEquals(0, second.updated());
    }

    @Test
    void providerLocationIsReResolvedWithoutBeingMarkedInferred() {
        insert(job("job-1", true, "Canonical City", listing("lever:one", "Chennai, TN", geo("0-old"))));

        service.runOnce();

        Document listing = listings("job-1").get(0);
        assertEquals("Chennai, TN", listing.getString("rawLocation"));
        assertNull(listing.get("rawLocationInferred"));
        assertEquals(resolver.resolve("Chennai, TN", null).placeIds(),
                listing.get("geo", Document.class).getList("placeIds", String.class));
    }

    @Test
    void currentListingsAreNeverOverwritten() {
        Document sentinel = geo(resolver.version()).append("status", "FOREIGN").append("placeIds", List.of("sentinel"));
        insert(job("job-1", true, "Pune", listing("lever:current", "Pune", sentinel),
                listing("ashby:stale", "Kochi", geo("0-old"))));

        GeoReResolutionService.Result result = service.runOnce();

        List<Document> listings = listings("job-1");
        assertEquals(1, result.updated());
        assertEquals(sentinel, listings.get(0).get("geo", Document.class));
        assertEquals(resolver.version(), listings.get(1).get("geo", Document.class).getString("version"));
    }

    @Test
    void listingLessJobsResolveTopLevelGeo() {
        insert(job("manual-1", true, "Hyderabad, Telangana").append("geo", geo("0-old")));

        service.runOnce();

        Document geo = mongo.getCollection("jobs").find(new Document("_id", "manual-1")).first().get("geo", Document.class);
        assertEquals(resolver.version(), geo.getString("version"));
        assertEquals("PLACE", geo.getString("status"));
    }

    @Test
    void inactiveJobsAreNotProcessed() {
        insert(job("job-1", false, "Pune", listing("lever:one", null, geo("0-old"))));

        GeoReResolutionService.Result result = service.runOnce();

        assertEquals(0, result.scanned());
        assertEquals("0-old", listings("job-1").get(0).get("geo", Document.class).getString("version"));
    }

    @Test
    void heldLeaseMeansNoWork() {
        insert(job("job-1", true, "Pune", listing("lever:one", null, geo("0-old"))));
        String owner = locks.acquire(GeoReResolutionService.LOCK, 60_000);
        try {
            GeoReResolutionService.Result result = service.runOnce();
            assertFalse(result.acquired());
            assertEquals(0, result.updated());
            assertEquals("0-old", listings("job-1").get(0).get("geo", Document.class).getString("version"));
        } finally {
            locks.release(GeoReResolutionService.LOCK, owner);
        }
    }

    private void insert(Document job) { mongo.getCollection("jobs").insertOne(job); }

    private List<Document> listings(String id) {
        return mongo.getCollection("jobs").find(new Document("_id", id)).first().getList("sourceListings", Document.class);
    }

    private static Document job(String id, boolean active, String location, Document... listings) {
        Document job = new Document("_id", id).append("title", "Engineer").append("location", location)
                .append("active", active);
        if (listings.length > 0) job.append("sourceListings", new ArrayList<>(List.of(listings)));
        return job;
    }

    private static Document listing(String identity, String rawLocation, Document geo) {
        Document listing = new Document("identity", identity).append("provider", identity.split(":")[0])
                .append("active", true).append("geo", geo);
        if (rawLocation != null) listing.append("rawLocation", rawLocation);
        return listing;
    }

    private static Document geo(String version) {
        return new Document("status", "UNRESOLVED").append("placeIds", List.of()).append("remote", false)
                .append("version", version);
    }
}
