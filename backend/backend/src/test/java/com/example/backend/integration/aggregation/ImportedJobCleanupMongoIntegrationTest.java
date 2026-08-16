package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.application.infrastructure.ApplicationDocument;
import com.example.backend.application.infrastructure.ApplicationRepository;
import com.example.backend.job.infrastructure.JobDocument;
import java.time.LocalDateTime;
import java.util.List;
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
