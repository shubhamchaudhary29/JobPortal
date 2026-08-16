package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.application.infrastructure.ApplicationDocument;
import com.example.backend.integration.adzuna.AdzunaJobStore;
import com.example.backend.integration.adzuna.AdzunaPersistenceException;
import com.example.backend.job.infrastructure.ImportedSourceListing;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.messaging.infrastructure.ConversationDocument;
import com.example.backend.shared.error.ConflictException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@SpringBootTest
class AggregationConflictMongoIntegrationTest {
    @Autowired AdzunaJobStore store;
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
    void ingestionPersistsAndCoalescesIdentityFingerprintConflict() {
        LocalDateTime observedAt = LocalDateTime.of(2026, 5, 1, 0, 0);
        JobDocument identityJob = imported("greenhouse", "board:one", "old-fingerprint");
        JobDocument fingerprintJob = imported("lever", "board:two", "new-fingerprint");
        mongo.insert(identityJob);
        mongo.insert(fingerprintJob);
        JobDocument incoming = imported("greenhouse", "board:one", "new-fingerprint");

        assertThrows(AdzunaPersistenceException.class, () -> store.upsert(incoming, observedAt, "board"));
        assertThrows(AdzunaPersistenceException.class, () -> store.upsert(incoming, observedAt.plusMinutes(1), "board"));

        List<AggregationConflictDocument> persisted = mongo.findAll(AggregationConflictDocument.class);
        assertEquals(1, persisted.size());
        AggregationConflictDocument conflict = persisted.get(0);
        assertAll(
                () -> assertEquals(AggregationConflictDocument.Status.OPEN, conflict.getStatus()),
                () -> assertEquals("greenhouse:board:one", conflict.getIdentity()),
                () -> assertEquals("new-fingerprint", conflict.getFingerprint()),
                () -> assertEquals(Set.of(identityJob.getId(), fingerprintJob.getId()), conflict.getJobIds()),
                () -> assertEquals(2, conflict.getOccurrences()),
                () -> assertEquals(observedAt, conflict.getFirstObservedAt()),
                () -> assertEquals(observedAt.plusMinutes(1), conflict.getLastObservedAt()));
    }

    @Test
    void resolutionMovesReferencesMergesListingsDeletesDuplicateAndIsIdempotent() {
        LocalDateTime observedAt = LocalDateTime.of(2026, 5, 1, 0, 0);
        JobDocument canonical = imported("greenhouse", "board:one", "old-fingerprint");
        JobDocument duplicate = imported("lever", "board:two", "new-fingerprint");
        mongo.insert(canonical);
        mongo.insert(duplicate);
        conflicts.recordIdentityFingerprint("greenhouse:board:one", "new-fingerprint",
                canonical.getId(), duplicate.getId(), observedAt);
        AggregationConflictDocument conflict = mongo.findAll(AggregationConflictDocument.class).get(0);
        ApplicationDocument application = application("candidate@example.test", duplicate.getId());
        mongo.insert(application);
        ConversationDocument conversation = new ConversationDocument();
        conversation.setApplicationId(application.getId());
        conversation.setJobId(duplicate.getId());
        mongo.insert(conversation);

        AggregationConflictService.ConflictView resolved = conflicts.resolve(conflict.getId(),
                canonical.getId(), duplicate.getId(), "admin@example.test");
        AggregationConflictService.ConflictView replay = conflicts.resolve(conflict.getId(),
                canonical.getId(), duplicate.getId(), "admin@example.test");

        JobDocument merged = mongo.findById(canonical.getId(), JobDocument.class);
        assertAll(
                () -> assertEquals(AggregationConflictDocument.Status.RESOLVED, resolved.status()),
                () -> assertEquals(resolved, replay),
                () -> assertNotNull(merged),
                () -> assertEquals(2, merged.getSourceListings().size()),
                () -> assertEquals(Set.of("greenhouse:board:one", "lever:board:two"), merged.getSourceIdentities()),
                () -> assertNull(mongo.findById(duplicate.getId(), JobDocument.class)),
                () -> assertEquals(canonical.getId(), mongo.findById(application.getId(), ApplicationDocument.class).getJobId()),
                () -> assertEquals(canonical.getId(), mongo.findById(conversation.getId(), ConversationDocument.class).getJobId()));
    }

    @Test
    void ambiguousCandidateReferencesBlockResolutionWithoutMutation() {
        LocalDateTime observedAt = LocalDateTime.of(2026, 5, 1, 0, 0);
        JobDocument canonical = imported("greenhouse", "board:one", "old-fingerprint");
        JobDocument duplicate = imported("lever", "board:two", "new-fingerprint");
        mongo.insert(canonical);
        mongo.insert(duplicate);
        conflicts.recordIdentityFingerprint("greenhouse:board:one", "new-fingerprint",
                canonical.getId(), duplicate.getId(), observedAt);
        AggregationConflictDocument conflict = mongo.findAll(AggregationConflictDocument.class).get(0);
        mongo.insert(application("same@example.test", canonical.getId()));
        mongo.insert(application("same@example.test", duplicate.getId()));

        assertThrows(ConflictException.class, () -> conflicts.resolve(conflict.getId(),
                canonical.getId(), duplicate.getId(), "admin@example.test"));

        assertAll(
                () -> assertNotNull(mongo.findById(canonical.getId(), JobDocument.class)),
                () -> assertNotNull(mongo.findById(duplicate.getId(), JobDocument.class)),
                () -> assertEquals(2, mongo.findAll(ApplicationDocument.class).size()),
                () -> assertEquals("APPLICATION_REFERENCE_COLLISION",
                        mongo.findById(conflict.getId(), AggregationConflictDocument.class).getResolutionFailure()));
    }

    @Test
    void conflictListingIsFilteredPaginatedAndBounded() {
        LocalDateTime observedAt = LocalDateTime.of(2026, 5, 1, 0, 0);
        conflicts.recordIdentityFingerprint("greenhouse:one", "one", "job-1", "job-2", observedAt);
        conflicts.recordIdentityFingerprint("lever:two", "two", "job-3", "job-4", observedAt.plusMinutes(1));

        var firstPage = conflicts.list("OPEN", 0, 1);

        assertAll(
                () -> assertEquals(1, firstPage.content().size()),
                () -> assertEquals(2, firstPage.totalElements()),
                () -> assertEquals(2, firstPage.totalPages()),
                () -> assertEquals("lever:two", firstPage.content().get(0).identity()),
                () -> assertThrows(com.example.backend.shared.error.BadRequestException.class,
                        () -> conflicts.list("OPEN", 0, 101)),
                () -> assertThrows(com.example.backend.shared.error.BadRequestException.class,
                        () -> conflicts.list("UNKNOWN", 0, 20)));
    }

    @Test
    void openReconciliationResumesAfterDuplicateWasMarkedPending() {
        LocalDateTime observedAt = LocalDateTime.of(2026, 5, 1, 0, 0);
        JobDocument canonical = imported("greenhouse", "board:one", "old-fingerprint");
        JobDocument duplicate = imported("lever", "board:two", "new-fingerprint");
        mongo.insert(canonical);
        mongo.insert(duplicate);
        conflicts.recordIdentityFingerprint("greenhouse:board:one", "new-fingerprint",
                canonical.getId(), duplicate.getId(), observedAt);
        AggregationConflictDocument conflict = mongo.findAll(AggregationConflictDocument.class).get(0);
        mongo.updateFirst(Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is(conflict.getId())),
                new org.springframework.data.mongodb.core.query.Update()
                        .set("canonicalJobId", canonical.getId()).set("duplicateJobId", duplicate.getId())
                        .set("reconciliationStartedAt", observedAt), AggregationConflictDocument.class);
        mongo.updateFirst(Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is(duplicate.getId())),
                new org.springframework.data.mongodb.core.query.Update()
                        .set("reconciliationTargetId", canonical.getId())
                        .set("reconciliationConflictId", conflict.getId())
                        .set("reconciliationOriginalFingerprint", duplicate.getFingerprint())
                        .unset("fingerprint").unset("sourceIdentities").unset("source").unset("externalId")
                        .set("active", false), JobDocument.class);

        var resolved = conflicts.resolve(conflict.getId(), canonical.getId(), duplicate.getId(), "admin@example.test");

        assertAll(
                () -> assertEquals(AggregationConflictDocument.Status.RESOLVED, resolved.status()),
                () -> assertEquals(2, mongo.findById(canonical.getId(), JobDocument.class).getSourceListings().size()),
                () -> assertNull(mongo.findById(duplicate.getId(), JobDocument.class)));
    }

    private ApplicationDocument application(String userId, String jobId) {
        ApplicationDocument application = new ApplicationDocument();
        application.setUserId(userId);
        application.setJobId(jobId);
        return application;
    }

    private JobDocument imported(String provider, String externalId, String fingerprint) {
        JobDocument job = new JobDocument();
        job.setSource(provider);
        job.setExternalId(externalId);
        job.setTitle("Engineer from " + provider);
        job.setDescription("Description");
        job.setCompany("Acme");
        job.setLocation("Remote");
        job.setFingerprint(fingerprint);
        job.setActive(true);
        job.setSourceIdentities(new java.util.LinkedHashSet<>(Set.of(provider + ":" + externalId)));
        ImportedSourceListing listing = new ImportedSourceListing();
        listing.setIdentity(provider + ":" + externalId);
        listing.setProvider(provider);
        listing.setEmployer("board");
        listing.setExternalId(externalId);
        listing.setApplicationUrl("https://" + provider + ".test/" + externalId.replace(':', '-'));
        listing.setFirstSeenAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        listing.setLastSeenAt(LocalDateTime.of(2026, 2, 1, 0, 0));
        listing.setActive(true);
        job.setSourceListings(List.of(listing));
        job.setApplicationUrl(listing.getApplicationUrl());
        job.setSourceUrl(listing.getApplicationUrl());
        job.setApplicationUrls(new java.util.LinkedHashSet<>(Set.of(listing.getApplicationUrl())));
        return job;
    }
}
