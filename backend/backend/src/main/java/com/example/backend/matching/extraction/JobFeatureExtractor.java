package com.example.backend.matching.extraction;

import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.matching.config.MatchingProperties;
import com.example.backend.job.domain.JobMatchFeatures;
import com.example.backend.job.domain.Seniority;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;

@Component
public class JobFeatureExtractor {
    private static final int MAX_TEXT = 50_000;
    private final JobSkillExtractor skills;
    private final ExperienceRequirementParser experience;
    private final EducationRequirementParser education;
    private final RoleNormalizer roles;
    private final SeniorityParser seniority;
    private final WorkAttributeNormalizer workAttributes;
    private final Clock clock;

    public JobFeatureExtractor(JobSkillExtractor skills, ExperienceRequirementParser experience,
                               EducationRequirementParser education, RoleNormalizer roles,
                               SeniorityParser seniority, WorkAttributeNormalizer workAttributes, Clock clock) {
        this.skills = skills;
        this.experience = experience;
        this.education = education;
        this.roles = roles;
        this.seniority = seniority;
        this.workAttributes = workAttributes;
        this.clock = clock;
    }

    public JobMatchFeatures extract(JobDocument job) {
        String title = bounded(job.getTitle());
        String description = bounded(job.getDescription());
        String location = bounded(job.getLocation());
        String employmentType = bounded(job.getEmploymentType());
        JobSkillExtractor.Result skillResult = skills.extract(title, description);
        Seniority seniorityValue = seniority.parse(title, description);
        ExperienceRequirementParser.Requirement experienceValue = experience.parse(
                title, description, job.getExperience(), seniorityValue);
        return new JobMatchFeatures(skillResult.all(), skillResult.required(), skillResult.preferred(),
                experienceValue.minimumMonths(), experienceValue.maximumMonths(), seniorityValue,
                education.parse(description), workAttributes.workMode(location, title, description),
                workAttributes.employmentType(employmentType, title, description), roles.normalize(title),
                MatchingProperties.FEATURE_VERSION, sourceHash(job), Instant.now(clock));
    }

    public boolean stale(JobDocument job) {
        JobMatchFeatures current = job.getMatchFeatures();
        return current == null || !MatchingProperties.FEATURE_VERSION.equals(current.getFeatureExtractionVersion())
                || !sourceHash(job).equals(current.getSourceHash());
    }

    public String sourceHash(JobDocument job) {
        String value = String.join("\u001f", bounded(job.getTitle()), bounded(job.getDescription()),
                bounded(job.getLocation()), bounded(job.getEmploymentType()), Double.toString(job.getExperience()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String bounded(String value) {
        if (value == null) return "";
        String normalized = value.strip().replaceAll("\\s+", " ");
        return normalized.length() <= MAX_TEXT ? normalized : normalized.substring(0, MAX_TEXT);
    }
}
