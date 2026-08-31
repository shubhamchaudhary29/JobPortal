package com.example.backend.matching.infrastructure;

import com.example.backend.integration.adzuna.AdzunaClient;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.matching.application.JobFeatureService;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class JobFeatureStoreMongoIntegrationTest {
    @Autowired MongoTemplate mongo;
    @Autowired JobFeatureService featureService;
    @Autowired JobFeatureStore featureStore;
    @MockitoBean AdzunaClient adzunaClient;

    @BeforeEach
    void clean() {
        mongo.remove(new Query(), JobDocument.class);
    }

    @Test
    void lazyExtractionUpdatesOnlyFeaturesAndPreservesConcurrentLifecycleState() {
        insertLegacy("legacy-1", "Requirements: Java and Docker. 2+ years experience.");
        JobDocument snapshot = mongo.findById("legacy-1", JobDocument.class);
        assertNotNull(snapshot);
        assertTrue(featureService.prepare(snapshot));

        List<Document> listings = List.of(new Document("identity", "adzuna:42")
                .append("provider", "adzuna").append("externalId", "42").append("active", false));
        mongo.updateFirst(Query.query(Criteria.where("_id").is("legacy-1")), new Update()
                .set("active", false).set("inactiveReason", "MISSING_FROM_SOURCE")
                .set("sourceListings", listings), JobDocument.class);

        featureStore.persistIfCurrent(snapshot);

        Document stored = mongo.getCollection("jobs").find(new Document("_id", "legacy-1")).first();
        assertNotNull(stored);
        assertNotNull(stored.get("matchFeatures"));
        assertEquals(false, stored.getBoolean("active"));
        assertEquals("MISSING_FROM_SOURCE", stored.getString("inactiveReason"));
        assertEquals(listings, stored.getList("sourceListings", Document.class));
    }

    @Test
    void staleExtractionCannotOverwriteFeaturesAfterCanonicalContentChanges() {
        insertLegacy("legacy-2", "Requirements: Java.");
        JobDocument snapshot = mongo.findById("legacy-2", JobDocument.class);
        assertNotNull(snapshot);
        assertTrue(featureService.prepare(snapshot));

        mongo.updateFirst(Query.query(Criteria.where("_id").is("legacy-2")),
                new Update().set("description", "Requirements: Python."), JobDocument.class);
        featureStore.persistIfCurrent(snapshot);

        Document stored = mongo.getCollection("jobs").find(new Document("_id", "legacy-2")).first();
        assertNotNull(stored);
        assertFalse(stored.containsKey("matchFeatures"));
        assertEquals("Requirements: Python.", stored.getString("description"));
    }

    private void insertLegacy(String id, String description) {
        mongo.getCollection("jobs").insertOne(new Document("_id", id)
                .append("title", "Backend Engineer").append("description", description)
                .append("location", "Pune").append("company", "Acme")
                .append("salary", 0.0).append("experience", 0.0)
                .append("employmentType", "full-time").append("source", "manual")
                .append("active", true).append("createdAt", LocalDateTime.of(2026, 8, 1, 0, 0)));
    }
}
