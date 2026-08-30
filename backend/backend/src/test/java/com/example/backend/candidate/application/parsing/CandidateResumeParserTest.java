package com.example.backend.candidate.application.parsing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CandidateResumeParserTest {
    private CandidateResumeParser parser;

    @BeforeEach
    void setUp() {
        SkillNormalizer skills = new SkillNormalizer();
        parser = new CandidateResumeParser(new ResumeSectionDetector(), new ContactInfoExtractor(), skills,
                new EducationParser(), new ExperienceParser(skills), new ProjectParser(skills), new CertificationParser());
    }

    @Test
    void parsesUnusualOrderingUnicodeIndianPhoneContactsLinksAndCommonSections() {
        String text = """
                Ananya Rao
                Bengaluru, India | ananya.rao@example.com | +91 98765 43210
                https://linkedin.com/in/ananyarao https://github.com/ananyarao
                PROJECTS
                Hiring Analytics Platform
                Built React and Spring-Boot services with MongoDB; improved reporting by 30%.

                TECHNICAL SKILLS
                Java, javascript, SpringBoot, Docker, AWS, java
                PROFESSIONAL SUMMARY
                Backend engineer building reliable services for multilingual products.
                EDUCATION
                National Institute of Technology
                B.Tech in Computer Science 2018 - 2022 CGPA: 8.7
                WORK EXPERIENCE
                Software Engineer at Example Labs
                2022 - Present
                Delivered REST APIs with Java and Kafka, reducing latency by 25%.
                CERTIFICATIONS
                AWS Certified Developer - Amazon 2024
                """;
        ParsedResume result = parser.parse(text);
        assertEquals("Ananya Rao", result.detectedFullName());
        assertEquals("ananya.rao@example.com", result.detectedEmail());
        assertTrue(result.phone().contains("98765"));
        assertEquals("Bengaluru, India", result.location());
        assertEquals("https://linkedin.com/in/ananyarao", result.links().getLinkedIn());
        assertEquals("https://github.com/ananyarao", result.links().getGithub());
        assertEquals(5, result.skills().size());
        assertEquals("Java", result.skills().get(0).getName());
        assertEquals(1, result.education().size());
        assertEquals(1, result.experience().size());
        assertTrue(result.experience().get(0).isCurrentlyWorking());
        assertEquals(1, result.projects().size());
        assertEquals(1, result.certifications().size());
    }

    @Test
    void lowercaseHeadingsMissingSectionsAndFresherProjectsAreReviewable() {
        ParsedResume result = parser.parse("""
                Student Name
                student@example.org
                skills
                Python, SQL, Git
                academic projects
                Forecasting Tool
                Used Python to process 10000 records.
                """);
        assertTrue(result.experience().isEmpty());
        assertEquals(1, result.projects().size());
        assertTrue(result.warnings().stream().anyMatch(value -> value.contains("education")));
        assertFalse(result.warnings().stream().anyMatch(value -> value.contains("experience or project")));
    }
}
