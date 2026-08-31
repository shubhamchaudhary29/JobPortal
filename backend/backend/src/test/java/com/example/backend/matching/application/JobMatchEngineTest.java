package com.example.backend.matching.application;

import com.example.backend.candidate.application.parsing.SkillNormalizer;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.matching.config.MatchingProperties;
import com.example.backend.matching.domain.DataConfidence;
import com.example.backend.matching.domain.MatchLevel;
import com.example.backend.matching.extraction.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JobMatchEngineTest {
    private JobFeatureExtractor extractor;
    private JobMatchEngine engine;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC);
        var skillNormalizer = new SkillNormalizer();
        var roles = new RoleNormalizer();
        var work = new WorkAttributeNormalizer();
        extractor = new JobFeatureExtractor(new JobSkillExtractor(skillNormalizer), new ExperienceRequirementParser(),
                new EducationRequirementParser(), roles, new SeniorityParser(), work, clock);
        engine = new JobMatchEngine(new MatchingProperties(), skillNormalizer, roles, work,
                new CandidateExperienceCalculator(clock));
    }

    @Test
    void perfectRequiredSkillsMatchAndAliasesAreDeterministic() {
        JobDocument job = job("Java Backend Engineer", "Requirements: Java, springboot, postgres, Docker.", "Remote", "full-time");
        CandidateProfileDocument candidate = candidate(List.of("java", "Spring Boot", "postgresql", "Docker", "Docker"), "Backend Developer");
        var first = engine.calculate(candidate, job);
        var second = engine.calculate(candidate, job);
        assertEquals(100.0, first.skillScore());
        assertEquals(first, second);
        assertEquals(List.of("Docker", "Java", "PostgreSQL", "Spring Boot"), first.matchedSkills());
        assertTrue(first.missingSkills().isEmpty());
        assertTrue(first.overallScore() >= 0 && first.overallScore() <= 100);
    }

    @Test
    void partialRequiredMatchAndMissingCriticalSkillReduceSkillScore() {
        JobDocument job = job("Backend Engineer", "Requirements: Java, Spring Boot, SQL and Docker. Nice to have: Kafka.", "Pune", "full-time");
        var result = engine.calculate(candidate(List.of("Java", "Spring Boot", "Docker"), "Backend Engineer"), job);
        assertTrue(result.skillScore() < 75);
        assertEquals(List.of("SQL"), result.missingSkills());
        assertFalse(result.optionalSkillsMatched().contains("Kafka"));
        assertTrue(result.gaps().stream().anyMatch(value -> value.contains("SQL")));
    }

    @Test
    void javaAndJavascriptAndSpringAndSpringBootRemainDistinct() {
        JobDocument javascript = job("Frontend Engineer", "Requirements: JavaScript and Spring Boot.", "Remote", null);
        var result = engine.calculate(candidate(List.of("Java", "Spring"), "Frontend Engineer"), javascript);
        assertEquals(List.of("JavaScript", "Spring Boot"), result.missingSkills());
        assertTrue(result.matchedSkills().isEmpty());
        var springOnly = engine.calculate(candidate(List.of("Spring Boot"), "Backend Engineer"),
                job("Backend Engineer", "Requirements: Spring.", "Remote", null));
        assertEquals(List.of("Spring"), springOnly.missingSkills());
    }

    @Test
    void roleFamiliesRecognizeEquivalentRolesButRejectUnrelatedRoles() {
        var backend = engine.calculate(candidate(List.of("Java"), "Backend Developer"),
                job("Java Backend Engineer", "Java", "Pune", null));
        var sde = engine.calculate(candidate(List.of("Java"), "SDE"),
                job("Software Engineer", "Java", "Pune", null));
        var frontend = engine.calculate(candidate(List.of("React"), "React Developer"),
                job("Frontend Engineer", "React", "Pune", null));
        var data = engine.calculate(candidate(List.of("Python"), "Data Scientist"),
                job("Backend Engineer", "Python", "Pune", null));
        var ml = engine.calculate(candidate(List.of("Python"), "ML Engineer"),
                job("Backend Engineer", "Python", "Pune", null));
        assertEquals(100.0, backend.titleScore());
        assertEquals(100.0, sde.titleScore());
        assertEquals(100.0, frontend.titleScore());
        assertTrue(data.titleScore() <= 10);
        assertTrue(ml.titleScore() <= 10);
    }

    @Test
    void experienceSupportsExactBelowAboveFresherAndInternships() {
        CandidateProfileDocument exact = candidate(List.of("Java"), "Software Engineer");
        exact.setExperience(List.of(experience("2024-09", "2026-08", false, "Software Engineer", "FULL_TIME")));
        assertEquals(100.0, engine.calculate(exact, job("Software Engineer", "Requires 2 years experience. Java", "Pune", null)).experienceScore());
        assertEquals(0.0, engine.calculate(candidate(List.of("Java"), "Software Engineer"),
                job("Software Engineer", "Requires 2 years experience. Java", "Pune", null)).experienceScore());
        CandidateProfileDocument below = candidate(List.of("Java"), "Software Engineer");
        below.setExperience(List.of(experience("2026-03", "2026-08", false, "Intern", "INTERNSHIP")));
        assertTrue(engine.calculate(below, job("Software Engineer", "Requires 2 years experience. Java", "Pune", null)).experienceScore() < 100);
        assertEquals(100.0, engine.calculate(below, job("Graduate Engineer", "Freshers welcome. Java", "Pune", "internship")).experienceScore());
        CandidateProfileDocument above = candidate(List.of("Java"), "Software Engineer");
        above.setExperience(List.of(experience("2020-01", "2026-08", false, "Software Engineer", "FULL_TIME")));
        assertEquals(100.0, engine.calculate(above,
                job("Software Engineer", "Requires 2 years experience. Java", "Pune", null)).experienceScore());
    }

    @Test
    void educationLocationAndEmploymentUseOnlyStatedEvidence() {
        CandidateProfileDocument candidate = candidate(List.of("Java"), "Backend Engineer");
        var degree = new CandidateProfileDocument.Education(); degree.setDegree("B.Tech"); degree.setFieldOfStudy("CSE");
        candidate.setEducation(List.of(degree));
        candidate.setLocation("Delhi");
        candidate.getPreferences().setPreferredLocations(List.of("Delhi NCR"));
        candidate.getPreferences().setRemotePreference("REMOTE");
        candidate.getPreferences().setEmploymentTypes(List.of("internship"));
        var matching = engine.calculate(candidate, job("Backend Engineer Intern", "Bachelor's in Computer Science. Java. Remote internship.", "Remote", "internship"));
        assertEquals(100.0, matching.educationScore());
        assertEquals(100.0, matching.locationScore());
        assertEquals(100.0, matching.employmentTypeScore());
        var mismatch = engine.calculate(candidate, job("Backend Engineer", "Java. Onsite full-time.", "Bengaluru", "full-time"));
        assertTrue(mismatch.locationScore() <= 25);
        assertEquals(20.0, mismatch.employmentTypeScore());
        assertNull(mismatch.educationScore());

        CandidateProfileDocument unrelatedDegree = candidate(List.of("Java"), "Backend Engineer");
        var arts = new CandidateProfileDocument.Education(); arts.setDegree("Diploma"); arts.setFieldOfStudy("History");
        unrelatedDegree.setEducation(List.of(arts));
        assertEquals(0.0, engine.calculate(unrelatedDegree,
                job("Backend Engineer", "B.Tech in Computer Science required. Java.", "Pune", null)).educationScore());
    }

    @Test
    void hybridFlexibleAndMissingPreferencesAreHandledWithoutInventedPenalties() {
        CandidateProfileDocument hybrid = candidate(List.of("React"), "Frontend Engineer");
        hybrid.getPreferences().setRemotePreference("HYBRID");
        var hybridResult = engine.calculate(hybrid,
                job("Frontend Engineer", "React. This is a hybrid role.", "Bengaluru", null));
        assertEquals(100.0, hybridResult.locationScore());

        CandidateProfileDocument flexible = candidate(List.of("React"), "Frontend Engineer");
        flexible.getPreferences().setRemotePreference("FLEXIBLE");
        assertEquals(100.0, engine.calculate(flexible,
                job("Frontend Engineer", "React. Onsite role.", "Mumbai", null)).locationScore());

        CandidateProfileDocument noPreferences = candidate(List.of("React"), "Frontend Engineer");
        assertNull(engine.calculate(noPreferences,
                job("Frontend Engineer", "React", null, "full-time")).employmentTypeScore());
        flexible.getPreferences().setRemotePreference("REMOTE");
        assertNull(engine.calculate(flexible,
                job("Frontend Engineer", "React", null, null)).locationScore());
    }

    @Test
    void statedEducationWithoutCandidateEducationIsARealCandidateGap() {
        var result = engine.calculate(candidate(List.of("Java"), "Backend Engineer"),
                job("Backend Engineer", "Bachelor's degree in CS required. Java.", "Pune", null));
        assertEquals(0.0, result.educationScore());
        assertTrue(result.normalizedWeights().containsKey("education"));
        assertTrue(result.gaps().stream().anyMatch(value -> value.contains("No education")));
    }

    @Test
    void missingDimensionsAreNormalizedAndSparseEvidenceIsLabeledLowData() {
        CandidateProfileDocument candidate = candidate(List.of("Java"), null);
        JobDocument sparse = job("Unusual role", "Java", null, null);
        var result = engine.calculate(candidate, sparse);
        assertEquals(100.0, result.skillScore());
        assertEquals(100.0, result.overallScore());
        assertEquals(100.0, result.normalizedWeights().get("skills"));
        assertEquals(DataConfidence.LOW, result.dataConfidence());
        assertEquals(MatchLevel.LOW_DATA, result.matchLevel());
        assertTrue(result.explanation().stream().anyMatch(value -> value.contains("Limited profile")));
        assertFalse(Double.isNaN(result.overallScore()));
    }

    @Test
    void emptyCandidateDoesNotDivideByZeroOrInventReliability() {
        var result = engine.calculate(null, job("Backend Engineer", "Requirements: Java and Docker. 3-5 years.", "Remote", null));
        assertEquals(0.0, result.overallScore());
        assertEquals(MatchLevel.LOW_DATA, result.matchLevel());
        assertFalse(Double.isNaN(result.overallScore()));
        assertTrue(result.explanation().stream().anyMatch(value -> value.contains("too little structured data")));
        assertEquals(0.0, result.skillScore());
        assertEquals(0.0, result.titleScore());
        assertEquals(0.0, result.experienceScore());
    }

    @Test
    void zeroWeightOnlyEvidenceDoesNotDivideByZeroOrInventANormalizedWeight() {
        MatchingProperties zeroSkillWeight = new MatchingProperties();
        zeroSkillWeight.setSkillsWeight(0);
        zeroSkillWeight.setExperienceWeight(60);
        Clock clock = Clock.fixed(Instant.parse("2026-08-30T00:00:00Z"), ZoneOffset.UTC);
        var normalizer = new SkillNormalizer();
        var roles = new RoleNormalizer();
        var work = new WorkAttributeNormalizer();
        var configuredEngine = new JobMatchEngine(zeroSkillWeight, normalizer, roles, work,
                new CandidateExperienceCalculator(clock));

        var result = configuredEngine.calculate(candidate(List.of("Java"), null),
                job("Unusual role", "Java", null, null));
        assertEquals(0.0, result.overallScore());
        assertTrue(result.normalizedWeights().isEmpty());
        assertFalse(Double.isNaN(result.overallScore()));
    }

    private JobDocument job(String title, String description, String location, String employmentType) {
        JobDocument job = new JobDocument();
        job.setId("job-1"); job.setTitle(title); job.setDescription(description); job.setLocation(location);
        job.setEmploymentType(employmentType); job.setMatchFeatures(extractor.extract(job));
        return job;
    }

    private CandidateProfileDocument candidate(List<String> skills, String role) {
        CandidateProfileDocument candidate = new CandidateProfileDocument();
        List<CandidateProfileDocument.Skill> values = new ArrayList<>();
        skills.forEach(value -> values.add(new CandidateProfileDocument.Skill(value, null, null, null, "MANUAL")));
        candidate.setSkills(values);
        if (role != null) candidate.getPreferences().setPreferredJobTitles(List.of(role));
        return candidate;
    }

    private CandidateProfileDocument.Experience experience(String start, String end, boolean current,
                                                             String title, String type) {
        var value = new CandidateProfileDocument.Experience();
        value.setStartDate(start); value.setEndDate(end); value.setCurrentlyWorking(current);
        value.setTitle(title); value.setEmploymentType(type);
        return value;
    }
}
