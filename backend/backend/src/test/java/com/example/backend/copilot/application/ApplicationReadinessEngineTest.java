package com.example.backend.copilot.application;

import com.example.backend.candidate.infrastructure.CandidateProfileDocument;
import com.example.backend.copilot.domain.CopilotModels.EvidenceReference;
import com.example.backend.copilot.domain.CopilotModels.KeywordAnalysis;
import com.example.backend.copilot.domain.CopilotModels.KeywordEvidenceLevel;
import com.example.backend.copilot.domain.CopilotModels.KeywordFinding;
import com.example.backend.copilot.domain.CopilotModels.KeywordImportance;
import com.example.backend.copilot.domain.CopilotModels.ReadinessLevel;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.matching.domain.DataConfidence;
import com.example.backend.matching.domain.JobMatchResult;
import com.example.backend.matching.domain.MatchLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationReadinessEngineTest {
    private final ApplicationReadinessEngine engine = new ApplicationReadinessEngine();

    @Test
    void highlyReadyCandidateUsesEvidenceAndRemainsDistinctFromMatchScore() {
        CandidateProfileDocument profile = completeProfile();
        KeywordAnalysis keywords = keywords(List.of(finding("Java", KeywordImportance.REQUIRED, KeywordEvidenceLevel.STRONG),
                finding("Docker", KeywordImportance.PREFERRED, KeywordEvidenceLevel.SUPPORTED)));
        var result = engine.calculate(profile, job("Detailed role description"), match(96, 100.0, 100.0), keywords, true);
        assertAll(
                () -> assertTrue(result.readinessScore() >= 80 && result.readinessScore() <= 100),
                () -> assertNotEquals(96.0, result.readinessScore()),
                () -> assertTrue(List.of(ReadinessLevel.READY, ReadinessLevel.NEARLY_READY).contains(result.readinessLevel())),
                () -> assertEquals(100, result.evidenceCoverage()),
                () -> assertTrue(result.disclaimer().contains("not an official ATS score"))
        );
    }

    @Test
    void highMatchWithWeakResumeEvidenceHasLowerReadiness() {
        CandidateProfileDocument profile = completeProfile();
        KeywordAnalysis keywords = keywords(List.of(finding("Java", KeywordImportance.REQUIRED, KeywordEvidenceLevel.UNDERREPRESENTED),
                finding("AWS", KeywordImportance.REQUIRED, KeywordEvidenceLevel.UNDERREPRESENTED)));
        var result = engine.calculate(profile, job("Java and AWS required"), match(91, 100.0, 100.0), keywords, true);
        assertTrue(result.readinessScore() < 80);
        assertEquals(0, result.evidenceCoverage());
        assertTrue(result.recommendations().stream().anyMatch(value -> value.contains("underrepresented")));
    }

    @Test
    void missingCriticalRequirementCapsScoreAndInactiveJobIsNotReady() {
        KeywordAnalysis keywords = keywords(List.of(finding("Kafka", KeywordImportance.REQUIRED, KeywordEvidenceLevel.MISSING)));
        var active = engine.calculate(completeProfile(), job("Kafka required"), match(90, 100.0, 100.0), keywords, true);
        var inactive = engine.calculate(completeProfile(), job("Kafka required"), match(90, 100.0, 100.0), keywords, false);
        assertAll(
                () -> assertTrue(active.readinessScore() <= 69),
                () -> assertTrue(active.blockers().stream().anyMatch(value -> value.contains("Kafka"))),
                () -> assertEquals(0, inactive.readinessScore()),
                () -> assertEquals(ReadinessLevel.INACTIVE, inactive.readinessLevel())
        );
    }

    @Test
    void sparseFresherAndMissingDescriptionStayBoundedAndDeterministic() {
        CandidateProfileDocument sparse = new CandidateProfileDocument();
        JobDocument job = job("");
        JobMatchResult match = match(0, null, null);
        KeywordAnalysis empty = keywords(List.of());
        var first = engine.calculate(sparse, job, match, empty, true);
        var second = engine.calculate(sparse, job, match, empty, true);
        assertAll(
                () -> assertEquals(ReadinessLevel.LOW_DATA, first.readinessLevel()),
                () -> assertTrue(first.readinessScore() >= 0 && first.readinessScore() <= 100),
                () -> assertEquals(first.readinessScore(), second.readinessScore()),
                () -> assertEquals(first.components(), second.components()),
                () -> assertTrue(first.blockers().stream().anyMatch(value -> value.contains("no usable description")))
        );
    }

    private CandidateProfileDocument completeProfile() {
        CandidateProfileDocument profile = new CandidateProfileDocument();
        profile.setProfessionalSummary("Backend engineer focused on reliable APIs.");
        profile.setSkills(List.of(new CandidateProfileDocument.Skill("Java", null, null, null, "MANUAL")));
        profile.setExperience(List.of(new CandidateProfileDocument.Experience("Acme", "Engineer", null, null,
                "2024-01", null, true, "Built reliable Java APIs.", List.of("Java"))));
        profile.setProjects(List.of(new CandidateProfileDocument.Project("Portal", "Containerized the service.",
                List.of("Docker"), null, null, null)));
        profile.setEducation(List.of(new CandidateProfileDocument.Education("College", "B.Tech", "CSE", null, null, null, null)));
        profile.setLinks(new CandidateProfileDocument.ProfessionalLinks(null, "https://github.com/candidate", null, null, List.of()));
        return profile;
    }
    private JobDocument job(String description) { JobDocument job = new JobDocument(); job.setDescription(description); return job; }
    private JobMatchResult match(double overall, Double experience, Double title) {
        return new JobMatchResult("job-1", overall, MatchLevel.STRONG, DataConfidence.HIGH, List.of(), List.of(), List.of(),
                title, 100.0, experience, null, null, null, Map.of(), List.of(), List.of(), List.of(), "job-match-1.2.0");
    }
    private KeywordFinding finding(String name, KeywordImportance importance, KeywordEvidenceLevel level) {
        return new KeywordFinding(name, importance, level, level == KeywordEvidenceLevel.MISSING ? List.of()
                : List.of(new EvidenceReference("EXPERIENCE", "experience.description", name, List.of(name))));
    }
    private KeywordAnalysis keywords(List<KeywordFinding> values) {
        return new KeywordAnalysis(values.stream().filter(value -> value.evidenceLevel() == KeywordEvidenceLevel.STRONG).toList(),
                values.stream().filter(value -> value.evidenceLevel() == KeywordEvidenceLevel.SUPPORTED).toList(),
                values.stream().filter(value -> value.evidenceLevel() == KeywordEvidenceLevel.UNDERREPRESENTED).toList(),
                values.stream().filter(value -> value.evidenceLevel() == KeywordEvidenceLevel.MISSING).toList(),
                values.stream().filter(value -> value.evidenceLevel() != KeywordEvidenceLevel.MISSING).toList());
    }
}
