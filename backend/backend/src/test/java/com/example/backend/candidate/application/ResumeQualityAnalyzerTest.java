package com.example.backend.candidate.application;

import com.example.backend.candidate.domain.ResumeParsingStatus;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ResumeQualityAnalyzerTest {
    private final ResumeQualityAnalyzer analyzer = new ResumeQualityAnalyzer();

    @Test
    void completeImpactOrientedResumeScoresWellWithDeterministicStrengths() {
        CandidateProfileDocument profile = new CandidateProfileDocument();
        profile.getResume().setParsingStatus(ResumeParsingStatus.PARSED);
        profile.setPhone("+91 98765 43210");
        profile.setProfessionalSummary("Backend engineer with five years of experience building reliable distributed services.");
        profile.setSkills(List.of(skill("Java"), skill("Spring Boot"), skill("Docker"), skill("AWS")));
        profile.setEducation(List.of(new CandidateProfileDocument.Education("Example University", "B.Tech", "CS", "2016", "2020", "8.5", null)));
        profile.setExperience(List.of(new CandidateProfileDocument.Experience("Example", "Engineer", null, null,
                "2020", null, true, "Reduced API latency by 35% while supporting 2 million requests per day.", List.of("Java"))));
        profile.getLinks().setGithub("https://github.com/example");
        profile.setExtractedTextLength(3000);
        var report = analyzer.analyze(profile, "candidate@example.test");
        assertTrue(report.qualityScore() >= 90);
        assertEquals("Resume Quality Score", report.scoreLabel());
        assertTrue(report.explanation().contains("not an official ATS score"));
        assertTrue(report.strengths().stream().anyMatch(value -> value.contains("measurable")));
    }

    @Test
    void sparseAndImageOnlyProfilesReceiveActionableBoundedIssues() {
        CandidateProfileDocument profile = new CandidateProfileDocument();
        profile.getResume().setParsingStatus(ResumeParsingStatus.OCR_REQUIRED);
        profile.setExtractedTextLength(0);
        var report = analyzer.analyze(profile, "candidate@example.test");
        assertTrue(report.qualityScore() >= 0 && report.qualityScore() < 40);
        assertTrue(report.issues().stream().anyMatch(issue -> issue.category().equals("ACCESSIBILITY") && issue.severity().equals("HIGH")));
        assertTrue(report.issues().stream().anyMatch(issue -> issue.message().contains("Neither experience nor projects")));
    }

    @Test
    void repeatedSparseDescriptionsAndMissingImpactAreDetected() {
        CandidateProfileDocument profile = new CandidateProfileDocument();
        profile.getResume().setParsingStatus(ResumeParsingStatus.PARSED);
        String repeated = "Responsible for working on assigned tasks with the engineering team.";
        profile.setExperience(List.of(
                new CandidateProfileDocument.Experience("A", "Engineer", null, null, null, null, false, repeated, List.of()),
                new CandidateProfileDocument.Experience("B", "Engineer", null, null, null, null, false, repeated, List.of())));
        var report = analyzer.analyze(profile, "candidate@example.test");
        assertTrue(report.issues().stream().anyMatch(issue -> issue.message().contains("Repeated")));
        assertTrue(report.issues().stream().anyMatch(issue -> issue.message().contains("measurable impact")));
    }

    private CandidateProfileDocument.Skill skill(String value) {
        return new CandidateProfileDocument.Skill(value, null, null, null, "MANUAL");
    }
}
