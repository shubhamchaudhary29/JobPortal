package com.example.backend.integration.reliability;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.backend.integration.adzuna.AdzunaJobStore;
import com.example.backend.integration.aggregation.EmployerIngestionService;
import com.example.backend.integration.aggregation.EmployerRegistryProperties;
import com.example.backend.integration.aggregation.ExternalJobNormalizer;
import com.example.backend.integration.aggregation.ImportedJobLifecycleService;
import com.example.backend.integration.jobs.JobSource;
import com.example.backend.integration.jooble.JoobleProperties;
import com.example.backend.integration.jooble.JoobleSearchProvider;
import com.example.backend.integration.search.RequestBudget;
import com.example.backend.integration.search.SearchProviderIngestionService;
import com.example.backend.job.infrastructure.ImportedSourceListing;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.shared.provider.ProviderCatalog;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.client.RestTemplate;

/** A Jooble listing of an ATS job joins the ATS job's canonical document through the shared fingerprint. */
@SpringBootTest
class JoobleMergeMongoIntegrationTest {
    @Autowired AdzunaJobStore store;
    @Autowired ImportedJobLifecycleService lifecycle;
    @Autowired MongoTemplate mongo;
    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws Exception {
        mongo.remove(new Query(), JobDocument.class);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", exchange -> {
            byte[] body = ("{\"totalCount\":1,\"jobs\":[{\"id\":991,\"title\":\"Backend Engineer\",\"location\":\"Bangalore, Karnataka\","
                    + "\"snippet\":\"Build payments\",\"type\":\"Full-time\",\"link\":\"https://in.jooble.org/desc/991\","
                    + "\"company\":\"Razorpay Software Private Limited\",\"updated\":\"2026-09-15T12:55:35\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void joobleAndGreenhouseCopiesShareOneJobWithBothListingsAndTheJoobleLink() {
        JobSource greenhouse = mock(JobSource.class);
        when(greenhouse.sourceName()).thenReturn("greenhouse");
        when(greenhouse.fetchWithMetadata(any(), any())).thenReturn(new JobSource.FetchResult(List.of(
                ExternalJobNormalizer.normalize("4012", "Backend Engineer", "<p>Build</p>", "Razorpay", "Bengaluru, India",
                        "Full-time", "https://job-boards.greenhouse.io/razorpay/jobs/4012", null)), 0));
        new EmployerIngestionService(List.of(greenhouse), store, new EmployerRegistryProperties(List.of(
                new EmployerRegistryProperties.Employer("Razorpay", EmployerRegistryProperties.Source.GREENHOUSE,
                        "razorpay", true))), lifecycle).sync();
        Map<String, String> urls = new HashMap<>();
        for (ProviderCatalog.Provider ats : ProviderCatalog.ofKind(ProviderCatalog.Kind.ATS_BOARD)) urls.put(ats.name(), base);
        ProviderReliabilityProperties reliability = new ProviderReliabilityProperties(1_000, 1_000, 1, 1, 10, urls,
                65_536, 20, 5, 3, 60_000);
        JoobleSearchProvider jooble = new JoobleSearchProvider(
                new JoobleProperties("test-only-jooble-key-0001", base + "/api", "backend engineer", "India", 1, 20, 100),
                reliability, new ProviderHttpClient(new RestTemplate(), Clock.systemUTC()),
                new ProviderCircuitBreaker(reliability, Clock.systemUTC()), new ProviderRequestLimiter(reliability, Clock.systemUTC()),
                ignored -> { }, bound -> 0);

        new SearchProviderIngestionService(store, lifecycle, RequestBudget.unlimited(), Clock.systemUTC()).sync(jooble, () -> true);

        List<JobDocument> jobs = mongo.findAll(JobDocument.class);
        assertEquals(1, jobs.size());
        JobDocument merged = jobs.get(0);
        assertAll(
                () -> assertEquals(List.of("greenhouse:razorpay:4012", "jooble:991"),
                        merged.getSourceListings().stream().map(ImportedSourceListing::getIdentity).toList()),
                () -> assertEquals("https://in.jooble.org/desc/991", merged.getSourceListings().get(1).getApplicationUrl()),
                () -> assertTrue(merged.getApplicationUrls().contains("https://in.jooble.org/desc/991")),
                () -> assertEquals("greenhouse", merged.getSource()));
    }
}
