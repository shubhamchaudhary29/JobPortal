package com.example.backend.candidate.application.parsing;

import com.example.backend.candidate.infrastructure.CandidateProfileDocument.Education;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class EducationParser {
    private static final Pattern DEGREE = Pattern.compile("(?i)\\b(bachelor|master|doctor|ph\\.?d|b\\.?tech|m\\.?tech|b\\.?e|m\\.?e|b\\.?sc|m\\.?sc|bca|mca|mba|diploma|associate|higher secondary)\\b");
    private static final Pattern GRADE = Pattern.compile("(?i)\\b(?:cgpa|gpa|grade|percentage)\\s*[:=-]?\\s*([0-9.]+%?(?:\\s*/\\s*[0-9.]+)?)");

    public List<Education> parse(List<String> lines) {
        List<Education> result = new ArrayList<>();
        for (List<String> block : ParsingSupport.blocks(lines)) {
            if (block.isEmpty()) continue;
            String institution = block.stream().filter(this::looksInstitution).findFirst().orElse(block.get(0));
            String degree = block.stream().filter(line -> DEGREE.matcher(line).find()).findFirst().orElse(null);
            if (degree == null && block.size() == 1) degree = block.get(0);
            String field = fieldOfStudy(degree);
            ParsingSupport.DateRange dates = ParsingSupport.dates(block);
            String grade = block.stream().map(this::grade).filter(value -> value != null).findFirst().orElse(null);
            result.add(new Education(institution, degree, field, dates.start(), dates.end(), grade,
                    block.size() > 2 ? ParsingSupport.join(block, 2) : null));
        }
        return result;
    }

    private boolean looksInstitution(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("university") || lower.contains("college") || lower.contains("institute")
                || lower.contains("school") || lower.contains("academy");
    }

    private String fieldOfStudy(String degree) {
        if (degree == null) return null;
        Matcher matcher = Pattern.compile("(?i)\\b(?:in|of)\\s+([\\p{L} &.-]{2,80})").matcher(degree);
        return matcher.find() ? matcher.group(1).replaceAll("\\s+(?:19|20)\\d{2}.*$", "").strip() : null;
    }

    private String grade(String line) {
        Matcher matcher = GRADE.matcher(line);
        return matcher.find() ? matcher.group(1).replaceAll("\\s+", "") : null;
    }
}
