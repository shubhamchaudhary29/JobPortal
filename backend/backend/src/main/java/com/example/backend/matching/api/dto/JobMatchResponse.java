package com.example.backend.matching.api.dto;

import com.example.backend.matching.domain.DataConfidence;
import com.example.backend.matching.domain.JobMatchResult;
import com.example.backend.matching.domain.MatchLevel;

import java.util.List;
import java.util.Map;

public record JobMatchResponse(
        String jobId,
        double overallScore,
        MatchLevel matchLevel,
        DataConfidence confidence,
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
        String scoringVersion) {

    public static JobMatchResponse from(JobMatchResult result) {
        return new JobMatchResponse(result.jobId(), result.overallScore(), result.matchLevel(), result.dataConfidence(),
                result.matchedSkills(), result.missingSkills(), result.optionalSkillsMatched(), result.titleScore(),
                result.skillScore(), result.experienceScore(), result.educationScore(), result.locationScore(),
                result.employmentTypeScore(), result.normalizedWeights(), result.strengths(), result.gaps(),
                result.explanation(), result.scoringVersion());
    }
}
