package com.example.backend.matching.application;

import com.example.backend.candidate.infrastructure.CandidateProfileDocument;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

@Component
public class CandidateExperienceCalculator {
    private final Clock clock;
    public CandidateExperienceCalculator(Clock clock) { this.clock = clock; }

    public OptionalInt totalMonths(List<CandidateProfileDocument.Experience> experiences) {
        if (experiences == null || experiences.isEmpty()) return OptionalInt.empty();
        Set<YearMonth> months = new HashSet<>();
        YearMonth now = YearMonth.from(clock.instant().atZone(ZoneOffset.UTC));
        for (CandidateProfileDocument.Experience experience : experiences) {
            YearMonth start = parse(experience.getStartDate(), false);
            YearMonth end = experience.isCurrentlyWorking() ? now : parse(experience.getEndDate(), true);
            if (start == null || end == null || end.isBefore(start) || start.isBefore(YearMonth.of(1950, 1))
                    || end.isAfter(now.plusMonths(1))) continue;
            YearMonth cursor = start;
            int bounded = 0;
            while (!cursor.isAfter(end) && bounded++ < 1_200) {
                months.add(cursor);
                cursor = cursor.plusMonths(1);
            }
        }
        return months.isEmpty() ? OptionalInt.empty() : OptionalInt.of(months.size());
    }

    private YearMonth parse(String value, boolean endOfYear) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        try {
            if (normalized.matches("\\d{4}")) return YearMonth.of(Integer.parseInt(normalized), endOfYear ? 12 : 1);
            return YearMonth.parse(normalized);
        } catch (DateTimeParseException | NumberFormatException ignored) {
            return null;
        }
    }
}
