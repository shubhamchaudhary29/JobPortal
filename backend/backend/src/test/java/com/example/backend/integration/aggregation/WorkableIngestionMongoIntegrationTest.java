package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.backend.integration.adzuna.AdzunaJobStore;
import com.example.backend.integration.jobs.ExternalJob;
import com.example.backend.integration.jobs.JobSource;
import com.example.backend.job.infrastructure.ImportedSourceListing;
import com.example.backend.job.infrastructure.JobDocument;
import io.micrometer.core.instrument.MeterRegistry;
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

/** Workable flows through the shared fingerprint/source-listing store, lifecycle, history, and metrics. */
@SpringBootTest(properties = "job-aggregation.lifecycle.missing-threshold=2")
class WorkableIngestionMongoIntegrationTest {
    @Autowired AdzunaJobStore store;
    @Autowired ImportedJobLifecycleService lifecycle;
    @Autowired DistributedLeaseLock locks;
    @Autowired SyncRunService runs;
    @Autowired MongoTemplate mongo;
    @Autowired MeterRegistry meters;
    @Autowired @Qualifier("ingestionLeaseScheduler") ScheduledExecutorService leaseScheduler;

    private final JobSource greenhouse = mock(JobSource.class);
    private final JobSource workable = mock(JobSource.class);
    private final AtomicReference<List<ExternalJob>> workableJobs = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        mongo.remove(new Query(), JobDocument.class);
        mongo.remove(new Query(), SyncRunDocument.class);
        when(greenhouse.sourceName()).thenReturn("greenhouse");
        when(workable.sourceName()).thenReturn("workable");
        when(greenhouse.fetchWithMetadata(any(), any())).thenReturn(new JobSource.FetchResult(List.of(
                job("4012", "https://boards.greenhouse.io/acme/jobs/4012")), 0));
        when(workable.fetchWithMetadata(any(), any()))
                .thenAnswer(invocation -> new JobSource.FetchResult(workableJobs.get(), 0));
    }

    @Test
    void workableListingMergesWithIdenticalGreenhouseJobAndHasItsOwnLifecycleHistoryAndMetrics() {
        workableJobs.set(List.of(job("AB12", "https://apply.workable.com/j/AB12")));
        service(EmployerRegistryProperties.Source.GREENHOUSE, "acme").sync();
        IngestionCoordinator workableRuns = coordinator(service(EmployerRegistryProperties.Source.WORKABLE, "acme-workable"));
        double workableRunsBefore = runCount();

        IngestionCoordinator.Result first = workableRuns.run(EmployerRegistryProperties.Source.WORKABLE,
                SyncRunService.Trigger.SCHEDULED);

        JobDocument merged = singleJob();
        assertAll(
                () -> assertEquals(SyncRunService.Outcome.COMPLETED, first.outcome()),
                () -> assertEquals(1, first.sync().unchanged() + first.sync().updated()),
                () -> assertEquals(List.of("greenhouse:acme:4012", "workable:acme-workable:AB12"),
                        merged.getSourceListings().stream().map(ImportedSourceListing::getIdentity).toList()),
                () -> assertEquals("greenhouse", merged.getSource()),
                () -> assertEquals("https://boards.greenhouse.io/acme/jobs/4012", merged.getApplicationUrl()),
                () -> assertEquals("acme-workable", listing(merged, "workable").getEmployer()),
                () -> assertTrue(merged.getActive()));

        workableJobs.set(List.of());
        workableRuns.run(EmployerRegistryProperties.Source.WORKABLE, SyncRunService.Trigger.SCHEDULED);
        workableRuns.run(EmployerRegistryProperties.Source.WORKABLE, SyncRunService.Trigger.MANUAL);
        JobDocument workableMissing = singleJob();
        assertAll(
                () -> assertFalse(listing(workableMissing, "workable").isActive()),
                () -> assertTrue(listing(workableMissing, "greenhouse").isActive()),
                () -> assertTrue(workableMissing.getActive()),
                () -> assertEquals("greenhouse", workableMissing.getSource()),
                () -> assertEquals("https://boards.greenhouse.io/acme/jobs/4012", workableMissing.getApplicationUrl()));

        workableJobs.set(List.of(job("AB12", "https://apply.workable.com/j/AB12")));
        workableRuns.run(EmployerRegistryProperties.Source.WORKABLE, SyncRunService.Trigger.MANUAL);
        JobDocument reactivated = singleJob();
        assertAll(
                () -> assertTrue(listing(reactivated, "workable").isActive()),
                () -> assertEquals(0, listing(reactivated, "workable").getConsecutiveMissingRuns()),
                () -> assertEquals("greenhouse", reactivated.getSource()));

        List<SyncRunDocument> history = mongo.findAll(SyncRunDocument.class);
        assertAll(
                () -> assertEquals(4, history.size()),
                () -> assertTrue(history.stream().allMatch(run -> "workable".equals(run.getProvider()))),
                () -> assertTrue(history.stream().allMatch(run -> run.getOutcome() == SyncRunService.Outcome.COMPLETED)),
                () -> assertEquals(workableRunsBefore + 4, runCount()));
    }

    @Test
    void workableOnlyJobDeactivatesAfterThresholdAndReactivatesOnRediscovery() {
        workableJobs.set(List.of(job("SOLO", "https://apply.workable.com/j/SOLO")));
        EmployerIngestionService workableOnly = service(EmployerRegistryProperties.Source.WORKABLE, "acme-workable");
        workableOnly.sync(EmployerRegistryProperties.Source.WORKABLE);

        workableJobs.set(List.of());
        workableOnly.sync(EmployerRegistryProperties.Source.WORKABLE);
        workableOnly.sync(EmployerRegistryProperties.Source.WORKABLE);
        JobDocument inactive = singleJob();
        assertAll(
                () -> assertFalse(inactive.getActive()),
                () -> assertEquals("all_source_listings_missing", inactive.getInactiveReason()));

        workableJobs.set(List.of(job("SOLO", "https://apply.workable.com/j/SOLO")));
        workableOnly.sync(EmployerRegistryProperties.Source.WORKABLE);
        assertTrue(singleJob().getActive());
    }

    private EmployerIngestionService service(EmployerRegistryProperties.Source source, String board) {
        return new EmployerIngestionService(List.of(greenhouse, workable), store,
                new EmployerRegistryProperties(List.of(new EmployerRegistryProperties.Employer("Acme", source,
                        board, true))), lifecycle);
    }

    private IngestionCoordinator coordinator(EmployerIngestionService ingestion) {
        return new IngestionCoordinator(ingestion, locks, 5_000, 1_000, leaseScheduler, runs);
    }

    private ExternalJob job(String id, String url) {
        return ExternalJobNormalizer.normalize(id, "Backend Engineer", "<p>Build</p>", "Acme",
                "Bengaluru, India", "Full-time", url, "2026-09-01T10:00:00Z");
    }

    private double runCount() {
        var counter = meters.find("jobportal.aggregation.runs").tag("provider", "workable").counters();
        return counter.stream().mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    private JobDocument singleJob() {
        List<JobDocument> jobs = mongo.findAll(JobDocument.class);
        assertEquals(1, jobs.size());
        return jobs.get(0);
    }

    private ImportedSourceListing listing(JobDocument job, String provider) {
        return job.getSourceListings().stream().filter(item -> provider.equals(item.getProvider()))
                .findFirst().orElseThrow();
    }
}
