package com.example.backend.matching.extraction;

import com.example.backend.job.domain.Seniority;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ExperienceRequirementParser {
    private static final Pattern RANGE = Pattern.compile("(?i)\\b(\\d{1,2})\\s*(?:-|–|—|to)\\s*(\\d{1,2})\\s*(?:years?|yrs?)\\b");
    private static final Pattern PLUS = Pattern.compile("(?i)\\b(\\d{1,2})\\s*\\+\\s*(?:years?|yrs?)\\b");
    private static final Pattern MINIMUM = Pattern.compile("(?i)\\b(?:minimum|min\\.?|at least)\\s*(?:of\\s*)?(\\d{1,2})\\s*(?:years?|yrs?)\\b");
    private static final Pattern SIMPLE = Pattern.compile("(?i)\\b(\\d{1,2})\\s*(?:years?|yrs?)\\s+(?:of\\s+)?experience\\b");

    public Requirement parse(String title, String description, double structuredYears, Seniority seniority) {
        String text = safe(title) + " " + safe(description);
        Matcher range = RANGE.matcher(text);
        if (range.find()) return new Requirement(months(range.group(1)), months(range.group(2)));
        Matcher plus = PLUS.matcher(text);
        if (plus.find()) return new Requirement(months(plus.group(1)), null);
        Matcher minimum = MINIMUM.matcher(text);
        if (minimum.find()) return new Requirement(months(minimum.group(1)), null);
        Matcher simple = SIMPLE.matcher(text);
        if (simple.find()) return new Requirement(months(simple.group(1)), null);
        if (text.matches(".*\\b(fresher|entry[ -]?level|no experience|required experience: 0)\\b.*")) return new Requirement(0, 24);
        if (structuredYears > 0) return new Requirement((int) Math.round(structuredYears * 12), null);
        if (seniority == Seniority.INTERN || seniority == Seniority.ENTRY) return new Requirement(0, 24);
        if (seniority == Seniority.JUNIOR) return new Requirement(0, 36);
        if (seniority == Seniority.MID) return new Requirement(36, null);
        if (seniority == Seniority.SENIOR) return new Requirement(60, null);
        if (seniority == Seniority.LEAD) return new Requirement(84, null);
        return new Requirement(null, null);
    }

    private int months(String years) { return Integer.parseInt(years) * 12; }
    private String safe(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
    public record Requirement(Integer minimumMonths, Integer maximumMonths) { }
}
