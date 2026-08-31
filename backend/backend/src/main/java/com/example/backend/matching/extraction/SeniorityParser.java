package com.example.backend.matching.extraction;

import com.example.backend.job.domain.Seniority;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class SeniorityParser {
    private static final Pattern INTERN = Pattern.compile("\\b(intern|internship|trainee|apprentice)\\b");
    private static final Pattern ENTRY = Pattern.compile("\\b(entry[ -]?level|fresher|graduate)\\b");
    private static final Pattern JUNIOR = Pattern.compile("\\b(junior|jr\\.?)\\b");
    private static final Pattern MID = Pattern.compile("\\b(mid[ -]?level|mid|intermediate)\\b");
    private static final Pattern SENIOR = Pattern.compile("\\b(senior|sr\\.?)\\b");
    private static final Pattern LEAD = Pattern.compile("\\b(lead|principal|staff|architect|manager)\\b");
    private static final Pattern EXPERIENCE_RANGE = Pattern.compile("\\b(\\d{1,2})\\s*(?:-|–|—|to)\\s*(\\d{1,2})\\s*(?:years?|yrs?)\\b");
    private static final Pattern EXPERIENCE_MINIMUM = Pattern.compile("\\b(\\d{1,2})\\s*(?:\\+\\s*)?(?:years?|yrs?)\\s+(?:of\\s+)?experience\\b");

    public Seniority parse(String title, String description) {
        String normalizedTitle = safe(title);
        if (INTERN.matcher(normalizedTitle).find()) return Seniority.INTERN;
        if (ENTRY.matcher(normalizedTitle).find()) return Seniority.ENTRY;
        if (JUNIOR.matcher(normalizedTitle).find()) return Seniority.JUNIOR;
        if (MID.matcher(normalizedTitle).find()) return Seniority.MID;
        if (LEAD.matcher(normalizedTitle).find()) return Seniority.LEAD;
        if (SENIOR.matcher(normalizedTitle).find()) return Seniority.SENIOR;
        String beginning = safe(description);
        if (beginning.length() > 1000) beginning = beginning.substring(0, 1000);
        if (INTERN.matcher(beginning).find()) return Seniority.INTERN;
        if (ENTRY.matcher(beginning).find()) return Seniority.ENTRY;
        var range = EXPERIENCE_RANGE.matcher(beginning);
        if (range.find()) return fromMinimumYears(Integer.parseInt(range.group(1)));
        var minimum = EXPERIENCE_MINIMUM.matcher(beginning);
        if (minimum.find()) return fromMinimumYears(Integer.parseInt(minimum.group(1)));
        return Seniority.UNKNOWN;
    }

    private Seniority fromMinimumYears(int years) {
        if (years >= 5) return Seniority.SENIOR;
        if (years >= 3) return Seniority.MID;
        if (years >= 1) return Seniority.JUNIOR;
        return Seniority.ENTRY;
    }

    private String safe(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT); }
}
