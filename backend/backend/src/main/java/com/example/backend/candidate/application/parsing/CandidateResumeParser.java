package com.example.backend.candidate.application.parsing;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class CandidateResumeParser {
    public static final String PARSER_VERSION = "candidate-parser-1.1.0";
    private final ResumeSectionDetector sections;
    private final ContactInfoExtractor contacts;
    private final SkillNormalizer skills;
    private final EducationParser education;
    private final ExperienceParser experience;
    private final ProjectParser projects;
    private final CertificationParser certifications;

    public CandidateResumeParser(ResumeSectionDetector sections, ContactInfoExtractor contacts,
                                 SkillNormalizer skills, EducationParser education, ExperienceParser experience,
                                 ProjectParser projects, CertificationParser certifications) {
        this.sections = sections;
        this.contacts = contacts;
        this.skills = skills;
        this.education = education;
        this.experience = experience;
        this.projects = projects;
        this.certifications = certifications;
    }

    public ParsedResume parse(String text) {
        Map<String, List<String>> detected = sections.detect(text);
        ContactInfoExtractor.ContactInfo contact = contacts.extract(text,
                detected.getOrDefault(ResumeSectionDetector.HEADER, List.of()));
        String summary = cleanParagraph(detected.get(ResumeSectionDetector.SUMMARY));
        List<com.example.backend.candidate.infrastructure.CandidateProfileDocument.Skill> parsedSkills =
                skills.extractKnownSkills(skillText(detected, text));
        List<com.example.backend.candidate.infrastructure.CandidateProfileDocument.Education> parsedEducation =
                education.parse(detected.getOrDefault(ResumeSectionDetector.EDUCATION, List.of()));
        List<com.example.backend.candidate.infrastructure.CandidateProfileDocument.Experience> parsedExperience =
                experience.parse(detected.getOrDefault(ResumeSectionDetector.EXPERIENCE, List.of()));
        List<com.example.backend.candidate.infrastructure.CandidateProfileDocument.Project> parsedProjects =
                projects.parse(detected.getOrDefault(ResumeSectionDetector.PROJECTS, List.of()));
        List<com.example.backend.candidate.infrastructure.CandidateProfileDocument.Certification> parsedCertifications =
                certifications.parse(detected.getOrDefault(ResumeSectionDetector.CERTIFICATIONS, List.of()));

        LinkedHashSet<String> warnings = new LinkedHashSet<>();
        if (contact.email() == null) warnings.add("No email address was confidently detected in the resume.");
        if (contact.phone() == null) warnings.add("No phone number was confidently detected in the resume.");
        if (summary == null) warnings.add("No summary or objective section was confidently detected.");
        if (parsedSkills.isEmpty()) warnings.add("No supported technical skills were confidently detected.");
        if (parsedEducation.isEmpty()) warnings.add("No education entries were confidently detected.");
        if (parsedExperience.isEmpty() && parsedProjects.isEmpty())
            warnings.add("No experience or project entries were confidently detected.");
        return new ParsedResume(contact.fullName(), contact.email(), contact.phone(), contact.location(), summary,
                parsedSkills, parsedEducation, parsedExperience, parsedProjects, parsedCertifications,
                contact.links(), new ArrayList<>(warnings));
    }

    private String skillText(Map<String, List<String>> detected, String fullText) {
        List<String> skillLines = detected.get(ResumeSectionDetector.SKILLS);
        return skillLines == null || skillLines.isEmpty() ? fullText : String.join(" ", skillLines);
    }

    private String cleanParagraph(List<String> lines) {
        if (lines == null || lines.isEmpty()) return null;
        String result = String.join(" ", lines).replaceAll("\\s+", " ").strip();
        return result.isBlank() ? null : result;
    }
}
