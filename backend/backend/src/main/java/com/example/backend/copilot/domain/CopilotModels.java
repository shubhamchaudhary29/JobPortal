package com.example.backend.copilot.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class CopilotModels {
    public static final String APPLICATION_READINESS_VERSION = "application-readiness-1.3.0";
    public static final String TAILORING_VERSION = "resume-tailoring-1.3.0";
    public static final int MAX_VERSIONS_PER_JOB = 25;

    private CopilotModels() { }

    public enum KeywordImportance { REQUIRED, PREFERRED, CONTEXTUAL }
    public enum KeywordEvidenceLevel { STRONG, SUPPORTED, UNDERREPRESENTED, MISSING }
    public enum ReadinessLevel { READY, NEARLY_READY, NEEDS_WORK, NOT_READY, LOW_DATA, INACTIVE }
    public enum ResumeStaleness { CURRENT, OUTDATED }
    public enum TailoringActionType {
        EMPHASIZE_SKILL, REORDER_SKILL, PRIORITIZE_PROJECT, PRIORITIZE_EXPERIENCE,
        SUMMARY_FOCUS, BULLET_REWRITE, MISSING_REQUIREMENT, UNDERREPRESENTED_SKILL
    }
    public enum PersonalApplicationStage {
        SAVED, PREPARING, APPLIED, OA, INTERVIEW, OFFER, REJECTED, WITHDRAWN
    }

    public record EvidenceReference(
            String evidenceType,
            String sourceField,
            String sourceText,
            List<String> normalizedSkills) { }

    public record KeywordFinding(
            String keyword,
            KeywordImportance importance,
            KeywordEvidenceLevel evidenceLevel,
            List<EvidenceReference> evidence) { }

    public record KeywordAnalysis(
            List<KeywordFinding> strong,
            List<KeywordFinding> supported,
            List<KeywordFinding> underrepresented,
            List<KeywordFinding> missing,
            List<KeywordFinding> present) { }

    public record ReadinessResult(
            double readinessScore,
            ReadinessLevel readinessLevel,
            List<String> strengths,
            List<String> blockers,
            List<String> recommendations,
            double evidenceCoverage,
            Map<String, Double> components,
            Instant calculatedAt,
            String version,
            boolean active,
            String disclaimer) { }

    public record TailoringAction(
            TailoringActionType type,
            String subject,
            String rationale,
            List<EvidenceReference> evidence) { }

    public record TailoringPlan(
            List<TailoringAction> actions,
            List<String> emphasize,
            List<String> missingRequirements,
            String version) { }

    public record JobSnapshot(
            String jobId,
            String title,
            String company,
            String location,
            String source,
            String employmentType,
            String applicationUrl) { }

    public record ResumeContent(
            String fullName,
            String email,
            String phone,
            String location,
            String summary,
            List<String> skills,
            List<ResumeExperience> experience,
            List<ResumeProject> projects,
            List<ResumeEducation> education,
            List<ResumeCertification> certifications,
            ResumeLinks links,
            List<String> sectionOrder) { }

    public record ResumeExperience(
            String organization,
            String title,
            String employmentType,
            String location,
            String startDate,
            String endDate,
            boolean currentlyWorking,
            String description,
            List<String> technologies) { }

    public record ResumeProject(
            String name,
            String description,
            List<String> technologies,
            String url,
            String startDate,
            String endDate) { }

    public record ResumeEducation(
            String institution,
            String degree,
            String fieldOfStudy,
            String startDate,
            String endDate,
            String grade,
            String description) { }

    public record ResumeCertification(String name, String issuer, String issueDate, String credentialUrl) { }
    public record ResumeLinks(String linkedIn, String github, String portfolio, String website, List<String> other) { }
}
