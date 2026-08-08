package com.example.backend.integration.adzuna;

import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.shared.validation.SafeExternalUrl;
import java.time.LocalDateTime;
import java.util.Optional;

public final class AdzunaJobMapper {
    private AdzunaJobMapper() { }

    public static Optional<JobDocument> toDocument(AdzunaResponse.AdzunaJob source, LocalDateTime now) {
        if (source == null || blank(source.id()) || blank(source.title())) return Optional.empty();
        Optional<String> sourceUrl = SafeExternalUrl.parse(source.redirectUrl());
        if (sourceUrl.isEmpty()) return Optional.empty();
        JobDocument job = new JobDocument();
        job.setTitle(source.title().trim());
        job.setDescription(cleanDescription(source.description()));
        job.setSourceUrl(sourceUrl.get());
        job.setCompany(source.company() == null || source.company().displayName() == null
                ? "Unknown" : source.company().displayName());
        job.setLocation(source.location() == null || source.location().displayName() == null
                ? "India" : source.location().displayName());
        job.setSalary(source.salaryMin() == null ? 0.0 : source.salaryMin());
        job.setExperience(0.0);
        job.setSource("adzuna");
        job.setExternalId(source.id());
        job.setRecruiterId(null);
        job.setFetchedAt(now);
        job.setLastSeenAt(now);
        return Optional.of(job);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    static String cleanDescription(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("<[^>]*>", "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&nbsp;", " ").replace("&#39;", "'").replace("&quot;", "\"")
                .replaceAll("<[^>]*>", "").replaceAll("\\s+", " ").trim();
    }
}
