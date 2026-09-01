package com.example.backend.copilot.application;

import com.example.backend.candidate.infrastructure.CandidateProfileDocument;
import com.example.backend.copilot.domain.CopilotModels.EvidenceReference;
import com.example.backend.copilot.domain.CopilotModels.KeywordAnalysis;
import com.example.backend.copilot.domain.CopilotModels.KeywordEvidenceLevel;
import com.example.backend.copilot.domain.CopilotModels.KeywordFinding;
import com.example.backend.copilot.domain.CopilotModels.KeywordImportance;
import com.example.backend.copilot.domain.CopilotModels.ReadinessLevel;
import com.example.backend.copilot.domain.CopilotModels.ReadinessResult;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.matching.domain.DataConfidence;
import com.example.backend.matching.domain.JobMatchResult;
import com.example.backend.matching.domain.MatchLevel;
import com.example.backend.user.domain.UserRole;
import com.example.backend.user.infrastructure.UserDocument;

import java.time.Instant;
import java.util.List;
import java.util.Map;

final class CopilotTestFixtures {
    private CopilotTestFixtures() { }

    static ApplicationCopilotAnalysisService.AnalysisBundle bundle() {
        CandidateProfileDocument profile = profile();
        UserDocument user = new UserDocument(); user.setId("user-1"); user.setEmail("candidate@example.test");
        user.setFullName("Candidate Name"); user.setRole(UserRole.USER);
        JobDocument job = new JobDocument(); job.setId("job-1"); job.setTitle("Backend Engineer");
        job.setCompany("Example Corp"); job.setLocation("Remote"); job.setSource("manual"); job.setActive(true);
        KeywordAnalysis keywords = keywords();
        JobMatchResult match = new JobMatchResult("job-1", 82.0, MatchLevel.STRONG, DataConfidence.HIGH,
                List.of("Java", "Docker", "AWS"), List.of("Kafka"), List.of(), 90.0, 75.0, 80.0,
                null, null, null, Map.of("skills", 50.0), List.of(), List.of(), List.of(), "job-match-1.2.0");
        ReadinessResult readiness = new ReadinessResult(72, ReadinessLevel.NEARLY_READY, List.of("Java evidence"),
                List.of("Kafka is missing"), List.of("Do not add Kafka without evidence"), 50, Map.of(), Instant.now(),
                "application-readiness-1.3.0", true, "Not an official ATS score");
        var context = new CopilotAccessService.CandidateContext(user, profile);
        return new ApplicationCopilotAnalysisService.AnalysisBundle(context, job, match, keywords, readiness,
                new TailoringPlanEngine().create(profile, keywords));
    }

    static CandidateProfileDocument profile() {
        CandidateProfileDocument profile = new CandidateProfileDocument();
        profile.setUpdatedAt(Instant.parse("2026-08-01T00:00:00Z"));
        profile.setPhone("+91 99999 99999"); profile.setLocation("Delhi");
        profile.setProfessionalSummary("Backend engineer improving reliable APIs.");
        profile.setSkills(List.of(skill("AWS"), skill("Java"), skill("Docker"), skill("Spring Boot")));
        profile.setExperience(List.of(
                new CandidateProfileDocument.Experience("Acme", "Backend Engineer", null, "Delhi", "2024-01", null,
                        true, "Improved API performance", List.of("Java", "Spring Boot")),
                new CandidateProfileDocument.Experience("Beta", "Intern", "INTERNSHIP", null, "2023-01", "2023-06",
                        false, "Reduced latency by 20%", List.of("Java"))));
        profile.setProjects(List.of(
                new CandidateProfileDocument.Project("Unrelated Notes", "Created a writing sample.", List.of(), null, null, null),
                new CandidateProfileDocument.Project("JobPortal", "Containerized the application for deployment.",
                        List.of("Docker"), "https://example.test/project", null, null)));
        profile.setEducation(List.of(new CandidateProfileDocument.Education("College", "B.Tech", "CSE",
                "2020", "2024", null, null)));
        profile.setCertifications(List.of());
        profile.setLinks(new CandidateProfileDocument.ProfessionalLinks(null, "https://github.com/candidate", null, null, List.of()));
        return profile;
    }

    static KeywordAnalysis keywords() {
        KeywordFinding java = finding("Java", KeywordImportance.REQUIRED, KeywordEvidenceLevel.STRONG,
                "EXPERIENCE", "Improved API performance");
        KeywordFinding docker = finding("Docker", KeywordImportance.PREFERRED, KeywordEvidenceLevel.SUPPORTED,
                "PROJECT", "JobPortal — Containerized the application for deployment.");
        KeywordFinding aws = finding("AWS", KeywordImportance.PREFERRED, KeywordEvidenceLevel.UNDERREPRESENTED,
                "SKILL", "AWS");
        KeywordFinding kafka = finding("Kafka", KeywordImportance.REQUIRED, KeywordEvidenceLevel.MISSING, null, null);
        KeywordFinding kubernetes = finding("Kubernetes", KeywordImportance.CONTEXTUAL, KeywordEvidenceLevel.MISSING, null, null);
        return new KeywordAnalysis(List.of(java), List.of(docker), List.of(aws), List.of(kafka, kubernetes),
                List.of(java, docker, aws));
    }

    private static CandidateProfileDocument.Skill skill(String name) {
        return new CandidateProfileDocument.Skill(name, null, null, null, "MANUAL");
    }
    private static KeywordFinding finding(String keyword, KeywordImportance importance, KeywordEvidenceLevel level,
                                          String type, String source) {
        return new KeywordFinding(keyword, importance, level, type == null ? List.of()
                : List.of(new EvidenceReference(type, type.equals("PROJECT") ? "projects.description" : "experience.description",
                source, List.of(keyword))));
    }
}
