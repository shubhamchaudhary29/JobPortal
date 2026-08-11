package com.example.backend.integration.adzuna;

import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.job.infrastructure.JobDocument;
import java.time.LocalDateTime;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/** Exercises atomic upserts against the same MongoDB used by the application. */
@SpringBootTest
class AdzunaJobStoreMongoIntegrationTest {
    @Autowired AdzunaJobStore store;
    @Autowired MongoTemplate mongo;

    @BeforeEach void clear() { mongo.remove(new Query(), JobDocument.class); }

    @Test
    void realMongoUpsertIsIdempotentConcurrentAndPreservesFirstSeen() throws Exception {
        LocalDateTime first = LocalDateTime.of(2026, 1, 1, 0, 0);
        assertEquals(UpsertOutcome.INSERTED, store.upsert(job("same", "one"), first));
        var pool = Executors.newFixedThreadPool(6);
        var futures = pool.invokeAll(java.util.Collections.nCopies(6,
                (Callable<UpsertOutcome>) () -> store.upsert(job("same", "one"), first.plusMinutes(1))));
        pool.shutdown();

        assertEquals(1, mongo.findAll(JobDocument.class).size());
        JobDocument persisted = mongo.findAll(JobDocument.class).get(0);
        assertEquals(first, persisted.getFirstSeenAt());
        assertTrue(persisted.getLastSeenAt().isAfter(first));
        for (var future : futures) assertEquals(UpsertOutcome.UNCHANGED, future.get());

        assertEquals(UpsertOutcome.UPDATED, store.upsert(job("same", "changed"), first.plusMinutes(2)));
        assertEquals("changed", mongo.findAll(JobDocument.class).get(0).getDescription());
    }

    @Test
    void externalIdentitiesAreScopedBySource() {
        JobDocument adzuna = job("id", "one");
        JobDocument other = job("id", "two");
        other.setSource("other");
        store.upsert(adzuna, LocalDateTime.now());
        store.upsert(other, LocalDateTime.now());
        assertEquals(2, mongo.findAll(JobDocument.class).size());
    }

    @Test
    void applicationOwnsTheNamedUniqueIndexAndImportedJobsDoNotAlterRecruiterJobs() {
        assertTrue(mongo.indexOps(JobDocument.class).getIndexInfo().stream()
                .anyMatch(index -> "source_external_id_unique".equals(index.getName()) && index.isUnique()));

        JobDocument recruiterJob = job("recruiter-id", "recruiter job");
        recruiterJob.setSource("manual");
        recruiterJob.setRecruiterId("recruiter-1");
        JobDocument imported = job("provider-id", "imported job");
        mongo.save(recruiterJob);
        store.upsert(imported, LocalDateTime.of(2026, 1, 1, 0, 1));

        assertEquals("recruiter-1", mongo.findAll(JobDocument.class).stream()
                .filter(job -> "manual".equals(job.getSource())).findFirst().orElseThrow().getRecruiterId());
        assertNull(mongo.findAll(JobDocument.class).stream()
                .filter(job -> "adzuna".equals(job.getSource())).findFirst().orElseThrow().getRecruiterId());
    }

    @Test
    void updatesEveryProviderManagedFieldButRetainsIdentityAndFirstSeen() {
        LocalDateTime first = LocalDateTime.of(2026, 1, 1, 0, 0);
        JobDocument original = job("meaningful", "old description");
        original.setEmploymentType("full_time");
        original.setSalaryMin(10.0); original.setSalaryMax(20.0);
        original.setPublishedAt(first); original.setExpiresAt(first.plusDays(7));
        assertEquals(UpsertOutcome.INSERTED, store.upsert(original, first));

        JobDocument changed = job("meaningful", "new description");
        changed.setTitle("Senior Engineer"); changed.setCompany("New Co"); changed.setLocation("Mumbai");
        changed.setEmploymentType("contract"); changed.setSalaryMin(30.0); changed.setSalaryMax(40.0);
        changed.setApplicationUrl("https://example.test/meaningful-new"); changed.setSourceUrl(changed.getApplicationUrl());
        changed.setPublishedAt(first.plusDays(1)); changed.setExpiresAt(first.plusDays(30)); changed.setFingerprint("changed-fingerprint");
        assertEquals(UpsertOutcome.UPDATED, store.upsert(changed, first.plusMinutes(1)));

        JobDocument persisted = mongo.findAll(JobDocument.class).get(0);
        assertAll(() -> assertEquals("Senior Engineer", persisted.getTitle()),
                () -> assertEquals("new description", persisted.getDescription()), () -> assertEquals("New Co", persisted.getCompany()),
                () -> assertEquals("Mumbai", persisted.getLocation()), () -> assertEquals("contract", persisted.getEmploymentType()),
                () -> assertEquals(30.0, persisted.getSalaryMin()), () -> assertEquals(40.0, persisted.getSalaryMax()),
                () -> assertEquals("https://example.test/meaningful-new", persisted.getApplicationUrl()),
                () -> assertEquals(first.plusDays(1), persisted.getPublishedAt()), () -> assertEquals(first.plusDays(30), persisted.getExpiresAt()),
                () -> assertEquals("changed-fingerprint", persisted.getFingerprint()), () -> assertEquals(first, persisted.getFirstSeenAt()),
                () -> assertEquals("adzuna", persisted.getSource()), () -> assertEquals("meaningful", persisted.getExternalId()));
    }

    private JobDocument job(String id, String description) {
        JobDocument job = new JobDocument();
        job.setSource("adzuna"); job.setExternalId(id); job.setTitle("Engineer"); job.setDescription(description);
        job.setCompany("Co"); job.setLocation("Pune"); job.setApplicationUrl("https://example.test/" + id);
        job.setSourceUrl(job.getApplicationUrl()); job.setFingerprint(id + description); job.setActive(true);
        return job;
    }
}
