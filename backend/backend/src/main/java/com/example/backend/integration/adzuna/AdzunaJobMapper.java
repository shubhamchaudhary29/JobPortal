package com.example.backend.integration.adzuna;

import com.example.backend.job.infrastructure.JobDocument;

public final class AdzunaJobMapper {
    private AdzunaJobMapper() { }

    public static JobDocument toDocument(AdzunaResponse.AdzunaJob source) {
        JobDocument job = new JobDocument();
        job.setTitle(source.title());
        job.setDescription(cleanDescription(source.description()));
        job.setSourceUrl(source.redirectUrl());
        job.setCompany(source.company() == null || source.company().displayName() == null
                ? "Unknown" : source.company().displayName());
        job.setLocation(source.location() == null || source.location().displayName() == null
                ? "India" : source.location().displayName());
        job.setSalary(source.salaryMin() == null ? 0.0 : source.salaryMin());
        job.setExperience(0.0);
        job.setSource("adzuna");
        job.setExternalId(source.id());
        job.setRecruiterId(null);
        return job;
    }

    static String cleanDescription(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("<[^>]*>", "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&nbsp;", " ").replace("&#39;", "'").replace("&quot;", "\"")
                .replaceAll("<[^>]*>", "").replaceAll("\\s+", " ").trim();
    }
}
