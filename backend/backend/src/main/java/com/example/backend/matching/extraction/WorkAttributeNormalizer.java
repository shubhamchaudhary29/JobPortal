package com.example.backend.matching.extraction;

import com.example.backend.job.domain.WorkMode;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class WorkAttributeNormalizer {
    public WorkMode workMode(String location, String title, String description) {
        String text = safe(location) + " " + safe(title) + " " + safe(description);
        if (text.matches(".*\\bhybrid\\b.*")) return WorkMode.HYBRID;
        if (text.matches(".*\\b(remote|work from home|wfh)\\b.*")) return WorkMode.REMOTE;
        if (text.matches(".*\\b(on[ -]?site|in office|office based)\\b.*")) return WorkMode.ONSITE;
        if (location != null && !location.isBlank()) return WorkMode.ONSITE;
        return WorkMode.UNKNOWN;
    }

    public String employmentType(String structured, String title, String description) {
        String text = safe(structured) + " " + safe(title) + " " + safe(description);
        if (text.matches(".*\\b(intern|internship|trainee)\\b.*")) return "INTERNSHIP";
        if (text.matches(".*\\bpart[ -]?time\\b.*")) return "PART_TIME";
        if (text.matches(".*\\b(contract|contractor|temporary|freelance)\\b.*")) return "CONTRACT";
        if (text.matches(".*\\b(full[ -]?time|permanent)\\b.*")) return "FULL_TIME";
        return null;
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
