package com.example.backend.integration.adzuna;

import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.shared.validation.SafeExternalUrl;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
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
        job.setSourceUrl(sourceUrl.get()); job.setApplicationUrl(sourceUrl.get());
        job.setCompany(source.company() == null || source.company().displayName() == null
                ? "Unknown" : source.company().displayName());
        job.setLocation(source.location() == null || source.location().displayName() == null
                ? "India" : source.location().displayName());
        job.setSalary(source.salaryMin() == null ? 0.0 : source.salaryMin());
        job.setSalaryMin(source.salaryMin()); job.setSalaryMax(source.salaryMax());
        job.setEmploymentType(normalize(source.employmentType()));
        job.setPublishedAt(parseDate(source.publishedAt()));
        job.setExperience(0.0);
        job.setSource("adzuna");
        job.setExternalId(source.id());
        job.setRecruiterId(null);
        job.setFetchedAt(now);
        job.setLastSeenAt(now);
        job.setFirstSeenAt(now); job.setActive(true);
        job.setFingerprint(fingerprint(job.getCompany(), job.getTitle(), job.getLocation(), sourceUrl.get()));
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
    private static String normalize(String value) { return value == null ? null : value.trim().replaceAll("\\s+", " "); }
    private static LocalDateTime parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try { return OffsetDateTime.parse(value.trim()).toLocalDateTime(); }
        catch (DateTimeParseException ignored) { return null; }
    }
    private static String fingerprint(String company, String title, String location, String url) {
        String input = String.join("|", company, title, location, url).toLowerCase(Locale.ROOT);
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }
}
