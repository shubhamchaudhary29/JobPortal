package com.example.backend.integration.adzuna;

import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.job.infrastructure.JobDocument;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@SpringBootTest
class CanonicalJobStoreMongoIntegrationTest {
    @Autowired AdzunaJobStore store;
    @Autowired MongoTemplate mongo;

    @BeforeEach
    void clear() {
        mongo.remove(new Query(), JobDocument.class);
    }

    @Test
    void primaryProviderAndApplicationLinkDoNotDependOnIngestionOrder() {
        LocalDateTime observedAt = LocalDateTime.of(2026, 2, 1, 0, 0);
        JobDocument lever = imported("lever", "board:lever-1", "https://lever.test/apply", "shared");
        JobDocument adzuna = imported("adzuna", "adzuna-1", "https://adzuna.test/apply", "shared");

        store.upsert(lever, observedAt, "board");
        store.upsert(adzuna, observedAt.plusMinutes(1));
        CanonicalSnapshot leverFirst = snapshot(singleJob());

        clear();
        store.upsert(adzuna, observedAt);
        store.upsert(lever, observedAt.plusMinutes(1), "board");
        CanonicalSnapshot adzunaFirst = snapshot(singleJob());

        assertEquals(leverFirst, adzunaFirst);
        assertAll(
                () -> assertEquals("adzuna", adzunaFirst.source()),
                () -> assertEquals("adzuna-1", adzunaFirst.externalId()),
                () -> assertEquals("https://adzuna.test/apply", adzunaFirst.applicationUrl()),
                () -> assertEquals("Description from adzuna", adzunaFirst.description()),
                () -> assertEquals(List.of("adzuna:adzuna-1", "lever:board:lever-1"), adzunaFirst.identities()),
                () -> assertEquals(List.of("https://adzuna.test/apply", "https://lever.test/apply"), adzunaFirst.urls()));
    }

    @Test
    void sameProviderUsesLexicalIdentityAsStableTieBreaker() {
        LocalDateTime observedAt = LocalDateTime.of(2026, 2, 1, 0, 0);
        store.upsert(imported("lever", "z-board:z-job", "https://lever.test/z", "shared"), observedAt, "z-board");
        store.upsert(imported("lever", "a-board:a-job", "https://lever.test/a", "shared"), observedAt.plusMinutes(1), "a-board");

        JobDocument persisted = singleJob();
        assertAll(
                () -> assertEquals("lever", persisted.getSource()),
                () -> assertEquals("a-board:a-job", persisted.getExternalId()),
                () -> assertEquals("https://lever.test/a", persisted.getApplicationUrl()),
                () -> assertEquals("a-board", persisted.getSourceListings().get(0).getEmployer()));
    }

    @Test
    void concurrentCrossProviderIngestionRetainsOneCanonicalDocumentAndBothListings() throws Exception {
        LocalDateTime observedAt = LocalDateTime.of(2026, 2, 1, 0, 0);
        JobDocument lever = imported("lever", "board:lever-1", "https://lever.test/apply", "shared");
        JobDocument adzuna = imported("adzuna", "adzuna-1", "https://adzuna.test/apply", "shared");
        List<Callable<UpsertOutcome>> tasks = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            JobDocument incoming = index % 2 == 0 ? lever : adzuna;
            tasks.add(() -> store.upsert(incoming, observedAt, incoming == lever ? "board" : null));
        }
        var pool = Executors.newFixedThreadPool(8);
        var outcomes = pool.invokeAll(tasks);
        pool.shutdown();
        for (var outcome : outcomes) assertNotNull(outcome.get());

        JobDocument persisted = singleJob();
        assertAll(
                () -> assertEquals("adzuna", persisted.getSource()),
                () -> assertEquals("https://adzuna.test/apply", persisted.getApplicationUrl()),
                () -> assertEquals(2, persisted.getSourceListings().size()),
                () -> assertEquals(2, persisted.getSourceIdentities().size()),
                () -> assertEquals(2, persisted.getApplicationUrls().size()));
    }

    @Test
    void replayingNonPrimarySourceIsIdempotentAndCannotReplaceCanonicalContent() {
        LocalDateTime observedAt = LocalDateTime.of(2026, 2, 1, 0, 0);
        JobDocument adzuna = imported("adzuna", "adzuna-1", "https://adzuna.test/apply", "shared");
        JobDocument lever = imported("lever", "board:lever-1", "https://lever.test/apply", "shared");
        store.upsert(adzuna, observedAt);
        store.upsert(lever, observedAt.plusMinutes(1), "board");

        assertEquals(UpsertOutcome.UNCHANGED,
                store.upsert(lever, observedAt.plusMinutes(2), "board"));
        JobDocument persisted = singleJob();
        assertAll(
                () -> assertEquals("Description from adzuna", persisted.getDescription()),
                () -> assertEquals("https://adzuna.test/apply", persisted.getApplicationUrl()),
                () -> assertEquals(2, persisted.getSourceListings().size()));
    }

    private JobDocument singleJob() {
        List<JobDocument> jobs = mongo.findAll(JobDocument.class);
        assertEquals(1, jobs.size());
        return jobs.get(0);
    }

    private CanonicalSnapshot snapshot(JobDocument job) {
        return new CanonicalSnapshot(job.getSource(), job.getExternalId(), job.getApplicationUrl(), job.getDescription(),
                new ArrayList<>(job.getSourceIdentities()), new ArrayList<>(job.getApplicationUrls()));
    }

    private JobDocument imported(String provider, String externalId, String applicationUrl, String fingerprint) {
        JobDocument job = new JobDocument();
        job.setSource(provider);
        job.setExternalId(externalId);
        job.setTitle("Engineer");
        job.setDescription("Description from " + provider);
        job.setCompany("Acme");
        job.setLocation("Remote");
        job.setApplicationUrl(applicationUrl);
        job.setSourceUrl(applicationUrl);
        job.setFingerprint(fingerprint);
        job.setActive(true);
        return job;
    }

    private record CanonicalSnapshot(String source, String externalId, String applicationUrl, String description,
                                     List<String> identities, List<String> urls) { }
}
