package com.example.backend.geo.application;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

/** An office change schedules a snapshot rebuild on the snapshot executor, long before the fixed schedule. */
@SpringBootTest(properties = {"geo.snapshot.enabled=true", "geo.snapshot.refresh-ms=3600000",
        "geo.snapshot.office-refresh-delay-ms=200"})
class OfficeSnapshotRefreshIntegrationTest {
    @Autowired CompanyOfficeService offices;
    @Autowired MapSnapshotService snapshots;
    @Autowired Gazetteer gazetteer;
    @Autowired MongoTemplate mongo;

    @Test
    void creatingAnOfficeRefreshesTheSnapshotAsynchronously() throws Exception {
        mongo.getCollection("company_offices").deleteMany(new Document());
        mongo.getCollection("jobs").deleteMany(new Document());
        String blr = GeoTestJobs.placeId(gazetteer, "Bengaluru");
        mongo.getCollection("jobs").insertOne(GeoTestJobs.job("r1", "Refreshco", 1, blr));
        offices.loadIndex();
        waitFor(() -> snapshots.current().isPresent());
        String before = snapshots.current().orElseThrow().snapshot().version();

        offices.create("Refreshco", "HQ", 12.97, 77.6, blr, "admin");

        waitFor(() -> snapshots.current().orElseThrow().snapshot().offices().stream()
                .anyMatch(office -> office.company().equals("Refreshco")));
        assertTrue(!snapshots.current().orElseThrow().snapshot().version().equals(before));
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("condition not met in time");
            Thread.sleep(50);
        }
    }
}
