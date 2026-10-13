package com.example.backend.integration.adzuna;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.backend.integration.aggregation.EmployerIngestionService;
import com.example.backend.integration.aggregation.EmployerRegistryProperties;
import com.example.backend.integration.aggregation.ExternalJobNormalizer;
import com.example.backend.integration.aggregation.ImportedJobLifecycleService;
import com.example.backend.integration.aggregation.JobFingerprint;
import com.example.backend.integration.jobs.JobSource;
import com.example.backend.job.infrastructure.ImportedSourceListing;
import com.example.backend.job.infrastructure.JobDocument;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

/** Version-2 fingerprints let an Adzuna listing and an ATS listing of one job share a canonical document. */
@SpringBootTest
class CrossProviderFingerprintMongoIntegrationTest {
    @Autowired AdzunaJobStore store;
    @Autowired ImportedJobLifecycleService lifecycle;
    @Autowired MongoTemplate mongo;

    @BeforeEach
    void clear() { mongo.remove(new Query(), JobDocument.class); }

    @Test
    void adzunaAndGreenhouseCopiesOfOneJobMergeWithTheCurrentFingerprintVersion() {
        AdzunaClient client = (keyword, page) -> new AdzunaResponse(page == 1 ? List.of(
                new AdzunaResponse.AdzunaJob("adz-1", "Backend Engineer", "<p>Build payments</p>",
                        "https://www.adzuna.in/land/ad/adz-1", 2_000_000d,
                        new AdzunaResponse.Company("Razorpay Software Private Limited"),
                        new AdzunaResponse.Location("Bangalore, Karnataka")),
                new AdzunaResponse.AdzunaJob("adz-2", "Backend Engineer", "<p>Duplicate ad</p>",
                        "https://www.adzuna.in/land/ad/adz-2", null,
                        new AdzunaResponse.Company("Razorpay"), new AdzunaResponse.Location("Bengaluru"))) : List.of());
        AdzunaProperties properties = AdzunaServiceTest.properties(1, "backend engineer");
        AdzunaService adzuna = new AdzunaService(new AdzunaJobSource(client), store,
                properties, new AdzunaCircuitBreaker(properties),
                new AdzunaSyncMetrics(), lifecycle, java.time.Clock.systemUTC(), ignored -> { }, bound -> 0);
        JobSource greenhouse = mock(JobSource.class);
        when(greenhouse.sourceName()).thenReturn("greenhouse");
        when(greenhouse.fetchWithMetadata(any(), any())).thenReturn(new JobSource.FetchResult(List.of(
                ExternalJobNormalizer.normalize("4012", "Backend Engineer", "<p>Build</p>", "Razorpay",
                        "Bengaluru, India", "Full-time", "https://job-boards.greenhouse.io/razorpay/jobs/4012",
                        null)), 0));

        adzuna.sync();
        new EmployerIngestionService(List.of(greenhouse), store, new EmployerRegistryProperties(List.of(
                new EmployerRegistryProperties.Employer("Razorpay", EmployerRegistryProperties.Source.GREENHOUSE,
                        "razorpay", true))), lifecycle).sync();

        List<JobDocument> jobs = mongo.findAll(JobDocument.class);
        assertEquals(1, jobs.size());
        JobDocument merged = jobs.get(0);
        assertAll(
                () -> assertEquals(List.of("adzuna:adz-1", "adzuna:adz-2", "greenhouse:razorpay:4012"),
                        merged.getSourceListings().stream().map(ImportedSourceListing::getIdentity).toList()),
                () -> assertEquals(JobFingerprint.of("Razorpay", "Backend Engineer", "Bengaluru"), merged.getFingerprint()),
                () -> assertEquals(JobFingerprint.VERSION, merged.getFingerprintVersion()),
                () -> assertEquals("adzuna", merged.getSource()),
                () -> assertEquals("https://www.adzuna.in/land/ad/adz-1", merged.getApplicationUrl()),
                () -> assertEquals("Bangalore, Karnataka", merged.getLocation()),
                () -> assertEquals("Razorpay Software Private Limited", merged.getCompany()),
                () -> assertTrue(merged.getActive()));
    }
}
