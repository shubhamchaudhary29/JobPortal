package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.application.infrastructure.ApplicationDocument;
import com.example.backend.application.infrastructure.ApplicationRepository;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.job.infrastructure.JobApplicationReferenceCoordinator;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@SpringBootTest(properties = {
        "job-aggregation.cleanup.retention-days=30",
        "job-aggregation.cleanup.batch-size=2"
})
class ImportedJobCleanupMongoIntegrationTest {
    @Autowired ImportedJobCleanupService cleanup;
    @Autowired ApplicationRepository applications;
    @Autowired MongoTemplate mongo;
    @Autowired JobApplicationReferenceCoordinator jobReferences;

    @BeforeEach
    void clear() {
        applications.deleteAll();
        mongo.remove(new Query(), JobDocument.class);
    }

    @Test
    void cleanupIsDeterministicallyBoundedAndDeletesOnlyOldInactiveImports() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 1, 0, 0);
        mongo.insert(inactiveImported("oldest", now.minusDays(60)));
        mongo.insert(inactiveImported("middle", now.minusDays(50)));
        mongo.insert(inactiveImported("newest", now.minusDays(40)));

        ImportedJobCleanupService.Result first = cleanup.cleanup(now);
        ImportedJobCleanupService.Result second = cleanup.cleanup(now);

        assertAll(
                () -> assertEquals(2, first.scanned()),
                () -> assertEquals(2, first.deleted()),
                () -> assertEquals(1, second.scanned()),
                () -> assertEquals(1, second.deleted()),
                () -> assertTrue(mongo.findAll(JobDocument.class).isEmpty()));
    }

    @Test
    void recruiterReferencedActiveAndRecentJobsAreProtected() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 1, 0, 0);
        JobDocument referenced = inactiveImported("referenced", now.minusDays(60));
        mongo.insert(referenced);
        ApplicationDocument application = new ApplicationDocument();
        application.setJobId(referenced.getId());
        application.setUserId("candidate@example.test");
        applications.save(application);

        JobDocument recruiter = inactiveImported("recruiter", now.minusDays(60));
        recruiter.setRecruiterId("recruiter-1");
        mongo.insert(recruiter);
        JobDocument active = inactiveImported("active", now.minusDays(60));
        active.setActive(true);
        mongo.insert(active);
        mongo.insert(inactiveImported("recent", now.minusDays(5)));
        JobDocument noInactiveDate = inactiveImported("no-date", now.minusDays(60));
        noInactiveDate.setInactiveAt(null);
        mongo.insert(noInactiveDate);

        ImportedJobCleanupService.Result result = cleanup.cleanup(now);

        assertAll(
                () -> assertEquals(1, result.scanned()),
                () -> assertEquals(0, result.deleted()),
                () -> assertEquals(1, result.protectedReferences()),
                () -> assertEquals(5, mongo.findAll(JobDocument.class).size()),
                () -> assertEquals(List.of(referenced.getId()), applications.findAll().stream()
                        .map(ApplicationDocument::getJobId).toList()));
    }

    @Test
    void cleanupClaimWinsRaceAndApplicationIsRejectedWithoutOrphan() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 4, 1, 0, 0);
        JobDocument job = inactiveImported("cleanup-wins", now.minusDays(60));
        job.setActive(true);
        mongo.insert(job);
        mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria.where("_id").is(job.getId())),
                new org.springframework.data.mongodb.core.query.Update().set("active", false), JobDocument.class);
        CountDownLatch cleanupClaimed = new CountDownLatch(1);
        CountDownLatch allowCleanup = new CountDownLatch(1);
        ImportedJobCleanupService racingCleanup = new ImportedJobCleanupService(
                mongo, applications, jobReferences, 30, 2, ignored -> {
                    cleanupClaimed.countDown();
                    await(allowCleanup);
                });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ImportedJobCleanupService.Result> cleanupResult = executor.submit(() -> racingCleanup.cleanup(now));
            assertTrue(cleanupClaimed.await(5, TimeUnit.SECONDS));
            Future<Boolean> applicationResult = executor.submit(() -> {
                boolean acquired = jobReferences.acquireApplicationReference(job.getId());
                if (acquired) applications.save(application(job.getId(), "candidate@example.test"));
                return acquired;
            });

            assertFalse(applicationResult.get(5, TimeUnit.SECONDS));
            allowCleanup.countDown();
            assertEquals(1, cleanupResult.get(5, TimeUnit.SECONDS).deleted());
            assertAll(
                    () -> assertFalse(mongo.exists(Query.query(org.springframework.data.mongodb.core.query.Criteria
                            .where("_id").is(job.getId())), JobDocument.class)),
                    () -> assertFalse(applications.existsByJobId(job.getId())));
        } finally {
            allowCleanup.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void applicationClaimWinsRaceAndCleanupCannotDeleteJob() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 4, 1, 0, 0);
        JobDocument job = inactiveImported("application-wins", now.minusDays(60));
        job.setActive(true);
        mongo.insert(job);
        CountDownLatch applicationClaimed = new CountDownLatch(1);
        CountDownLatch allowInsert = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> applicationResult = executor.submit(() -> {
                boolean acquired = jobReferences.acquireApplicationReference(job.getId());
                applicationClaimed.countDown();
                await(allowInsert);
                if (acquired) applications.save(application(job.getId(), "candidate@example.test"));
                return acquired;
            });
            assertTrue(applicationClaimed.await(5, TimeUnit.SECONDS));
            mongo.updateFirst(Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id")
                            .is(job.getId())),
                    new org.springframework.data.mongodb.core.query.Update().set("active", false), JobDocument.class);

            ImportedJobCleanupService.Result cleanupResult = cleanup.cleanup(now);
            allowInsert.countDown();

            assertTrue(applicationResult.get(5, TimeUnit.SECONDS));
            assertAll(
                    () -> assertEquals(0, cleanupResult.deleted()),
                    () -> assertTrue(mongo.exists(Query.query(org.springframework.data.mongodb.core.query.Criteria
                            .where("_id").is(job.getId())), JobDocument.class)),
                    () -> assertTrue(applications.existsByJobId(job.getId())));
        } finally {
            allowInsert.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void inactiveAndReconciliationPendingImportsRejectClaimsButRecruiterJobsRemainEligible() {
        LocalDateTime now = LocalDateTime.of(2026, 4, 1, 0, 0);
        JobDocument inactive = inactiveImported("inactive", now.minusDays(60));
        mongo.insert(inactive);
        JobDocument pending = inactiveImported("pending", now.minusDays(60));
        pending.setActive(true);
        pending.setReconciliationConflictId("conflict-1");
        mongo.insert(pending);
        JobDocument recruiter = inactiveImported("recruiter-eligible", now.minusDays(60));
        recruiter.setRecruiterId("recruiter-1");
        recruiter.setReconciliationConflictId("ignored-for-recruiter");
        mongo.insert(recruiter);

        assertAll(
                () -> assertFalse(jobReferences.acquireApplicationReference(inactive.getId())),
                () -> assertFalse(jobReferences.acquireApplicationReference(pending.getId())),
                () -> assertTrue(jobReferences.acquireApplicationReference(recruiter.getId())));
    }

    private ApplicationDocument application(String jobId, String userId) {
        ApplicationDocument application = new ApplicationDocument();
        application.setJobId(jobId);
        application.setUserId(userId);
        return application;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for race barrier");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for race barrier", interrupted);
        }
    }

    private JobDocument inactiveImported(String externalId, LocalDateTime inactiveAt) {
        JobDocument job = new JobDocument();
        job.setSource("adzuna");
        job.setExternalId(externalId);
        job.setTitle("Engineer");
        job.setDescription("Description");
        job.setCompany("Acme");
        job.setLocation("Remote");
        job.setFingerprint("fingerprint-" + externalId);
        job.setActive(false);
        job.setInactiveAt(inactiveAt);
        job.setInactiveReason("all_source_listings_missing");
        return job;
    }
}
