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

/** Ashby flows through the shared fingerprint/source-listing store, lifecycle, history, and metrics. */
@SpringBootTest(properties = "job-aggregation.lifecycle.missing-threshold=2")
class AshbyIngestionMongoIntegrationTest {
    @Autowired AdzunaJobStore store;
    @Autowired ImportedJobLifecycleService lifecycle;
    @Autowired DistributedLeaseLock locks;
    @Autowired SyncRunService runs;
    @Autowired MongoTemplate mongo;
    @Autowired MeterRegistry meters;
    @Autowired @Qualifier("ingestionLeaseScheduler") ScheduledExecutorService leaseScheduler;

    private final JobSource greenhouse = mock(JobSource.class);
    private final JobSource ashby = mock(JobSource.class);
    private final AtomicReference<List<ExternalJob>> ashbyJobs = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        mongo.remove(new Query(), JobDocument.class);
        mongo.remove(new Query(), SyncRunDocument.class);
        when(greenhouse.sourceName()).thenReturn("greenhouse");
        when(ashby.sourceName()).thenReturn("ashby");
        when(greenhouse.fetchWithMetadata(any(), any())).thenReturn(new JobSource.FetchResult(List.of(
                job("4012", "https://boards.greenhouse.io/acme/jobs/4012")), 0));
        when(ashby.fetchWithMetadata(any(), any()))
                .thenAnswer(invocation -> new JobSource.FetchResult(ashbyJobs.get(), 0));
    }

    @Test
    void ashbyListingMergesWithIdenticalGreenhouseJobAndHasItsOwnLifecycleHistoryAndMetrics() {
        ashbyJobs.set(List.of(job("a1", "https://jobs.ashbyhq.com/acme/a1")));
        service(EmployerRegistryProperties.Source.GREENHOUSE, "acme").sync();
        IngestionCoordinator ashbyRuns = coordinator(service(EmployerRegistryProperties.Source.ASHBY, "acme-ashby"));
        double ashbyRunsBefore = runCount();

        IngestionCoordinator.Result first = ashbyRuns.run(EmployerRegistryProperties.Source.ASHBY,
                SyncRunService.Trigger.SCHEDULED);

        JobDocument merged = singleJob();
        assertAll(
                () -> assertEquals(SyncRunService.Outcome.COMPLETED, first.outcome()),
                () -> assertEquals(1, first.sync().unchanged() + first.sync().updated()),
                () -> assertEquals(List.of("ashby:acme-ashby:a1", "greenhouse:acme:4012"),
                        merged.getSourceListings().stream().map(ImportedSourceListing::getIdentity).toList()),
                () -> assertEquals("ashby", merged.getSource()),
                () -> assertEquals("https://jobs.ashbyhq.com/acme/a1", merged.getApplicationUrl()),
                () -> assertEquals("acme-ashby", listing(merged, "ashby").getEmployer()),
                () -> assertTrue(merged.getActive()));

        ashbyJobs.set(List.of());
        ashbyRuns.run(EmployerRegistryProperties.Source.ASHBY, SyncRunService.Trigger.SCHEDULED);
        ashbyRuns.run(EmployerRegistryProperties.Source.ASHBY, SyncRunService.Trigger.MANUAL);
        JobDocument ashbyMissing = singleJob();
        assertAll(
                () -> assertFalse(listing(ashbyMissing, "ashby").isActive()),
                () -> assertTrue(listing(ashbyMissing, "greenhouse").isActive()),
                () -> assertTrue(ashbyMissing.getActive()),
                () -> assertEquals("greenhouse", ashbyMissing.getSource()),
                () -> assertEquals("https://boards.greenhouse.io/acme/jobs/4012", ashbyMissing.getApplicationUrl()));

        ashbyJobs.set(List.of(job("a1", "https://jobs.ashbyhq.com/acme/a1")));
        ashbyRuns.run(EmployerRegistryProperties.Source.ASHBY, SyncRunService.Trigger.MANUAL);
        JobDocument reactivated = singleJob();
        assertAll(
                () -> assertTrue(listing(reactivated, "ashby").isActive()),
                () -> assertEquals(0, listing(reactivated, "ashby").getConsecutiveMissingRuns()),
                () -> assertEquals("ashby", reactivated.getSource()));

        List<SyncRunDocument> history = mongo.findAll(SyncRunDocument.class);
        assertAll(
                () -> assertEquals(4, history.size()),
                () -> assertTrue(history.stream().allMatch(run -> "ashby".equals(run.getProvider()))),
                () -> assertTrue(history.stream().allMatch(run -> run.getOutcome() == SyncRunService.Outcome.COMPLETED)),
                () -> assertEquals(ashbyRunsBefore + 4, runCount()));
    }

    @Test
    void ashbyOnlyJobDeactivatesAfterThresholdAndReactivatesOnRediscovery() {
        ashbyJobs.set(List.of(job("solo", "https://jobs.ashbyhq.com/acme/solo")));
        EmployerIngestionService ashbyOnly = service(EmployerRegistryProperties.Source.ASHBY, "acme-ashby");
        ashbyOnly.sync(EmployerRegistryProperties.Source.ASHBY);

        ashbyJobs.set(List.of());
        ashbyOnly.sync(EmployerRegistryProperties.Source.ASHBY);
        ashbyOnly.sync(EmployerRegistryProperties.Source.ASHBY);
        JobDocument inactive = singleJob();
        assertAll(
                () -> assertFalse(inactive.getActive()),
                () -> assertEquals("all_source_listings_missing", inactive.getInactiveReason()));

        ashbyJobs.set(List.of(job("solo", "https://jobs.ashbyhq.com/acme/solo")));
        ashbyOnly.sync(EmployerRegistryProperties.Source.ASHBY);
        assertTrue(singleJob().getActive());
    }

    private EmployerIngestionService service(EmployerRegistryProperties.Source source, String board) {
        return new EmployerIngestionService(List.of(greenhouse, ashby), store,
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
        var counter = meters.find("jobportal.aggregation.runs").tag("provider", "ashby").counters();
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
