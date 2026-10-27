package com.example.backend.geo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.example.backend.geo.application.Gazetteer;
import com.example.backend.integration.adzuna.AdzunaJobStore;
import com.example.backend.integration.aggregation.GeoReResolutionService;
import com.example.backend.job.api.dto.CreateJobRequest;
import com.example.backend.job.application.JobService;
import com.example.backend.job.domain.GeoResolution;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.shared.security.CurrentUserProvider;
import com.example.backend.user.domain.UserRole;
import com.example.backend.user.infrastructure.UserDocument;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** GEO-9A: {@code siteLabel} is stored on listing and top-level geo, and documents written before GEO-9 read back null. */
@SpringBootTest
class GeoSiteLabelPersistenceMongoIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final String RECRUITER = "geo9-recruiter@example.test";

    @Autowired AdzunaJobStore store;
    @Autowired GeoReResolutionService reResolution;
    @Autowired JobService jobs;
    @Autowired Gazetteer gazetteer;
    @Autowired MongoTemplate mongo;
    @MockitoBean CurrentUserProvider currentUser;

    @BeforeEach
    void clear() {
        mongo.remove(new Query(), JobDocument.class);
        mongo.remove(Query.query(Criteria.where("email").is(RECRUITER)), UserDocument.class);
        mongo.getCollection("ingestion_locks").deleteMany(new Document());
    }

    @Test
    void documentsWrittenBeforeGeo9ReadBackWithoutASiteLabel() {
        Document oldGeo = new Document("status", "PLACE").append("placeIds", List.of(bengaluru())).append("remote", false)
                .append("version", "3-old").append("localityIds", List.of());
        mongo.getCollection("jobs").insertOne(new Document("_id", "old-listing").append("title", "Engineer").append("active", true)
                .append("sourceListings", List.of(new Document("identity", "lever:x").append("active", true).append("geo", oldGeo))));
        mongo.getCollection("jobs").insertOne(new Document("_id", "old-manual").append("title", "Engineer").append("active", true)
                .append("geo", oldGeo));

        GeoResolution listing = mongo.findById("old-listing", JobDocument.class).getSourceListings().get(0).getGeo();
        GeoResolution manual = mongo.findById("old-manual", JobDocument.class).getGeo();
        assertAll(
                () -> assertNull(listing.siteLabel()),
                () -> assertNull(manual.siteLabel()),
                () -> assertEquals(List.of(bengaluru()), manual.placeIds()));
    }

    @Test
    void importedListingAndManualJobStoreTheSiteLabel() {
        JobDocument imported = new JobDocument();
        imported.setSource("lever");
        imported.setExternalId("board:one");
        imported.setTitle("Engineer");
        imported.setDescription("Requirements: Java.");
        imported.setCompany("Acme");
        imported.setLocation("Bengaluru- We Work");
        imported.setApplicationUrl("https://lever.test/board-one");
        imported.setSourceUrl(imported.getApplicationUrl());
        imported.setFingerprint("fp-geo9");
        imported.setActive(true);
        store.upsert(imported, NOW, "board");

        JobDocument stored = mongo.findAll(JobDocument.class).get(0);
        assertEquals("We Work", stored.getSourceListings().get(0).getGeo().siteLabel());
        Document raw = mongo.getCollection("jobs").find().first();
        assertEquals("We Work", raw.getList("sourceListings", Document.class).get(0).get("geo", Document.class)
                .getString("siteLabel"));

        UserDocument recruiter = new UserDocument();
        recruiter.setEmail(RECRUITER);
        recruiter.setRole(UserRole.RECRUITER);
        mongo.insert(recruiter);
        when(currentUser.email()).thenReturn(RECRUITER);
        String id = jobs.create(new CreateJobRequest("Engineer", "Build things", "Mumbai, Urmi", "Acme", 10, 1)).id();
        assertEquals("Urmi", mongo.findById(id, JobDocument.class).getGeo().siteLabel());
    }

    @Test
    void reResolutionWritesTheSiteLabel() {
        Document old = new Document("status", "UNRESOLVED").append("placeIds", List.of()).append("remote", false)
                .append("version", "3-old");
        mongo.getCollection("jobs").insertOne(new Document("_id", "listed").append("title", "Engineer").append("active", true)
                .append("location", "Chennai, Kochar Jade").append("sourceListings", new ArrayList<>(List.of(
                        new Document("identity", "lever:a").append("provider", "lever").append("active", true)
                                .append("rawLocation", "Chennai, Kochar Jade").append("geo", old)))));
        mongo.getCollection("jobs").insertOne(new Document("_id", "manual").append("title", "Engineer").append("active", true)
                .append("location", "Gurugram, Star Center").append("geo", old));

        assertEquals(2, reResolution.runOnce().updated());

        assertEquals("Kochar Jade", mongo.findById("listed", JobDocument.class).getSourceListings().get(0).getGeo().siteLabel());
        assertEquals("Star Center", mongo.findById("manual", JobDocument.class).getGeo().siteLabel());
    }

    private String bengaluru() { return gazetteer.primaryMatches("bengaluru").get(0).id(); }
}
