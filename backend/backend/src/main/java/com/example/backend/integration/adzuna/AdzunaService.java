package com.example.backend.integration.adzuna;

import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.job.infrastructure.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

@Service
public class AdzunaService {
    private static final Logger log = LoggerFactory.getLogger(AdzunaService.class);
    private static final List<String> KEYWORDS = List.of("java developer", "python developer", "react developer",
            "data analyst", "backend engineer", "frontend developer", "full stack developer",
            "machine learning engineer");
    private final String appId;
    private final String appKey;
    private final RestTemplate http;
    private final JobRepository jobs;

    public AdzunaService(@Value("${adzuna.app.id}") String appId, @Value("${adzuna.app.key}") String appKey,
                         RestTemplate http, JobRepository jobs) {
        this.appId = appId;
        this.appKey = appKey;
        this.http = http;
        this.jobs = jobs;
    }

    @Scheduled(fixedRate = 6 * 60 * 60 * 1000)
    public void fetchAndSaveJobs() {
        long startedAt = System.nanoTime();
        int failures = 0;
        for (String keyword : KEYWORDS) {
            try {
                fetchKeyword(keyword);
                Thread.sleep(1000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                failures++;
                break;
            } catch (RuntimeException ex) {
                failures++;
                log.warn("Adzuna import failed for one keyword: {}", ex.getClass().getSimpleName());
            }
        }
        log.info("Adzuna import finished in {} ms with {} failed keyword(s)",
                (System.nanoTime() - startedAt) / 1_000_000, failures);
    }

    private void fetchKeyword(String keyword) {
        URI uri = UriComponentsBuilder.fromUriString("https://api.adzuna.com/v1/api/jobs/in/search/1")
                .queryParam("app_id", appId).queryParam("app_key", appKey).queryParam("results_per_page", 20)
                .queryParam("what", keyword).queryParam("content-type", "application/json").build().encode().toUri();
        AdzunaResponse response = http.getForObject(uri, AdzunaResponse.class);
        if (response == null || response.results() == null) return;
        for (AdzunaResponse.AdzunaJob source : response.results()) {
            if (source.id() == null || jobs.existsByExternalId(source.id())) continue;
            JobDocument document = AdzunaJobMapper.toDocument(source);
            jobs.save(document);
        }
    }
}
