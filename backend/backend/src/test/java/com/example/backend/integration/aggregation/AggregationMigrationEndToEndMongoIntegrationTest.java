package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.application.infrastructure.ApplicationDocument;
import com.example.backend.integration.adzuna.AdzunaJobStore;
import com.example.backend.job.infrastructure.ImportedSourceListing;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.messaging.infrastructure.ConversationDocument;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@SpringBootTest(properties = {
        "job-aggregation.lifecycle.missing-threshold=2",
        "job-aggregation.cleanup.retention-days=1"
})
class AggregationMigrationEndToEndMongoIntegrationTest {
    @Autowired AdzunaJobStore store;
    @Autowired ImportedJobLifecycleService lifecycle;
    @Autowired ImportedJobCleanupService cleanup;
    @Autowired AggregationConflictService conflicts;
    @Autowired MongoTemplate mongo;

    @BeforeEach
    void clear() {
        mongo.remove(new Query(), ApplicationDocument.class);
        mongo.remove(new Query(), ConversationDocument.class);
        mongo.remove(new Query(), AggregationConflictDocument.class);
        mongo.remove(new Query(), JobDocument.class);
        mongo.getCollection("ingestion_locks").deleteMany(new org.bson.Document());
    }

    @Test
    void canonicalLifecycleCleanupAndConflictResolutionPreserveReferences() {
        LocalDateTime firstSeen = LocalDateTime.of(2026, 6, 1, 0, 0);
        store.upsert(incoming("adzuna", "a-1", "https://adzuna.test/a-1", "shared"), firstSeen);
        store.upsert(incoming("lever", "board:l-1", "https://lever.test/l-1", "shared"), firstSeen, "board");

        JobDocument canonical = onlyFingerprint("shared");
        assertEquals(List.of("adzuna:a-1", "lever:board:l-1"), canonical.getSourceListings().stream()
                .map(ImportedSourceListing::getIdentity).toList());
        lifecycle.completeSuccessfulRun("adzuna", null, Set.of(), firstSeen.plusDays(1));
        lifecycle.completeSuccessfulRun("adzuna", null, Set.of(), firstSeen.plusDays(2));
        assertTrue(onlyFingerprint("shared").getActive());
        lifecycle.completeSuccessfulRun("lever", "board", Set.of(), firstSeen.plusDays(3));
        lifecycle.completeSuccessfulRun("lever", "board", Set.of(), firstSeen.plusDays(4));
        assertFalse(onlyFingerprint("shared").getActive());

        ApplicationDocument protectedApplication = application("protected@example.test", canonical.getId());
        mongo.insert(protectedApplication);
        assertEquals(0, cleanup.cleanup(firstSeen.plusDays(10)).deleted());
        assertNotNull(mongo.findById(canonical.getId(), JobDocument.class));

        store.upsert(incoming("lever", "board:l-1", "https://lever.test/l-1", "shared"),
                firstSeen.plusDays(11), "board");
        assertTrue(onlyFingerprint("shared").getActive());

        JobDocument conflictCanonical = completeImported("greenhouse", "board:g-1", "old-fingerprint", firstSeen);
        JobDocument conflictDuplicate = completeImported("lever", "board:l-2", "new-fingerprint", firstSeen);
        mongo.insert(conflictCanonical);
        mongo.insert(conflictDuplicate);
        ApplicationDocument movedApplication = application("moved@example.test", conflictDuplicate.getId());
        mongo.insert(movedApplication);
        conflicts.recordIdentityFingerprint("greenhouse:board:g-1", "new-fingerprint",
                conflictCanonical.getId(), conflictDuplicate.getId(), firstSeen.plusDays(12));
        AggregationConflictDocument conflict = mongo.findAll(AggregationConflictDocument.class).get(0);

        var outcome = conflicts.resolve(conflict.getId(), conflictCanonical.getId(),
                conflictDuplicate.getId(), "admin@example.test");

        assertAll(
                () -> assertEquals(AggregationConflictDocument.Status.RESOLVED, outcome.status()),
                () -> assertNull(mongo.findById(conflictDuplicate.getId(), JobDocument.class)),
                () -> assertEquals(conflictCanonical.getId(),
                        mongo.findById(movedApplication.getId(), ApplicationDocument.class).getJobId()),
                () -> assertEquals(2, mongo.findById(conflictCanonical.getId(), JobDocument.class)
                        .getSourceListings().size()));
    }

    private ApplicationDocument application(String userId, String jobId) {
        ApplicationDocument application = new ApplicationDocument();
        application.setUserId(userId);
        application.setJobId(jobId);
        return application;
    }

    private JobDocument onlyFingerprint(String fingerprint) {
        return mongo.findOne(Query.query(org.springframework.data.mongodb.core.query.Criteria
                .where("fingerprint").is(fingerprint)), JobDocument.class);
    }

    private JobDocument incoming(String provider, String externalId, String url, String fingerprint) {
        JobDocument job = new JobDocument();
        job.setSource(provider);
        job.setExternalId(externalId);
        job.setTitle("Engineer");
        job.setDescription("Description");
        job.setCompany("Acme");
        job.setLocation("Remote");
        job.setApplicationUrl(url);
        job.setSourceUrl(url);
        job.setFingerprint(fingerprint);
        job.setActive(true);
        return job;
    }

    private JobDocument completeImported(String provider, String externalId, String fingerprint,
                                         LocalDateTime observedAt) {
        JobDocument job = incoming(provider, externalId,
                "https://" + provider + ".test/" + externalId.replace(':', '-'), fingerprint);
        String identity = provider + ":" + externalId;
        job.setSourceIdentities(new LinkedHashSet<>(Set.of(identity)));
        job.setApplicationUrls(new LinkedHashSet<>(Set.of(job.getApplicationUrl())));
        ImportedSourceListing listing = new ImportedSourceListing();
        listing.setIdentity(identity);
        listing.setProvider(provider);
        listing.setEmployer("board");
        listing.setExternalId(externalId);
        listing.setApplicationUrl(job.getApplicationUrl());
        listing.setFirstSeenAt(observedAt);
        listing.setLastSeenAt(observedAt);
        listing.setActive(true);
        listing.setConsecutiveMissingRuns(0);
        job.setSourceListings(List.of(listing));
        return job;
    }
}
