package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.backend.integration.adzuna.AdzunaJobStore;
import com.example.backend.integration.jobs.ExternalJob;
import com.example.backend.integration.jobs.JobSource;
import com.example.backend.job.infrastructure.ImportedSourceListing;
import com.example.backend.job.infrastructure.JobDocument;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/** Incomplete SmartRecruiters coverage stores what it saw but never advances missing state. */
@SpringBootTest(properties = "job-aggregation.lifecycle.missing-threshold=2")
class SmartRecruitersIngestionMongoIntegrationTest {
    @Autowired AdzunaJobStore store;
    @Autowired ImportedJobLifecycleService lifecycle;
    @Autowired DistributedLeaseLock locks;
    @Autowired SyncRunService runs;
    @Autowired MongoTemplate mongo;
    @Autowired @Qualifier("ingestionLeaseScheduler") ScheduledExecutorService leaseScheduler;

    private final JobSource greenhouse = mock(JobSource.class);
    private final JobSource smartRecruiters = mock(JobSource.class);
    private final AtomicReference<JobSource.FetchResult> next = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        mongo.remove(new Query(), JobDocument.class);
        mongo.remove(new Query(), SyncRunDocument.class);
        when(greenhouse.sourceName()).thenReturn("greenhouse");
        when(smartRecruiters.sourceName()).thenReturn("smartrecruiters");
        when(greenhouse.fetchWithMetadata(any(), any())).thenReturn(new JobSource.FetchResult(List.of(
                job("4012", "https://boards.greenhouse.io/acme/jobs/4012")), 0));
        when(smartRecruiters.fetchWithMetadata(any(), any())).thenAnswer(invocation -> next.get());
    }

    @Test
    void mergesWithGreenhouseAndIncompleteRunsArePartialWithoutLifecycleProgress() {
        new EmployerIngestionService(List.of(greenhouse, smartRecruiters), store, registry(
                EmployerRegistryProperties.Source.GREENHOUSE, "acme"), lifecycle).sync();
        IngestionCoordinator coordinator = new IngestionCoordinator(new EmployerIngestionService(
                List.of(greenhouse, smartRecruiters), store,
                registry(EmployerRegistryProperties.Source.SMARTRECRUITERS, "Acme1"), lifecycle),
                locks, 5_000, 1_000, leaseScheduler, runs);

        next.set(new JobSource.FetchResult(List.of(job("744000001", "https://jobs.smartrecruiters.com/Acme1/744000001-engineer")), 0));
        IngestionCoordinator.Result merged = coordinator.run(EmployerRegistryProperties.Source.SMARTRECRUITERS,
                SyncRunService.Trigger.SCHEDULED);
        JobDocument job = singleJob();
        assertAll(
                () -> assertEquals(SyncRunService.Outcome.COMPLETED, merged.outcome()),
                () -> assertEquals(List.of("greenhouse:acme:4012", "smartrecruiters:Acme1:744000001"),
                        job.getSourceListings().stream().map(ImportedSourceListing::getIdentity).toList()));

        next.set(new JobSource.FetchResult(List.of(), 0, 0, false));
        for (int run = 0; run < 3; run++) {
            IngestionCoordinator.Result partial = coordinator.run(EmployerRegistryProperties.Source.SMARTRECRUITERS,
                    SyncRunService.Trigger.SCHEDULED);
            assertEquals(SyncRunService.Outcome.FAILED, partial.outcome());
            assertEquals(1, partial.sync().failedEmployers());
        }
        next.set(new JobSource.FetchResult(List.of(job("other", "https://jobs.smartrecruiters.com/Acme1/other")),
                0, 0, false));
        IngestionCoordinator.Result partialWithItems = coordinator.run(
                EmployerRegistryProperties.Source.SMARTRECRUITERS, SyncRunService.Trigger.MANUAL);

        ImportedSourceListing listing = listing(mongo.findAll(JobDocument.class).stream()
                .filter(candidate -> candidate.getSourceListings().size() == 2).findFirst().orElseThrow());
        assertAll(
                () -> assertEquals(SyncRunService.Outcome.PARTIAL, partialWithItems.outcome()),
                () -> assertEquals(2, mongo.findAll(JobDocument.class).size()),
                () -> assertTrue(listing.isActive()),
                () -> assertEquals(0, listing.getConsecutiveMissingRuns()),
                () -> assertEquals(List.of(SyncRunService.Outcome.COMPLETED, SyncRunService.Outcome.FAILED,
                        SyncRunService.Outcome.FAILED, SyncRunService.Outcome.FAILED, SyncRunService.Outcome.PARTIAL),
                        mongo.findAll(SyncRunDocument.class).stream()
                                .sorted(java.util.Comparator.comparing(SyncRunDocument::getStartedAt))
                                .map(SyncRunDocument::getOutcome).toList()),
                () -> assertTrue(mongo.findAll(SyncRunDocument.class).stream()
                        .allMatch(run -> "smartrecruiters".equals(run.getProvider()))));

        next.set(new JobSource.FetchResult(List.of(), 0));
        coordinator.run(EmployerRegistryProperties.Source.SMARTRECRUITERS, SyncRunService.Trigger.SCHEDULED);
        coordinator.run(EmployerRegistryProperties.Source.SMARTRECRUITERS, SyncRunService.Trigger.SCHEDULED);
        JobDocument afterCompleteMisses = mongo.findAll(JobDocument.class).stream()
                .filter(candidate -> candidate.getSourceListings().size() == 2).findFirst().orElseThrow();
        assertAll(
                () -> assertFalse(listing(afterCompleteMisses).isActive()),
                () -> assertTrue(afterCompleteMisses.getActive()));
    }

    private EmployerRegistryProperties registry(EmployerRegistryProperties.Source source, String board) {
        return new EmployerRegistryProperties(List.of(new EmployerRegistryProperties.Employer("Acme", source,
                board, true)));
    }

    private ExternalJob job(String id, String url) {
        return ExternalJobNormalizer.normalize(id, "Backend Engineer " + ("other".equals(id) ? "II" : ""),
                "<p>Build</p>", "Acme", "Pune, India", "Full-time", url, "2026-09-01T10:00:00Z");
    }

    private JobDocument singleJob() {
        List<JobDocument> jobs = mongo.findAll(JobDocument.class);
        assertEquals(1, jobs.size());
        return jobs.get(0);
    }

    private ImportedSourceListing listing(JobDocument job) {
        return job.getSourceListings().stream().filter(item -> "smartrecruiters".equals(item.getProvider()))
                .findFirst().orElseThrow();
    }
}
