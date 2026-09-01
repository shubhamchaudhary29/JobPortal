package com.example.backend.copilot.application;

import com.example.backend.candidate.infrastructure.CandidateProfileDocument;
import com.example.backend.copilot.domain.CopilotModels.KeywordAnalysis;
import com.example.backend.copilot.domain.CopilotModels.KeywordEvidenceLevel;
import com.example.backend.copilot.domain.CopilotModels.KeywordFinding;
import com.example.backend.copilot.domain.CopilotModels.KeywordImportance;
import com.example.backend.copilot.domain.CopilotModels.ReadinessLevel;
import com.example.backend.copilot.domain.CopilotModels.ReadinessResult;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.matching.domain.JobMatchResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.backend.copilot.domain.CopilotModels.APPLICATION_READINESS_VERSION;

@Component
public class ApplicationReadinessEngine {
    public ReadinessResult calculate(CandidateProfileDocument profile, JobDocument job,
                                     JobMatchResult match, KeywordAnalysis keywords, boolean active) {
        List<KeywordFinding> all = all(keywords);
        List<KeywordFinding> required = all.stream().filter(value -> value.importance() == KeywordImportance.REQUIRED).toList();
        List<KeywordFinding> preferred = all.stream().filter(value -> value.importance() == KeywordImportance.PREFERRED).toList();
        LinkedHashMap<String, Weighted> components = new LinkedHashMap<>();
        if (!required.isEmpty()) components.put("requiredSkillCoverage", new Weighted(coverage(required), 30));
        if (!preferred.isEmpty()) components.put("preferredSkillCoverage", new Weighted(coverage(preferred), 10));
        if (!all.isEmpty()) components.put("evidenceStrength", new Weighted(evidenceStrength(all), 30));
        if (match.experienceScore() != null) components.put("experienceCompatibility", new Weighted(match.experienceScore(), 15));
        if (match.titleScore() != null) components.put("roleRelevance", new Weighted(match.titleScore(), 10));
        components.put("resumeCompleteness", new Weighted(completeness(profile), 10));
        components.put("professionalLinks", new Weighted(hasProfessionalLink(profile) ? 100 : 0, 5));
        double totalWeight = components.values().stream().mapToInt(Weighted::weight).sum();
        double score = totalWeight == 0 ? 0 : components.values().stream()
                .mapToDouble(value -> value.score() * value.weight()).sum() / totalWeight;

        List<KeywordFinding> missingRequired = required.stream()
                .filter(value -> value.evidenceLevel() == KeywordEvidenceLevel.MISSING).toList();
        if (!missingRequired.isEmpty()) score = Math.min(score, 69);
        if (!active) score = 0;
        score = round(score);
        double evidenceCoverage = all.isEmpty() ? 0 : round(all.stream().filter(value ->
                value.evidenceLevel() == KeywordEvidenceLevel.STRONG
                        || value.evidenceLevel() == KeywordEvidenceLevel.SUPPORTED).count() * 100.0 / all.size());

        List<String> strengths = strengths(keywords, match, profile);
        List<String> blockers = blockers(missingRequired, match, profile, active, job);
        List<String> recommendations = recommendations(keywords, profile);
        boolean sparse = sparse(profile) || (all.isEmpty() && (job.getDescription() == null || job.getDescription().isBlank()));
        ReadinessLevel level = !active ? ReadinessLevel.INACTIVE : sparse ? ReadinessLevel.LOW_DATA
                : score >= 85 ? ReadinessLevel.READY : score >= 70 ? ReadinessLevel.NEARLY_READY
                : score >= 50 ? ReadinessLevel.NEEDS_WORK : ReadinessLevel.NOT_READY;
        Map<String, Double> componentScores = new LinkedHashMap<>();
        components.forEach((name, value) -> componentScores.put(name, round(value.score())));
        return new ReadinessResult(score, level, List.copyOf(strengths), List.copyOf(blockers),
                List.copyOf(recommendations), evidenceCoverage, Map.copyOf(componentScores), Instant.now(),
                APPLICATION_READINESS_VERSION, active,
                "Application Readiness is a deterministic preparation aid, not an official ATS score or interview probability.");
    }

    private List<String> strengths(KeywordAnalysis keywords, JobMatchResult match, CandidateProfileDocument profile) {
        List<String> values = new ArrayList<>();
        if (!keywords.strong().isEmpty()) values.add("Strong job-relevant evidence supports: " + names(keywords.strong()) + ".");
        if (!keywords.supported().isEmpty()) values.add("Project or education evidence supports: " + names(keywords.supported()) + ".");
        if (match.experienceScore() != null && match.experienceScore() >= 80)
            values.add("Recorded experience is compatible with the stated requirement.");
        if (notBlank(profile == null ? null : profile.getProfessionalSummary())) values.add("A professional summary is available for tailoring.");
        return values;
    }

    private List<String> blockers(List<KeywordFinding> missingRequired, JobMatchResult match,
                                  CandidateProfileDocument profile, boolean active, JobDocument job) {
        List<String> values = new ArrayList<>();
        if (!active) values.add("This job is no longer active; application preparation is retained for history only.");
        if (!missingRequired.isEmpty()) values.add("Required skills missing from your profile: " + names(missingRequired) + ".");
        if (match.experienceScore() != null && match.experienceScore() < 50)
            values.add("Recorded experience is below the job's stated requirement.");
        if (sparse(profile)) values.add("The profile is too sparse for a confident readiness assessment.");
        if (job.getDescription() == null || job.getDescription().isBlank())
            values.add("The job has no usable description, so requirement coverage is limited.");
        return values;
    }

    private List<String> recommendations(KeywordAnalysis keywords, CandidateProfileDocument profile) {
        List<String> values = new ArrayList<>();
        if (!keywords.underrepresented().isEmpty())
            values.add("Add truthful project or experience detail for underrepresented skills: "
                    + names(keywords.underrepresented()) + ".");
        if (!keywords.missing().isEmpty())
            values.add("Review missing requirements; do not add them unless you can document real evidence.");
        if (!hasProfessionalLink(profile)) values.add("Add a relevant professional link if one is available.");
        if (!notBlank(profile == null ? null : profile.getProfessionalSummary()))
            values.add("Add an evidence-based professional summary before applying.");
        return values;
    }

    private double coverage(List<KeywordFinding> values) {
        return values.stream().mapToDouble(value -> switch (value.evidenceLevel()) {
            case STRONG, SUPPORTED -> 100; case UNDERREPRESENTED -> 50; case MISSING -> 0;
        }).average().orElse(0);
    }

    private double evidenceStrength(List<KeywordFinding> values) {
        return values.stream().mapToDouble(value -> switch (value.evidenceLevel()) {
            case STRONG -> 100; case SUPPORTED -> 75; case UNDERREPRESENTED -> 35; case MISSING -> 0;
        }).average().orElse(0);
    }

    private double completeness(CandidateProfileDocument profile) {
        if (profile == null) return 0;
        double score = 0;
        if (notBlank(profile.getProfessionalSummary())) score += 20;
        if (!list(profile.getSkills()).isEmpty()) score += 20;
        if (list(profile.getExperience()).stream().anyMatch(value -> notBlank(value.getDescription()))) score += 25;
        if (list(profile.getProjects()).stream().anyMatch(value -> notBlank(value.getDescription()))) score += 20;
        if (!list(profile.getEducation()).isEmpty()) score += 15;
        return score;
    }

    private boolean hasProfessionalLink(CandidateProfileDocument profile) {
        if (profile == null || profile.getLinks() == null) return false;
        var links = profile.getLinks();
        return notBlank(links.getLinkedIn()) || notBlank(links.getGithub()) || notBlank(links.getPortfolio())
                || notBlank(links.getWebsite()) || !list(links.getOther()).isEmpty();
    }

    private boolean sparse(CandidateProfileDocument profile) {
        return profile == null || (list(profile.getSkills()).isEmpty() && list(profile.getExperience()).isEmpty()
                && list(profile.getProjects()).isEmpty() && !notBlank(profile.getProfessionalSummary()));
    }

    private List<KeywordFinding> all(KeywordAnalysis analysis) {
        List<KeywordFinding> values = new ArrayList<>();
        values.addAll(analysis.strong()); values.addAll(analysis.supported());
        values.addAll(analysis.underrepresented()); values.addAll(analysis.missing());
        return values;
    }
    private String names(List<KeywordFinding> values) { return String.join(", ", values.stream().map(KeywordFinding::keyword).toList()); }
    private boolean notBlank(String value) { return value != null && !value.isBlank(); }
    private <T> List<T> list(List<T> values) { return values == null ? List.of() : values; }
    private double round(double value) { return Math.round(Math.max(0, Math.min(100, value)) * 10.0) / 10.0; }
    private record Weighted(double score, int weight) { }
}
