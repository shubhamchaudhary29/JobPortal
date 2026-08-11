package com.example.backend.integration.adzuna;

import com.example.backend.integration.jobs.ExternalJob;
import com.example.backend.integration.jobs.JobFetchRequest;
import com.example.backend.integration.jobs.JobSource;
import java.util.List;
import org.springframework.stereotype.Component;

@Component("adzunaJobSource")
public class AdzunaJobSource implements JobSource {
    private final AdzunaClient client;
    public AdzunaJobSource(AdzunaClient client) { this.client = client; }
    @Override public String sourceName() { return "adzuna"; }
    @Override public List<ExternalJob> fetch(JobFetchRequest request) {
        AdzunaResponse response = client.fetchPage(request.keyword(), request.page());
        if (response.results() == null) return List.of();
        return response.results().stream().map(this::external).toList();
    }
    private ExternalJob external(AdzunaResponse.AdzunaJob job) {
        String url = com.example.backend.shared.validation.SafeExternalUrl.parse(job.redirectUrl()).orElse(null);
        String company = job.company() == null || job.company().displayName() == null ? "Unknown" : job.company().displayName().trim();
        String location = job.location() == null || job.location().displayName() == null ? "India" : job.location().displayName().trim();
        String title = job.title() == null ? null : job.title().trim();
        String description = AdzunaJobMapper.cleanDescription(job.description());
        String fingerprint = url == null || title == null ? null : fingerprint(company, title, location, url);
        return new ExternalJob(job.id(), title, description, company, location, job.employmentType(), job.salaryMin(), job.salaryMax(), url,
                parseDate(job.publishedAt()), null, fingerprint);
    }
    private static java.time.LocalDateTime parseDate(String value) { try { return value == null ? null : java.time.OffsetDateTime.parse(value).toLocalDateTime(); } catch (java.time.format.DateTimeParseException ex) { return null; } }
    private static String fingerprint(String company, String title, String location, String url) { try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((company + "|" + title + "|" + location + "|" + url).toLowerCase(java.util.Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8))); } catch (Exception ex) { throw new IllegalStateException(ex); } }
}
