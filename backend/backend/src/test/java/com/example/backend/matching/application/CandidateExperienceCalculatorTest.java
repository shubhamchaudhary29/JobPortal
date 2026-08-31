package com.example.backend.matching.application;

import com.example.backend.candidate.infrastructure.CandidateProfileDocument;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CandidateExperienceCalculatorTest {
    private final CandidateExperienceCalculator calculator = new CandidateExperienceCalculator(
            Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void overlappingIntervalsAreCountedOnceAndCurrentEmploymentUsesFixedClock() {
        var first = experience("2025-01", "2025-12", false);
        var overlapping = experience("2025-06", "2025-12", false);
        var current = experience("2026-01", null, true);
        assertEquals(20, calculator.totalMonths(List.of(first, overlapping, current)).orElseThrow());
    }

    @Test
    void handlesYearOnlyAndIgnoresInvalidOrMissingRanges() {
        assertEquals(12, calculator.totalMonths(List.of(experience("2024", "2024", false),
                experience("2026-01", "2025-01", false), experience(null, "2025-01", false))).orElseThrow());
        assertTrue(calculator.totalMonths(List.of(experience("bad", "also-bad", false))).isEmpty());
    }

    private CandidateProfileDocument.Experience experience(String start, String end, boolean current) {
        var value = new CandidateProfileDocument.Experience();
        value.setStartDate(start); value.setEndDate(end); value.setCurrentlyWorking(current);
        return value;
    }
}
