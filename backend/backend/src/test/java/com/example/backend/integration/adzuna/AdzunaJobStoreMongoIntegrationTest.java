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

/** Uses the real MongoDB configured in src/test/resources/application.properties. */
@SpringBootTest
class AdzunaJobStoreMongoIntegrationTest {
    @Autowired AdzunaJobStore store;
    @Autowired MongoTemplate mongo;

    @BeforeEach void clear() { mongo.dropCollection(JobDocument.class); mongo.indexOps(JobDocument.class).ensureIndex(new org.springframework.data.mongodb.core.index.Index().on("source", org.springframework.data.domain.Sort.Direction.ASC).on("externalId", org.springframework.data.domain.Sort.Direction.ASC).unique().partial(org.springframework.data.mongodb.core.index.PartialIndexFilter.of(org.springframework.data.mongodb.core.query.Criteria.where("source").type(2).and("externalId").type(2)))); }

    @Test void realMongoUpsertIsIdempotentConcurrentAndPreservesFirstSeen() throws Exception {
        LocalDateTime first = LocalDateTime.of(2026, 1, 1, 0, 0);
        assertEquals(UpsertOutcome.INSERTED, store.upsert(job("same", "one"), first));
        var pool = Executors.newFixedThreadPool(6);
        var futures = pool.invokeAll(java.util.Collections.nCopies(6, (Callable<UpsertOutcome>) () -> store.upsert(job("same", "one"), first.plusMinutes(1))));
        pool.shutdown();
        assertEquals(1, mongo.findAll(JobDocument.class).size());
        JobDocument persisted = mongo.findAll(JobDocument.class).get(0);
        assertEquals(first, persisted.getFirstSeenAt()); assertTrue(persisted.getLastSeenAt().isAfter(first));
        for (var future : futures) assertTrue(future.get() == UpsertOutcome.UNCHANGED || future.get() == UpsertOutcome.UPDATED);
        assertEquals(UpsertOutcome.UPDATED, store.upsert(job("same", "changed"), first.plusMinutes(2)));
        assertEquals("changed", mongo.findAll(JobDocument.class).get(0).getDescription());
    }
    @Test void externalIdentitiesAreScopedBySource() {
        JobDocument a = job("id", "one"); a.setSource("adzuna"); JobDocument b = job("id", "two"); b.setSource("other");
        store.upsert(a, LocalDateTime.now()); store.upsert(b, LocalDateTime.now());
        assertEquals(2, mongo.findAll(JobDocument.class).size());
    }
    private JobDocument job(String id, String description) { JobDocument job = new JobDocument(); job.setSource("adzuna"); job.setExternalId(id); job.setTitle("Engineer"); job.setDescription(description); job.setCompany("Co"); job.setLocation("Pune"); job.setApplicationUrl("https://example.test/" + id); job.setSourceUrl(job.getApplicationUrl()); job.setFingerprint(id + description); job.setActive(true); return job; }
}
