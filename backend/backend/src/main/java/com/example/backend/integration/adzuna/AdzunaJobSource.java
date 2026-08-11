package com.example.backend.integration.adzuna;

import com.example.backend.integration.jobs.ExternalJob;
import com.example.backend.integration.jobs.JobFetchRequest;
import com.example.backend.integration.jobs.JobSource;
import com.example.backend.job.infrastructure.JobDocument;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class AdzunaJobSource implements JobSource {
    private final AdzunaClient client;
    public AdzunaJobSource(AdzunaClient client) { this.client = client; }
    @Override public String sourceName() { return "adzuna"; }
    @Override public List<ExternalJob> fetch(JobFetchRequest request) {
        AdzunaResponse response = client.fetchPage(request.keyword(), request.page());
        if (response.results() == null) return List.of();
        return response.results().stream().map(job -> AdzunaJobMapper.toDocument(job, java.time.LocalDateTime.now())
                .map(this::external).orElseGet(() -> new ExternalJob(null, null, null, null, null, null, null, null, null, null, null, null))).toList();
    }
    private ExternalJob external(JobDocument job) { return new ExternalJob(job.getExternalId(), job.getTitle(), job.getDescription(), job.getCompany(), job.getLocation(), job.getEmploymentType(), job.getSalaryMin(), job.getSalaryMax(), job.getApplicationUrl(), job.getPublishedAt(), job.getExpiresAt(), job.getFingerprint()); }
}
