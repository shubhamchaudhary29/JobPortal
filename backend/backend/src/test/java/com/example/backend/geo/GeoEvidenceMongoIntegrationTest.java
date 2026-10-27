package com.example.backend.geo;

import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.geo.application.Gazetteer;
import com.example.backend.geo.application.LocalityIndex;
import com.example.backend.integration.adzuna.AdzunaJobStore;
import com.example.backend.integration.adzuna.UpsertOutcome;
import com.example.backend.integration.aggregation.GeoReResolutionService;
import com.example.backend.job.domain.GeoResolution;
import com.example.backend.job.domain.LocationHint;
import com.example.backend.job.infrastructure.JobDocument;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/**
 * GEO-10A/C on every geo write path: the Adzuna hint on insert and on update (stored {@code rawArea}, an UPDATED
 * outcome when a point arrives), re-resolution from the stored area and point, and the description as evidence.
 */
@SpringBootTest
class GeoEvidenceMongoIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final List<String> WHITEFIELD_AREA = List.of("India", "Karnataka", "Bangalore", "Whitefield");

    @Autowired AdzunaJobStore store;
    @Autowired GeoReResolutionService reResolution;
    @Autowired Gazetteer gazetteer;
    @Autowired MongoTemplate mongo;

    @BeforeEach
    void clear() {
        mongo.remove(new Query(), JobDocument.class);
        mongo.getCollection("ingestion_locks").deleteMany(new Document());
    }

    @Test
    void adzunaAreaGivesTheLocalityAndIsStoredForReResolution() {
        assertEquals(UpsertOutcome.INSERTED, store.upsert(adzuna("a1", "Bangalore", new LocationHint(WHITEFIELD_AREA, null, null),
                "desc"), NOW));

        GeoResolution geo = listingGeo();
        assertEquals(List.of(whitefield().id()), geo.localityIds());
        assertEquals(WHITEFIELD_AREA, rawListing().getList("rawArea", String.class));
    }

    @Test
    void anExistingListingThatGainsAPointIsUpdatedAndReResolved() {
        assertEquals(UpsertOutcome.INSERTED, store.upsert(adzuna("a1", "Bangalore", null, "desc"), NOW));
        assertNull(listingGeo().point());
        assertTrue(listingGeo().localityIds().isEmpty());

        LocalityIndex.Locality whitefield = whitefield();
        LocationHint point = new LocationHint(List.of(), whitefield.lat(), whitefield.lng());
        assertEquals(UpsertOutcome.UPDATED, store.upsert(adzuna("a1", "Bangalore", point, "desc"), NOW.plusHours(1)),
                "same content, but the stored geo had no point");
        assertNotNull(listingGeo().point());
        assertEquals(List.of(whitefield.id()), listingGeo().localityIds());
        assertEquals(UpsertOutcome.UNCHANGED, store.upsert(adzuna("a1", "Bangalore", point, "desc"), NOW.plusHours(2)),
                "once stored, the same point is no change");
    }

    @Test
    void reResolutionUsesTheStoredAreaAndTheDescription() {
        Document old = new Document("status", "PLACE").append("placeIds", List.of(bengaluru())).append("remote", false)
                .append("version", "4-old");
        mongo.getCollection("jobs").insertOne(new Document("_id", "listed").append("title", "Engineer").append("active", true)
                .append("location", "Bangalore").append("sourceListings", new ArrayList<>(List.of(
                        new Document("identity", "adzuna:a").append("provider", "adzuna").append("active", true)
                                .append("rawLocation", "Bangalore").append("rawArea", WHITEFIELD_AREA).append("geo", old)))));
        mongo.getCollection("jobs").insertOne(new Document("_id", "manual").append("title", "Engineer").append("active", true)
                .append("location", "Bengaluru").append("description", "<p>Work location: Marathahalli, Bengaluru</p>")
                .append("geo", old));

        assertEquals(2, reResolution.runOnce().updated());

        assertEquals(List.of(whitefield().id()),
                mongo.findById("listed", JobDocument.class).getSourceListings().get(0).getGeo().localityIds());
        LocalityIndex.Locality marathahalli = gazetteer.localityMatches("marathahalli", bengaluru()).get(0);
        assertEquals(List.of(marathahalli.id()), mongo.findById("manual", JobDocument.class).getGeo().localityIds());
    }

    @Test
    void importedDescriptionIsEvidenceButNeverAPlace() {
        store.upsert(adzuna("a1", "Bengaluru", null, "Office address: Whitefield, Bengaluru"), NOW);
        assertEquals(List.of(whitefield().id()), listingGeo().localityIds());

        mongo.remove(new Query(), JobDocument.class);
        store.upsert(adzuna("a2", "India", null, "Office address: Whitefield, Bengaluru"), NOW);
        assertTrue(listingGeo().placeIds().isEmpty(), "a description never names the city");
    }

    @Test
    void documentsWrittenBeforeGeo10ReadBackWithoutRawArea() {
        mongo.getCollection("jobs").insertOne(new Document("_id", "old").append("title", "Engineer").append("active", true)
                .append("sourceListings", List.of(new Document("identity", "adzuna:x").append("active", true))));
        assertNull(mongo.findById("old", JobDocument.class).getSourceListings().get(0).getRawArea());
    }

    private JobDocument adzuna(String id, String location, LocationHint hint, String description) {
        JobDocument job = new JobDocument();
        job.setSource("adzuna");
        job.setExternalId(id);
        job.setTitle("Engineer");
        job.setDescription(description);
        job.setCompany("Acme");
        job.setLocation(location);
        job.setApplicationUrl("https://www.adzuna.in/land/ad/" + id);
        job.setSourceUrl(job.getApplicationUrl());
        job.setFingerprint("fp-" + id);
        job.setActive(true);
        job.setLocationHint(hint);
        return job;
    }

    private GeoResolution listingGeo() { return mongo.findAll(JobDocument.class).get(0).getSourceListings().get(0).getGeo(); }

    private Document rawListing() {
        return mongo.getCollection("jobs").find().first().getList("sourceListings", Document.class).get(0);
    }

    private String bengaluru() { return gazetteer.primaryMatches("bengaluru").get(0).id(); }

    private LocalityIndex.Locality whitefield() { return gazetteer.localityMatches("whitefield", bengaluru()).get(0); }
}
