package com.example.backend.matching.extraction;

import com.example.backend.candidate.application.parsing.SkillNormalizer;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.matching.config.MatchingProperties;
import com.example.backend.job.domain.RoleFamily;
import com.example.backend.job.domain.Seniority;
import com.example.backend.job.domain.WorkMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class JobFeatureExtractorTest {
    private JobFeatureExtractor extractor;

    @BeforeEach
    void setUp() {
        var normalizer = new SkillNormalizer();
        extractor = new JobFeatureExtractor(new JobSkillExtractor(normalizer), new ExperienceRequirementParser(),
                new EducationRequirementParser(), new RoleNormalizer(), new SeniorityParser(),
                new WorkAttributeNormalizer(), Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void extractsRequiredPreferredAndNeutralSkillsWithoutJavaJavascriptConfusion() {
        var features = extractor.extract(job("Backend Engineer", "Requirements: Java, Spring Boot and SQL are required. "
                + "Nice to have: Docker and AWS. We build JavaScript dashboards."));
        assertTrue(features.getRequiredSkills().containsAll(java.util.List.of("Java", "Spring Boot", "SQL")));
        assertTrue(features.getPreferredSkills().containsAll(java.util.List.of("Docker", "AWS")));
        assertTrue(features.getNormalizedSkills().contains("JavaScript"));
        assertEquals(RoleFamily.BACKEND, features.getRoleFamily());
    }

    @Test
    void extractsExperienceSeniorityEducationAndWorkAttributes() {
        var features = extractor.extract(job("Senior Software Engineer", "3-5 years of experience. Bachelor's in CS required. Hybrid full-time role."));
        assertEquals(36, features.getMinimumExperienceMonths());
        assertEquals(60, features.getMaximumExperienceMonths());
        assertEquals(Seniority.SENIOR, features.getSeniority());
        assertTrue(features.getEducationRequirements().containsAll(java.util.List.of("BACHELOR", "COMPUTING_FIELD")));
        assertEquals(WorkMode.HYBRID, features.getWorkMode());
        assertEquals("FULL_TIME", features.getEmploymentType());
        assertEquals(MatchingProperties.FEATURE_VERSION, features.getFeatureExtractionVersion());
        assertNotNull(features.getSourceHash());
        assertFalse(extractor.extract(job("Product Manager", "It is a role for a bachelor's graduate."))
                .getEducationRequirements().contains("COMPUTING_FIELD"));
    }

    @Test
    void handlesFresherInternRemoteNoisyUnicodeAndEmptyDescriptions() {
        var fresher = extractor.extract(job("Software Engineer Intern", "Freshers welcome — remote internship. Unicode: नमस्ते"));
        assertEquals(0, fresher.getMinimumExperienceMonths());
        assertEquals(Seniority.INTERN, fresher.getSeniority());
        assertEquals(WorkMode.REMOTE, fresher.getWorkMode());
        assertEquals("INTERNSHIP", fresher.getEmploymentType());
        assertDoesNotThrow(() -> extractor.extract(job("Unusual ✨ role", null)));
    }

    @Test
    void supportsPlusAndZeroToTwoRequirementsAndInvalidatesChangedSource() {
        var plus = job("Engineer", "2+ years experience");
        var features = extractor.extract(plus);
        assertEquals(24, features.getMinimumExperienceMonths());
        plus.setMatchFeatures(features);
        assertFalse(extractor.stale(plus));
        plus.setDescription("0–2 years experience");
        assertTrue(extractor.stale(plus));
        assertEquals(0, extractor.extract(plus).getMinimumExperienceMonths());
        assertEquals(24, extractor.extract(plus).getMaximumExperienceMonths());
    }

    @Test
    void supportsJuniorOnsitePreferredOnlyRequiredOnlyAndLargeDescriptions() {
        var junior = extractor.extract(job("Junior React Developer", "Preferred: TypeScript and GraphQL. On-site position."));
        assertEquals(Seniority.JUNIOR, junior.getSeniority());
        assertEquals(WorkMode.ONSITE, junior.getWorkMode());
        assertTrue(junior.getPreferredSkills().containsAll(java.util.List.of("TypeScript", "GraphQL")));
        assertTrue(junior.getRequiredSkills().isEmpty());

        var required = extractor.extract(job("Backend Engineer", "Must have: Java, SQL and Docker."));
        assertTrue(required.getRequiredSkills().containsAll(java.util.List.of("Java", "SQL", "Docker")));
        assertTrue(required.getPreferredSkills().isEmpty());

        var mid = extractor.extract(job("Mid-level Backend Engineer", "Java services."));
        assertEquals(Seniority.MID, mid.getSeniority());
        assertEquals(36, mid.getMinimumExperienceMonths());

        String large = "noise ".repeat(20_000) + "Java";
        assertDoesNotThrow(() -> extractor.extract(job("Engineer", large)));
    }

    private JobDocument job(String title, String description) {
        JobDocument job = new JobDocument();
        job.setTitle(title); job.setDescription(description); job.setLocation("Pune");
        return job;
    }
}
