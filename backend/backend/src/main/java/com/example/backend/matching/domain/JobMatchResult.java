package com.example.backend.matching.domain;

import java.util.List;
import java.util.Map;

public record JobMatchResult(
        String jobId,
        double overallScore,
        MatchLevel matchLevel,
        DataConfidence dataConfidence,
        List<String> matchedSkills,
        List<String> missingSkills,
        List<String> optionalSkillsMatched,
        Double titleScore,
        Double skillScore,
        Double experienceScore,
        Double educationScore,
        Double locationScore,
        Double employmentTypeScore,
        Map<String, Double> normalizedWeights,
        List<String> strengths,
        List<String> gaps,
        List<String> explanation,
        String scoringVersion) { }
