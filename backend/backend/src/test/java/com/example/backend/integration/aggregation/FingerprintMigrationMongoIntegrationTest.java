package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.job.infrastructure.ImportedSourceListing;
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

@SpringBootTest
class FingerprintMigrationMongoIntegrationTest {
    @Autowired FingerprintMigrationService migration;
    @Autowired AggregationConflictService conflicts;
    @Autowired MongoTemplate mongo;

    private JobDocument bangalore, bengaluru, solo, recruiter, referenced, current, unfingerprintable, pending;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), JobDocument.class);
        mongo.remove(new Query(), AggregationConflictDocument.class);
        mongo.getCollection("applications").deleteMany(new Document());
        bangalore = imported("Razorpay Software Pvt Ltd", "Backend Engineer", "Bangalore", "v1-bangalore", "greenhouse:rzp:1");
        bengaluru = imported("Razorpay", "Backend Engineer", "Bengaluru", "v1-bengaluru", "adzuna:adz-1");
        solo = imported("Acme", "Engineer", "Pune", "v1-solo", "lever:acme:1");
        recruiter = imported("Acme", "Engineer", "Pune", "v1-recruiter", null);
        recruiter.setRecruiterId("recruiter-1");
        referenced = imported("Globex", "Analyst", "Bombay", "v1-referenced", "lever:globex:1");
        current = imported("Initech", "QA", "New Delhi", JobFingerprint.of("Initech", "QA", "Delhi"), "lever:initech:1");
        current.setFingerprintVersion(JobFingerprint.VERSION);
        unfingerprintable = imported(null, "Orphan", "Pune", "v1-orphan", "lever:orphan:1");
        pending = imported("Razorpay", "Backend Engineer", "Bengaluru", null, null);
        pending.setReconciliationTargetId("somewhere");
        for (JobDocument job : List.of(bangalore, bengaluru, solo, recruiter, referenced, current, unfingerprintable, pending)) {
            mongo.insert(job);
        }
        mongo.getCollection("applications").insertOne(new Document("jobId", referenced.getId()).append("userId", "candidate-1"));
    }

    @Test
    void dryRunReportsEveryCategoryWithoutWriting() {
        List<Document> jobsBefore = snapshot("jobs");

        FingerprintMigrationService.Report report = migration.run(false);

        assertAll(
                () -> assertEquals(jobsBefore, snapshot("jobs")),
                () -> assertTrue(snapshot("aggregation_conflicts").isEmpty()),
                () -> assertEquals(6, report.scanned),
                () -> assertEquals(1, report.current),
                () -> assertEquals(1, report.planned),
                () -> assertEquals(List.of(solo.getId()), report.plannedIds),
                () -> assertEquals(0, report.updated),
                () -> assertEquals(2, report.collisions),
                () -> assertEquals(1, report.collisionGroups),
                () -> assertEquals(1, report.conflictPairs),
                () -> assertEquals(0, report.conflictsRecorded),
                () -> assertEquals(1, report.skippedApplicationReferenced),
                () -> assertEquals(List.of(referenced.getId()), report.skippedApplicationIds),
                () -> assertEquals(1, report.unfingerprintable),
                () -> assertTrue(report.needsReview()));
    }

    @Test
    void applyUpdatesOnlyUniqueStaleJobsRoutesCollisionsToConflictsAndIsIdempotent() {
        FingerprintMigrationService.Report first = migration.run(true);
        List<Document> afterFirst = snapshot("jobs");
        FingerprintMigrationService.Report second = migration.run(true);

        JobDocument migrated = mongo.findById(solo.getId(), JobDocument.class);
        List<AggregationConflictDocument> recorded = mongo.findAll(AggregationConflictDocument.class);
        assertAll(
                () -> assertEquals(1, first.updated),
                () -> assertEquals(JobFingerprint.of("Acme", "Engineer", "Pune"), migrated.getFingerprint()),
                () -> assertEquals(JobFingerprint.VERSION, migrated.getFingerprintVersion()),
                () -> assertUnchanged(bangalore), () -> assertUnchanged(bengaluru), () -> assertUnchanged(recruiter),
                () -> assertUnchanged(referenced), () -> assertUnchanged(unfingerprintable), () -> assertUnchanged(pending),
                () -> assertEquals(1, first.conflictsRecorded),
                () -> assertEquals(1, recorded.size()),
                () -> assertEquals(AggregationConflictDocument.Type.FINGERPRINT_MIGRATION, recorded.get(0).getType()),
                () -> assertEquals(AggregationConflictDocument.Status.OPEN, recorded.get(0).getStatus()),
                () -> assertEquals(java.util.Set.of(bangalore.getId(), bengaluru.getId()), recorded.get(0).getJobIds()),
                () -> assertEquals(JobFingerprint.of("Razorpay", "Backend Engineer", "Bengaluru"), recorded.get(0).getFingerprint()),
                () -> assertEquals(0, second.updated),
                () -> assertEquals(0, second.planned),
                () -> assertEquals(2, second.current),
                () -> assertEquals(afterFirst, snapshot("jobs")),
                () -> assertEquals(1, mongo.findAll(AggregationConflictDocument.class).size()),
                () -> assertEquals(2, mongo.findAll(AggregationConflictDocument.class).get(0).getOccurrences()));
    }

    @Test
    void resolvingTheConflictThenRerunningUpgradesTheSurvivor() {
        migration.run(true);
        AggregationConflictDocument conflict = mongo.findAll(AggregationConflictDocument.class).get(0);

        conflicts.resolve(conflict.getId(), bengaluru.getId(), bangalore.getId(), "admin@example.com");
        FingerprintMigrationService.Report rerun = migration.run(true);

        JobDocument survivor = mongo.findById(bengaluru.getId(), JobDocument.class);
        assertAll(
                () -> assertNull(mongo.findById(bangalore.getId(), JobDocument.class)),
                () -> assertEquals(1, rerun.updated),
                () -> assertEquals(0, rerun.collisions),
                () -> assertEquals(JobFingerprint.of("Razorpay", "Backend Engineer", "Bengaluru"), survivor.getFingerprint()),
                () -> assertEquals(JobFingerprint.VERSION, survivor.getFingerprintVersion()),
                () -> assertEquals(2, survivor.getSourceListings().size()));
    }

    private void assertUnchanged(JobDocument seeded) {
        JobDocument stored = mongo.findById(seeded.getId(), JobDocument.class);
        assertEquals(seeded.getFingerprint(), stored.getFingerprint(), seeded.getTitle());
        assertEquals(seeded.getFingerprintVersion(), stored.getFingerprintVersion(), seeded.getTitle());
    }

    private List<Document> snapshot(String collection) {
        return mongo.getCollection(collection).find().sort(new Document("_id", 1)).into(new ArrayList<>());
    }

    private JobDocument imported(String company, String title, String location, String fingerprint, String identity) {
        JobDocument job = new JobDocument();
        job.setCompany(company);
        job.setTitle(title);
        job.setLocation(location);
        job.setFingerprint(fingerprint);
        job.setActive(true);
        if (identity != null) {
            ImportedSourceListing listing = new ImportedSourceListing();
            listing.setIdentity(identity);
            listing.setProvider(identity.substring(0, identity.indexOf(':')));
            listing.setExternalId(identity.substring(identity.indexOf(':') + 1));
            listing.setApplicationUrl("https://jobs.example.test/" + identity.replace(':', '/'));
            listing.setActive(true);
            listing.setFirstSeenAt(LocalDateTime.of(2026, 9, 1, 0, 0));
            listing.setLastSeenAt(LocalDateTime.of(2026, 10, 1, 0, 0));
            job.setSourceListings(new ArrayList<>(List.of(listing)));
            job.setSource(listing.getProvider());
            job.setExternalId(listing.getExternalId());
            job.setSourceIdentities(new java.util.LinkedHashSet<>(List.of(identity)));
        }
        return job;
    }
}
