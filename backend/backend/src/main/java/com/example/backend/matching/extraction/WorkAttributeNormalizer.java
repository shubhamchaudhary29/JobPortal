package com.example.backend.matching.extraction;

import com.example.backend.job.domain.WorkMode;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class WorkAttributeNormalizer {
    private static final Pattern HYBRID = Pattern.compile("\\bhybrid\\b");
    private static final Pattern REMOTE = Pattern.compile("\\b(remote|work from home|wfh)\\b");
    private static final Pattern ONSITE = Pattern.compile("\\b(on[ -]?site|in office|office based)\\b");
    private static final Pattern INTERNSHIP = Pattern.compile("\\b(intern|internship|trainee)\\b");
    private static final Pattern PART_TIME = Pattern.compile("\\bpart[ -]?time\\b");
    private static final Pattern CONTRACT = Pattern.compile("\\b(contract|contractor|temporary|freelance)\\b");
    private static final Pattern FULL_TIME = Pattern.compile("\\b(full[ -]?time|permanent)\\b");

    public WorkMode workMode(String location, String title, String description) {
        String text = safe(location) + " " + safe(title) + " " + safe(description);
        if (HYBRID.matcher(text).find()) return WorkMode.HYBRID;
        if (REMOTE.matcher(text).find()) return WorkMode.REMOTE;
        if (ONSITE.matcher(text).find()) return WorkMode.ONSITE;
        if (location != null && !location.isBlank()) return WorkMode.ONSITE;
        return WorkMode.UNKNOWN;
    }

    public String employmentType(String structured, String title, String description) {
        String text = safe(structured) + " " + safe(title) + " " + safe(description);
        if (INTERNSHIP.matcher(text).find()) return "INTERNSHIP";
        if (PART_TIME.matcher(text).find()) return "PART_TIME";
        if (CONTRACT.matcher(text).find()) return "CONTRACT";
        if (FULL_TIME.matcher(text).find()) return "FULL_TIME";
        return "UNKNOWN";
    }

    public String normalizeEmploymentPreference(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = safe(value).replace('-', ' ').replace('_', ' ').replaceAll("\\s+", " ").trim();
        return switch (normalized) {
            case "intern", "internship", "trainee" -> "INTERNSHIP";
            case "full time", "permanent" -> "FULL_TIME";
            case "part time" -> "PART_TIME";
            case "contract", "contractor", "freelance", "temporary" -> "CONTRACT";
            default -> normalized.toUpperCase(Locale.ROOT).replace(' ', '_');
        };
    }

    private String safe(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
}
