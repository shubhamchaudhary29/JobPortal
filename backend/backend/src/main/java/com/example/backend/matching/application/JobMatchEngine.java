package com.example.backend.matching.application;

import com.example.backend.candidate.application.parsing.SkillNormalizer;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.matching.config.MatchingProperties;
import com.example.backend.matching.domain.DataConfidence;
import com.example.backend.job.domain.JobMatchFeatures;
import com.example.backend.matching.domain.JobMatchResult;
import com.example.backend.matching.domain.MatchLevel;
import com.example.backend.job.domain.RoleFamily;
import com.example.backend.job.domain.WorkMode;
import com.example.backend.matching.extraction.RoleNormalizer;
import com.example.backend.matching.extraction.WorkAttributeNormalizer;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class JobMatchEngine {
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N} ]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern BACHELOR_DEGREE = Pattern.compile(".*\\b(b tech|b e|bachelor|bsc|b sc|bs)\\b.*");
    private static final Pattern MASTER_DEGREE = Pattern.compile(".*\\b(m tech|mca|master|msc|m sc|ms)\\b.*");
    private static final Pattern COMPUTING_FIELD = Pattern.compile(
            ".*\\b(computer science|cse|information technology|computer engineering|it)\\b.*");
    private static final Pattern LOCATION_NOISE = Pattern.compile("\\b(india|remote|hybrid|onsite)\\b");
    private final MatchingProperties properties;
    private final SkillNormalizer skillNormalizer;
    private final RoleNormalizer roleNormalizer;
    private final WorkAttributeNormalizer workAttributes;
    private final CandidateExperienceCalculator experienceCalculator;

    public JobMatchEngine(MatchingProperties properties, SkillNormalizer skillNormalizer,
                          RoleNormalizer roleNormalizer, WorkAttributeNormalizer workAttributes,
                          CandidateExperienceCalculator experienceCalculator) {
        this.properties = properties;
        this.skillNormalizer = skillNormalizer;
        this.roleNormalizer = roleNormalizer;
        this.workAttributes = workAttributes;
        this.experienceCalculator = experienceCalculator;
    }

    public JobMatchResult calculate(CandidateProfileDocument candidate, JobDocument job) {
        JobMatchFeatures features = job.getMatchFeatures() == null ? new JobMatchFeatures() : job.getMatchFeatures();
        CandidateEvidence evidence = evidence(candidate);
        SkillEvidence skill = skillEvidence(evidence.skills(), features);
        Double title = titleScore(evidence.roles(), features.getRoleFamily());
        Double experience = experienceScore(evidence.experienceMonths(), features);
        Double education = educationScore(candidate, features.getEducationRequirements());
        Double location = locationScore(candidate, job, features.getWorkMode());
        Double employment = employmentScore(candidate, features.getEmploymentType());

        LinkedHashMap<String, Component> components = new LinkedHashMap<>();
        components.put("skills", new Component(skill.score(), properties.getSkillsWeight()));
        components.put("experience", new Component(experience, properties.getExperienceWeight()));
        components.put("title", new Component(title, properties.getTitleWeight()));
        components.put("education", new Component(education, properties.getEducationWeight()));
        components.put("location", new Component(location, properties.getLocationWeight()));
        components.put("employmentType", new Component(employment, properties.getEmploymentTypeWeight()));
        int availableWeight = components.values().stream().filter(Component::contributes).mapToInt(Component::weight).sum();
        double weighted = components.values().stream().filter(Component::contributes)
                .mapToDouble(value -> value.score() * value.weight()).sum();
        double overall = availableWeight == 0 ? 0 : round(weighted / availableWeight);
        LinkedHashMap<String, Double> normalizedWeights = new LinkedHashMap<>();
        components.forEach((name, component) -> {
            if (component.contributes()) normalizedWeights.put(name, round(component.weight() * 100.0 / availableWeight));
        });

        int candidateSignals = evidence.signalCount(candidate);
        int jobSignals = jobSignalCount(features, job);
        int evaluated = (int) components.values().stream().filter(Component::contributes).count();
        DataConfidence confidence = confidence(candidateSignals, jobSignals, evaluated);
        MatchLevel level = level(overall, confidence);
        List<String> strengths = strengths(skill, title, experience, education, location, employment, features, evidence);
        List<String> gaps = gaps(skill, title, experience, education, location, employment,
                candidateSignals, features, evidence, candidate);
        List<String> explanation = new ArrayList<>(strengths);
        explanation.addAll(gaps);
        if (confidence == DataConfidence.LOW)
            explanation.add("Limited profile or job data is available; add skills and preferences to improve matching accuracy.");

        return new JobMatchResult(job.getId(), overall, level, confidence, skill.matched(), skill.missingRequired(),
                skill.matchedPreferred(), title, skill.score(), experience, education, location, employment,
                Collections.unmodifiableMap(new LinkedHashMap<>(normalizedWeights)), List.copyOf(strengths),
                List.copyOf(gaps), List.copyOf(explanation),
                MatchingProperties.SCORING_VERSION);
    }

    private CandidateEvidence evidence(CandidateProfileDocument candidate) {
        if (candidate == null) return new CandidateEvidence(Set.of(), Set.of(), OptionalInt.empty());
        List<String> rawSkills = new ArrayList<>();
        if (candidate.getSkills() != null) candidate.getSkills().forEach(skill -> rawSkills.add(skill.getName()));
        if (candidate.getExperience() != null) candidate.getExperience().forEach(value -> {
            if (value.getTechnologies() != null) rawSkills.addAll(value.getTechnologies());
        });
        if (candidate.getProjects() != null) candidate.getProjects().forEach(value -> {
            if (value.getTechnologies() != null) rawSkills.addAll(value.getTechnologies());
        });
        Set<String> skills = new LinkedHashSet<>(skillNormalizer.normalizeTechnologyNames(rawSkills));
        Set<RoleFamily> roles = new LinkedHashSet<>();
        if (candidate.getPreferences() != null && candidate.getPreferences().getPreferredJobTitles() != null)
            candidate.getPreferences().getPreferredJobTitles().forEach(value -> addKnownRole(roles, value));
        if (candidate.getExperience() != null)
            candidate.getExperience().forEach(value -> addKnownRole(roles, value.getTitle()));
        return new CandidateEvidence(skills, roles, experienceCalculator.totalMonths(candidate.getExperience()));
    }

    private void addKnownRole(Set<RoleFamily> values, String raw) {
        RoleFamily value = roleNormalizer.normalize(raw);
        if (value != RoleFamily.UNKNOWN) values.add(value);
    }

    private SkillEvidence skillEvidence(Set<String> candidate, JobMatchFeatures features) {
        Set<String> all = set(features.getNormalizedSkills());
        Set<String> required = set(features.getRequiredSkills());
        Set<String> preferred = set(features.getPreferredSkills());
        Set<String> neutral = new LinkedHashSet<>(all);
        neutral.removeAll(required); neutral.removeAll(preferred);
        List<String> matched = intersection(all, candidate);
        List<String> missing = difference(required, candidate);
        List<String> matchedPreferred = intersection(preferred, candidate);
        List<String> missingPreferred = difference(preferred, candidate);
        if (all.isEmpty()) return new SkillEvidence(null, matched, missing, matchedPreferred, missingPreferred,
                required.size(), preferred.size());
        double score;
        double requiredScore = coverage(required, candidate);
        double preferredScore = coverage(preferred, candidate);
        double neutralScore = coverage(neutral, candidate);
        if (!required.isEmpty() && !preferred.isEmpty()) score = requiredScore * 0.70 + preferredScore * 0.30;
        else if (!required.isEmpty() && !neutral.isEmpty()) score = requiredScore * 0.80 + neutralScore * 0.20;
        else if (!preferred.isEmpty() && !neutral.isEmpty()) score = preferredScore * 0.60 + neutralScore * 0.40;
        else if (!required.isEmpty()) score = requiredScore;
        else if (!preferred.isEmpty()) score = preferredScore;
        else score = neutralScore;
        return new SkillEvidence(round(score), matched, missing, matchedPreferred, missingPreferred,
                required.size(), preferred.size());
    }

    private Double titleScore(Set<RoleFamily> candidateRoles, RoleFamily jobRole) {
        if (jobRole == null || jobRole == RoleFamily.UNKNOWN) return null;
        if (candidateRoles.isEmpty()) return 0.0;
        return round(candidateRoles.stream().mapToDouble(value -> roleNormalizer.similarity(value, jobRole)).max().orElse(0));
    }

    private Double experienceScore(OptionalInt candidateMonths, JobMatchFeatures features) {
        Integer minimum = features.getMinimumExperienceMonths();
        Integer maximum = features.getMaximumExperienceMonths();
        if (minimum == null && maximum == null) return null;
        if (minimum != null && minimum == 0 && candidateMonths.isEmpty()) return 100.0;
        if (candidateMonths.isEmpty()) return 0.0;
        int months = candidateMonths.getAsInt();
        if (minimum == null || months >= minimum) return 100.0;
        if (minimum == 0) return 100.0;
        return round(Math.max(10, months * 100.0 / minimum));
    }

    private Double educationScore(CandidateProfileDocument candidate, List<String> requirements) {
        if (requirements == null || requirements.isEmpty()) return null;
        if (candidate == null || candidate.getEducation() == null || candidate.getEducation().isEmpty()) return 0.0;
        boolean bachelor = false, master = false, computing = false;
        for (CandidateProfileDocument.Education value : candidate.getEducation()) {
            String degree = normalized(value.getDegree());
            String field = normalized(value.getFieldOfStudy());
            bachelor |= BACHELOR_DEGREE.matcher(degree).matches();
            master |= MASTER_DEGREE.matcher(degree).matches();
            computing |= COMPUTING_FIELD.matcher(degree + " " + field).matches();
        }
        int matched = 0;
        for (String requirement : requirements) {
            if ("BACHELOR".equals(requirement) && (bachelor || master)) matched++;
            if ("MASTER".equals(requirement) && master) matched++;
            if ("COMPUTING_FIELD".equals(requirement) && computing) matched++;
        }
        return round(matched * 100.0 / requirements.size());
    }

    private Double locationScore(CandidateProfileDocument candidate, JobDocument job, WorkMode workMode) {
        if (candidate == null || candidate.getPreferences() == null) return null;
        if ((workMode == null || workMode == WorkMode.UNKNOWN) && locationKey(job.getLocation()).isBlank()) return null;
        String remotePreference = normalized(candidate.getPreferences().getRemotePreference()).replace(' ', '_').toUpperCase(Locale.ROOT);
        List<String> preferredLocations = candidate.getPreferences().getPreferredLocations() == null ? List.of()
                : candidate.getPreferences().getPreferredLocations();
        boolean flexible = remotePreference.contains("FLEXIBLE") || remotePreference.contains("ANY");
        if (flexible) return 100.0;
        if (workMode == WorkMode.REMOTE) {
            if (remotePreference.contains("REMOTE")) return 100.0;
            if (remotePreference.contains("HYBRID")) return 75.0;
            if (!remotePreference.isBlank()) return 25.0;
        }
        if (workMode == WorkMode.HYBRID) {
            if (remotePreference.contains("HYBRID")) return 100.0;
            if (remotePreference.contains("REMOTE")) return 80.0;
        }
        String jobLocation = locationKey(job.getLocation());
        boolean exact = !jobLocation.isBlank() && preferredLocations.stream().map(this::locationKey)
                .anyMatch(value -> !value.isBlank() && (jobLocation.contains(value) || value.contains(jobLocation)));
        if (exact) return 100.0;
        if (preferredLocations.isEmpty() && (candidate.getLocation() == null || candidate.getLocation().isBlank()))
            return remotePreference.isBlank() ? null : 50.0;
        if (locationKey(candidate.getLocation()).equals(jobLocation) && !jobLocation.isBlank()) return 90.0;
        if (workMode == WorkMode.ONSITE && remotePreference.contains("REMOTE")) return 20.0;
        return 25.0;
    }

    private Double employmentScore(CandidateProfileDocument candidate, String jobType) {
        if (jobType == null || "UNKNOWN".equals(jobType) || candidate == null || candidate.getPreferences() == null
                || candidate.getPreferences().getEmploymentTypes() == null
                || candidate.getPreferences().getEmploymentTypes().isEmpty()) return null;
        Set<String> preferred = new LinkedHashSet<>();
        candidate.getPreferences().getEmploymentTypes().forEach(value -> {
            String normalized = workAttributes.normalizeEmploymentPreference(value);
            if (normalized != null) preferred.add(normalized);
        });
        return preferred.contains(jobType) ? 100.0 : 20.0;
    }

    private List<String> strengths(SkillEvidence skill, Double title, Double experience, Double education,
                                   Double location, Double employment, JobMatchFeatures features,
                                   CandidateEvidence evidence) {
        List<String> values = new ArrayList<>();
        int matchedRequired = skill.requiredCount() - skill.missingRequired().size();
        if (matchedRequired > 0)
            values.add("Matched " + matchedRequired + " of " + skill.requiredCount() + " required skills.");
        else if (!skill.matched().isEmpty()) values.add("Matched skills: " + String.join(", ", skill.matched()) + ".");
        if (title != null && title >= 70) values.add("The role aligns with your preferred or previous role family.");
        if (experience != null && experience >= 80) values.add("Your recorded experience is compatible with the job requirement.");
        if (location != null && location >= 80) values.add("The job location or work mode matches your preferences.");
        if (employment != null && employment >= 80) values.add("The employment type matches your preferences.");
        if (education != null && education >= 80) values.add("Your education matches the stated requirement.");
        return values;
    }

    private List<String> gaps(SkillEvidence skill, Double title, Double experience, Double education,
                              Double location, Double employment, int candidateSignals,
                              JobMatchFeatures features, CandidateEvidence evidence,
                              CandidateProfileDocument candidate) {
        List<String> values = new ArrayList<>();
        if (!skill.missingRequired().isEmpty()) values.add("Required skills not found in your profile: " + String.join(", ", skill.missingRequired()) + ".");
        if (!skill.missingPreferred().isEmpty()) values.add("Preferred skills not found in your profile: " + String.join(", ", skill.missingPreferred()) + ".");
        if (title != null && title <= 25 && !evidence.roles().isEmpty())
            values.add("Your preferred or previous role family does not closely align with this role.");
        if (experience != null && experience < 80) {
            if (evidence.experienceMonths().isEmpty())
                values.add("No valid dated experience was found for the stated experience requirement.");
            else if (features.getMinimumExperienceMonths() != null)
                values.add("Your profile shows approximately " + evidence.experienceMonths().getAsInt()
                        + " months of experience; this job asks for at least "
                        + features.getMinimumExperienceMonths() + " months.");
        }
        if (education != null && education < 80) {
            boolean missingEducation = candidate == null || candidate.getEducation() == null
                    || candidate.getEducation().isEmpty();
            values.add(missingEducation
                    ? "No education was listed for the job's stated education requirement."
                    : "Your listed education does not fully match the stated requirement.");
        }
        if (location != null && location < 50)
            values.add("The job location or work mode does not align with your stated preferences.");
        if (employment != null && employment < 50)
            values.add("The employment type does not match your stated preferences.");
        if (candidateSignals == 0) values.add("Your candidate profile has too little structured data for a reliable comparison.");
        else {
            if (evidence.skills().isEmpty() && features.getNormalizedSkills() != null && !features.getNormalizedSkills().isEmpty())
                values.add("Add skills to your profile to evaluate technical compatibility.");
            if (evidence.roles().isEmpty() && features.getRoleFamily() != RoleFamily.UNKNOWN)
                values.add("Add preferred job titles to improve role matching.");
        }
        return values;
    }

    private DataConfidence confidence(int candidateSignals, int jobSignals, int evaluated) {
        if (candidateSignals >= 4 && jobSignals >= 5 && evaluated >= 5) return DataConfidence.HIGH;
        if (candidateSignals >= 2 && jobSignals >= 3 && evaluated >= 3) return DataConfidence.MEDIUM;
        return DataConfidence.LOW;
    }

    private MatchLevel level(double score, DataConfidence confidence) {
        if (confidence == DataConfidence.LOW) return MatchLevel.LOW_DATA;
        if (score >= 90) return MatchLevel.EXCELLENT;
        if (score >= 75) return MatchLevel.STRONG;
        if (score >= 60) return MatchLevel.MODERATE;
        if (score >= 40) return MatchLevel.WEAK;
        return MatchLevel.LOW;
    }

    private int jobSignalCount(JobMatchFeatures features, JobDocument job) {
        int count = 0;
        if (features.getNormalizedSkills() != null && !features.getNormalizedSkills().isEmpty()) count++;
        if (features.getRoleFamily() != null && features.getRoleFamily() != RoleFamily.UNKNOWN) count++;
        if (features.getMinimumExperienceMonths() != null || features.getMaximumExperienceMonths() != null) count++;
        if (features.getEducationRequirements() != null && !features.getEducationRequirements().isEmpty()) count++;
        if (features.getWorkMode() != null && features.getWorkMode() != WorkMode.UNKNOWN) count++;
        if (features.getEmploymentType() != null && !"UNKNOWN".equals(features.getEmploymentType())) count++;
        if (job.getDescription() != null && job.getDescription().length() >= 200) count++;
        return count;
    }

    private double coverage(Set<String> required, Set<String> candidate) {
        if (required.isEmpty()) return 100;
        return intersection(required, candidate).size() * 100.0 / required.size();
    }

    private Set<String> set(Collection<String> values) {
        return values == null ? new LinkedHashSet<>() : new LinkedHashSet<>(values);
    }

    private List<String> intersection(Collection<String> left, Collection<String> right) {
        return left.stream().filter(right::contains).sorted().toList();
    }

    private List<String> difference(Collection<String> left, Collection<String> right) {
        return left.stream().filter(value -> !right.contains(value)).sorted().toList();
    }

    private String normalized(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return WHITESPACE.matcher(NON_WORD.matcher(normalized).replaceAll(" ")).replaceAll(" ").trim();
    }

    private String locationKey(String value) {
        String normalized = normalized(value).replace("new delhi", "delhi ncr").replace("gurugram", "delhi ncr")
                .replace("gurgaon", "delhi ncr").replace("noida", "delhi ncr");
        return WHITESPACE.matcher(LOCATION_NOISE.matcher(normalized).replaceAll("")).replaceAll(" ").trim();
    }

    private double round(double value) {
        double bounded = Math.max(0, Math.min(100, value));
        return Math.round(bounded * 10.0) / 10.0;
    }

    private record Component(Double score, int weight) {
        boolean contributes() { return score != null && weight > 0; }
    }
    private record SkillEvidence(Double score, List<String> matched, List<String> missingRequired,
                                 List<String> matchedPreferred, List<String> missingPreferred,
                                 int requiredCount, int preferredCount) { }
    private record CandidateEvidence(Set<String> skills, Set<RoleFamily> roles, OptionalInt experienceMonths) {
        int signalCount(CandidateProfileDocument candidate) {
            int count = 0;
            if (!skills.isEmpty()) count++;
            if (!roles.isEmpty()) count++;
            if (experienceMonths.isPresent()) count++;
            if (candidate != null && candidate.getEducation() != null && !candidate.getEducation().isEmpty()) count++;
            if (candidate != null && candidate.getPreferences() != null) {
                if ((candidate.getPreferences().getPreferredLocations() != null && !candidate.getPreferences().getPreferredLocations().isEmpty())
                        || (candidate.getPreferences().getRemotePreference() != null && !candidate.getPreferences().getRemotePreference().isBlank())) count++;
                if (candidate.getPreferences().getEmploymentTypes() != null && !candidate.getPreferences().getEmploymentTypes().isEmpty()) count++;
            }
            return count;
        }
    }
}
