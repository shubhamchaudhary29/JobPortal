package com.example.backend.copilot.application;

import com.example.backend.candidate.application.parsing.SkillNormalizer;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument;
import com.example.backend.copilot.domain.CopilotModels.EvidenceReference;
import com.example.backend.copilot.domain.CopilotModels.KeywordAnalysis;
import com.example.backend.copilot.domain.CopilotModels.KeywordEvidenceLevel;
import com.example.backend.copilot.domain.CopilotModels.KeywordFinding;
import com.example.backend.copilot.domain.CopilotModels.KeywordImportance;
import com.example.backend.job.domain.JobMatchFeatures;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
public class CandidateEvidenceCatalog {
    private static final int EVIDENCE_TEXT_LIMIT = 600;
    private final SkillNormalizer skills;

    public CandidateEvidenceCatalog(SkillNormalizer skills) { this.skills = skills; }

    public Analysis analyze(CandidateProfileDocument profile, JobMatchFeatures features) {
        Map<String, List<RankedEvidence>> catalog = build(profile);
        LinkedHashMap<String, KeywordImportance> jobKeywords = jobKeywords(features);
        List<KeywordFinding> findings = new ArrayList<>();
        jobKeywords.forEach((keyword, importance) -> {
            List<RankedEvidence> evidence = catalog.getOrDefault(key(keyword), List.of());
            int rank = evidence.stream().mapToInt(RankedEvidence::rank).max().orElse(0);
            KeywordEvidenceLevel level = rank >= 3 ? KeywordEvidenceLevel.STRONG
                    : rank == 2 ? KeywordEvidenceLevel.SUPPORTED
                    : rank == 1 ? KeywordEvidenceLevel.UNDERREPRESENTED : KeywordEvidenceLevel.MISSING;
            List<EvidenceReference> references = evidence.stream().sorted(Comparator.comparingInt(RankedEvidence::rank).reversed())
                    .map(RankedEvidence::reference).distinct().limit(5).toList();
            findings.add(new KeywordFinding(keyword, importance, level, references));
        });
        Comparator<KeywordFinding> order = Comparator.comparingInt((KeywordFinding value) -> importanceRank(value.importance()))
                .thenComparing(KeywordFinding::keyword, String.CASE_INSENSITIVE_ORDER);
        findings.sort(order);
        List<KeywordFinding> strong = group(findings, KeywordEvidenceLevel.STRONG);
        List<KeywordFinding> supported = group(findings, KeywordEvidenceLevel.SUPPORTED);
        List<KeywordFinding> underrepresented = group(findings, KeywordEvidenceLevel.UNDERREPRESENTED);
        List<KeywordFinding> missing = group(findings, KeywordEvidenceLevel.MISSING);
        List<KeywordFinding> present = findings.stream()
                .filter(value -> value.evidenceLevel() != KeywordEvidenceLevel.MISSING).toList();
        return new Analysis(new KeywordAnalysis(strong, supported, underrepresented, missing, present), catalog);
    }

    private Map<String, List<RankedEvidence>> build(CandidateProfileDocument profile) {
        Map<String, List<RankedEvidence>> values = new LinkedHashMap<>();
        if (profile == null) return values;
        list(profile.getSkills()).forEach(skill -> add(values, skill.getName(), 1,
                reference("SKILL", "skills", skill.getName(), List.of(skill.getName()))));
        if (notBlank(profile.getProfessionalSummary())) addText(values, profile.getProfessionalSummary(), 1,
                "SUMMARY", "professionalSummary");
        for (CandidateProfileDocument.Experience value : list(profile.getExperience())) {
            List<String> normalized = combinedSkills(value.getTechnologies(), value.getTitle(), value.getDescription());
            String source = joinEvidence(value.getTitle(), value.getOrganization(), value.getDescription());
            for (String skill : normalized) add(values, skill, 3,
                    reference("EXPERIENCE", "experience.description", source, normalized));
        }
        for (CandidateProfileDocument.Project value : list(profile.getProjects())) {
            List<String> normalized = combinedSkills(value.getTechnologies(), value.getName(), value.getDescription());
            String source = joinEvidence(value.getName(), null, value.getDescription());
            for (String skill : normalized) add(values, skill, 2,
                    reference("PROJECT", "projects.description", source, normalized));
        }
        for (CandidateProfileDocument.Certification value : list(profile.getCertifications())) {
            String source = joinEvidence(value.getName(), value.getIssuer(), null);
            addText(values, source, 2, "CERTIFICATION", "certifications");
        }
        for (CandidateProfileDocument.Education value : list(profile.getEducation())) {
            String source = joinEvidence(value.getDegree(), value.getFieldOfStudy(), value.getDescription());
            addText(values, source, 2, "EDUCATION", "education");
        }
        return values;
    }

    private LinkedHashMap<String, KeywordImportance> jobKeywords(JobMatchFeatures features) {
        LinkedHashMap<String, KeywordImportance> values = new LinkedHashMap<>();
        if (features == null) return values;
        list(features.getRequiredSkills()).forEach(value -> values.put(value, KeywordImportance.REQUIRED));
        list(features.getPreferredSkills()).forEach(value -> values.putIfAbsent(value, KeywordImportance.PREFERRED));
        list(features.getNormalizedSkills()).forEach(value -> values.putIfAbsent(value, KeywordImportance.CONTEXTUAL));
        return values;
    }

    private void addText(Map<String, List<RankedEvidence>> values, String text, int rank,
                         String type, String field) {
        if (!notBlank(text)) return;
        List<String> normalized = skills.extractKnownSkills(text).stream().map(CandidateProfileDocument.Skill::getName).distinct().toList();
        EvidenceReference reference = reference(type, field, text, normalized);
        normalized.forEach(skill -> add(values, skill, rank, reference));
    }

    private List<String> combinedSkills(List<String> explicit, String heading, String description) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>(skills.normalizeTechnologyNames(list(explicit)));
        String text = (heading == null ? "" : heading) + "\n" + (description == null ? "" : description);
        skills.extractKnownSkills(text).forEach(value -> normalized.add(value.getName()));
        return List.copyOf(normalized);
    }

    private void add(Map<String, List<RankedEvidence>> values, String skill, int rank, EvidenceReference reference) {
        if (!notBlank(skill)) return;
        values.computeIfAbsent(key(skill), ignored -> new ArrayList<>()).add(new RankedEvidence(rank, reference));
    }

    private EvidenceReference reference(String type, String field, String text, List<String> normalized) {
        String bounded = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        if (bounded.length() > EVIDENCE_TEXT_LIMIT) bounded = bounded.substring(0, EVIDENCE_TEXT_LIMIT).strip();
        return new EvidenceReference(type, field, bounded, List.copyOf(normalized));
    }

    private String joinEvidence(String primary, String secondary, String description) {
        List<String> parts = new ArrayList<>();
        if (notBlank(primary)) parts.add(primary.strip());
        if (notBlank(secondary)) parts.add(secondary.strip());
        if (notBlank(description)) parts.add(description.strip());
        return String.join(" — ", parts);
    }

    private List<KeywordFinding> group(List<KeywordFinding> values, KeywordEvidenceLevel level) {
        return values.stream().filter(value -> value.evidenceLevel() == level).toList();
    }
    private int importanceRank(KeywordImportance value) {
        return switch (value) { case REQUIRED -> 0; case PREFERRED -> 1; case CONTEXTUAL -> 2; };
    }
    private String key(String value) { return value.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", ""); }
    private boolean notBlank(String value) { return value != null && !value.isBlank(); }
    private <T> List<T> list(List<T> values) { return values == null ? List.of() : values; }

    public record Analysis(KeywordAnalysis keywords, Map<String, List<RankedEvidence>> evidence) { }
    public record RankedEvidence(int rank, EvidenceReference reference) { }
}
