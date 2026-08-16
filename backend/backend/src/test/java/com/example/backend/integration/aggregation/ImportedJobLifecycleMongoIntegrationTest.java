package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.integration.adzuna.AdzunaJobStore;
import com.example.backend.job.infrastructure.ImportedSourceListing;
import com.example.backend.job.infrastructure.JobDocument;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@SpringBootTest(properties = "job-aggregation.lifecycle.missing-threshold=2")
class ImportedJobLifecycleMongoIntegrationTest {
    @Autowired ImportedJobLifecycleService lifecycle;
    @Autowired AdzunaJobStore store;
    @Autowired MongoTemplate mongo;

    @BeforeEach
    void clear() {
        mongo.remove(new Query(), JobDocument.class);
    }

    @Test
    void successfulMissesDeactivateAtThresholdAndRediscoveryReactivates() {
        LocalDateTime firstSeen = LocalDateTime.of(2026, 3, 1, 0, 0);
        JobDocument incoming = imported("lever", "board:one", "https://lever.test/one", "one");
        store.upsert(incoming, firstSeen, "board");

        lifecycle.completeSuccessfulRun("lever", "board", Set.of(), firstSeen.plusDays(1));
        JobDocument afterFirstMiss = singleJob();
        assertAll(
                () -> assertTrue(afterFirstMiss.getActive()),
                () -> assertTrue(afterFirstMiss.getSourceListings().get(0).isActive()),
                () -> assertEquals(1, afterFirstMiss.getSourceListings().get(0).getConsecutiveMissingRuns()));

        lifecycle.completeSuccessfulRun("lever", "board", Set.of(), firstSeen.plusDays(2));
        JobDocument inactive = singleJob();
        assertAll(
                () -> assertFalse(inactive.getActive()),
                () -> assertFalse(inactive.getSourceListings().get(0).isActive()),
                () -> assertEquals(2, inactive.getSourceListings().get(0).getConsecutiveMissingRuns()),
                () -> assertEquals("all_source_listings_missing", inactive.getInactiveReason()),
                () -> assertEquals(firstSeen.plusDays(2), inactive.getInactiveAt()));

        store.upsert(incoming, firstSeen.plusDays(3), "board");
        JobDocument reactivated = singleJob();
        assertAll(
                () -> assertTrue(reactivated.getActive()),
                () -> assertTrue(reactivated.getSourceListings().get(0).isActive()),
                () -> assertEquals(0, reactivated.getSourceListings().get(0).getConsecutiveMissingRuns()),
                () -> assertNull(reactivated.getInactiveReason()),
                () -> assertNull(reactivated.getInactiveAt()),
                () -> assertEquals(firstSeen, reactivated.getSourceListings().get(0).getFirstSeenAt()));
    }

    @Test
    void inactiveSourceDoesNotDeactivateMultiSourceCanonicalJob() {
        LocalDateTime firstSeen = LocalDateTime.of(2026, 3, 1, 0, 0);
        store.upsert(imported("adzuna", "adzuna-1", "https://adzuna.test/one", "shared"), firstSeen);
        store.upsert(imported("lever", "board:one", "https://lever.test/one", "shared"),
                firstSeen, "board");

        lifecycle.completeSuccessfulRun("adzuna", null, Set.of(), firstSeen.plusDays(1));
        lifecycle.completeSuccessfulRun("adzuna", null, Set.of(), firstSeen.plusDays(2));
        JobDocument persisted = singleJob();
        assertAll(
                () -> assertTrue(persisted.getActive()),
                () -> assertEquals("lever", persisted.getSource()),
                () -> assertEquals("https://lever.test/one", persisted.getApplicationUrl()),
                () -> assertFalse(listing(persisted, "adzuna:adzuna-1").isActive()),
                () -> assertTrue(listing(persisted, "lever:board:one").isActive()));

        store.upsert(imported("adzuna", "adzuna-1", "https://adzuna.test/one", "shared"),
                firstSeen.plusDays(3));
        assertEquals("adzuna", singleJob().getSource());
    }

    @Test
    void seenSetResetsOnlySeenListingAndRecruiterJobsAreIgnored() {
        LocalDateTime observedAt = LocalDateTime.of(2026, 3, 1, 0, 0);
        store.upsert(imported("lever", "board:one", "https://lever.test/one", "one"), observedAt, "board");
        store.upsert(imported("lever", "board:two", "https://lever.test/two", "two"), observedAt, "board");
        JobDocument recruiter = imported("lever", "board:manual", "https://lever.test/manual", "manual");
        recruiter.setRecruiterId("recruiter-1");
        ImportedSourceListing fakeListing = new ImportedSourceListing();
        fakeListing.setIdentity("lever:board:manual");
        fakeListing.setProvider("lever");
        fakeListing.setEmployer("board");
        fakeListing.setExternalId("board:manual");
        fakeListing.setActive(true);
        recruiter.setSourceListings(List.of(fakeListing));
        mongo.insert(recruiter);

        lifecycle.completeSuccessfulRun("lever", "board", Set.of("lever:board:one"), observedAt.plusDays(1));

        JobDocument seen = byExternalId("board:one");
        JobDocument missing = byExternalId("board:two");
        JobDocument untouchedRecruiter = byExternalId("board:manual");
        assertAll(
                () -> assertEquals(0, listing(seen, "lever:board:one").getConsecutiveMissingRuns()),
                () -> assertEquals(observedAt.plusDays(1), listing(seen, "lever:board:one").getLastSeenAt()),
                () -> assertEquals(1, listing(missing, "lever:board:two").getConsecutiveMissingRuns()),
                () -> assertEquals(0, listing(untouchedRecruiter, "lever:board:manual").getConsecutiveMissingRuns()));
    }

    private JobDocument singleJob() {
        List<JobDocument> jobs = mongo.findAll(JobDocument.class);
        assertEquals(1, jobs.size());
        return jobs.get(0);
    }

    private JobDocument byExternalId(String externalId) {
        return mongo.findOne(Query.query(org.springframework.data.mongodb.core.query.Criteria
                .where("externalId").is(externalId)), JobDocument.class);
    }

    private ImportedSourceListing listing(JobDocument job, String identity) {
        assertNotNull(job);
        return job.getSourceListings().stream().filter(item -> identity.equals(item.getIdentity()))
                .findFirst().orElseThrow();
    }

    private JobDocument imported(String provider, String externalId, String url, String fingerprint) {
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
}
