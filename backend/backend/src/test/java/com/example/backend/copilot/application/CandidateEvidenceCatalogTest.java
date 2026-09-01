package com.example.backend.copilot.application;

import com.example.backend.candidate.application.parsing.SkillNormalizer;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument;
import com.example.backend.copilot.domain.CopilotModels.KeywordEvidenceLevel;
import com.example.backend.copilot.domain.CopilotModels.KeywordFinding;
import com.example.backend.copilot.domain.CopilotModels.KeywordImportance;
import com.example.backend.job.domain.JobMatchFeatures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CandidateEvidenceCatalogTest {
    private final CandidateEvidenceCatalog catalog = new CandidateEvidenceCatalog(new SkillNormalizer());

    @Test
    void classifiesWorkProjectListedAndMissingEvidenceByRequirementImportance() {
        CandidateProfileDocument profile = new CandidateProfileDocument();
        profile.setSkills(List.of(skill("Java"), skill("AWS"), skill("JavaScript")));
        profile.setExperience(List.of(experience("Backend Engineer", "Built Java and Spring Boot REST APIs.",
                List.of("Java", "Spring Boot"))));
        profile.setProjects(List.of(project("Containerized JobPortal", "Packaged the service for repeatable deployment.",
                List.of("Docker"))));
        JobMatchFeatures features = new JobMatchFeatures();
        features.setRequiredSkills(List.of("Java", "Spring Boot", "Kafka", "Java"));
        features.setPreferredSkills(List.of("Docker", "AWS", "Kubernetes"));
        features.setNormalizedSkills(List.of("Java", "Spring Boot", "Kafka", "Docker", "AWS", "Kubernetes", "JavaScript"));

        Map<String, KeywordFinding> findings = all(catalog.analyze(profile, features).keywords()).stream()
                .collect(Collectors.toMap(KeywordFinding::keyword, Function.identity()));
        assertAll(
                () -> assertEquals(KeywordEvidenceLevel.STRONG, findings.get("Java").evidenceLevel()),
                () -> assertEquals(KeywordEvidenceLevel.STRONG, findings.get("Spring Boot").evidenceLevel()),
                () -> assertEquals(KeywordEvidenceLevel.SUPPORTED, findings.get("Docker").evidenceLevel()),
                () -> assertEquals(KeywordEvidenceLevel.UNDERREPRESENTED, findings.get("AWS").evidenceLevel()),
                () -> assertEquals(KeywordEvidenceLevel.MISSING, findings.get("Kafka").evidenceLevel()),
                () -> assertEquals(KeywordImportance.REQUIRED, findings.get("Kafka").importance()),
                () -> assertEquals(KeywordImportance.PREFERRED, findings.get("Kubernetes").importance()),
                () -> assertEquals(KeywordEvidenceLevel.UNDERREPRESENTED, findings.get("JavaScript").evidenceLevel()),
                () -> assertEquals(7, findings.size()),
                () -> assertTrue(findings.get("Java").evidence().stream().anyMatch(value -> "EXPERIENCE".equals(value.evidenceType())))
        );
    }

    @Test
    void aliasNormalizationPreservesJavaJavascriptAndSpringBootBoundaries() {
        CandidateProfileDocument profile = new CandidateProfileDocument();
        profile.setExperience(List.of(experience("Engineer", "Used JS and Spring Boot.", List.of())));
        JobMatchFeatures features = new JobMatchFeatures();
        features.setRequiredSkills(List.of("Java", "JavaScript", "Spring", "Spring Boot"));
        features.setNormalizedSkills(features.getRequiredSkills());

        Map<String, KeywordEvidenceLevel> levels = all(catalog.analyze(profile, features).keywords()).stream()
                .collect(Collectors.toMap(KeywordFinding::keyword, KeywordFinding::evidenceLevel));
        assertAll(
                () -> assertEquals(KeywordEvidenceLevel.MISSING, levels.get("Java")),
                () -> assertEquals(KeywordEvidenceLevel.STRONG, levels.get("JavaScript")),
                () -> assertEquals(KeywordEvidenceLevel.MISSING, levels.get("Spring")),
                () -> assertEquals(KeywordEvidenceLevel.STRONG, levels.get("Spring Boot"))
        );
    }

    private List<KeywordFinding> all(com.example.backend.copilot.domain.CopilotModels.KeywordAnalysis value) {
        List<KeywordFinding> all = new ArrayList<>(); all.addAll(value.strong()); all.addAll(value.supported());
        all.addAll(value.underrepresented()); all.addAll(value.missing()); return all;
    }
    private CandidateProfileDocument.Skill skill(String name) { return new CandidateProfileDocument.Skill(name, null, null, null, "MANUAL"); }
    private CandidateProfileDocument.Experience experience(String title, String description, List<String> technologies) {
        return new CandidateProfileDocument.Experience("Acme", title, null, null, "2024-01", null, true, description, technologies);
    }
    private CandidateProfileDocument.Project project(String name, String description, List<String> technologies) {
        return new CandidateProfileDocument.Project(name, description, technologies, null, null, null);
    }
}
