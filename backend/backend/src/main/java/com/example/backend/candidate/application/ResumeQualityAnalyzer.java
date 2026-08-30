package com.example.backend.candidate.application;

import com.example.backend.candidate.api.dto.ResumeQualityResponse;
import com.example.backend.candidate.api.dto.ResumeQualityResponse.Issue;
import com.example.backend.candidate.domain.ResumeParsingStatus;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument.Experience;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument.Project;
import com.example.backend.shared.validation.SafeExternalUrl;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class ResumeQualityAnalyzer {
    private static final Pattern IMPACT = Pattern.compile("(?i)(?:\\b\\d+(?:[.,]\\d+)?%?\\b|[$₹€£]\\s?\\d+|increased|reduced|improved|grew|saved|accelerated|optimized)");
    private static final String EXPLANATION = "Internal heuristic based on profile completeness, extracted text, and writing signals. It is not an official ATS score.";

    public ResumeQualityResponse analyze(CandidateProfileDocument profile, String email) {
        List<Issue> issues = new ArrayList<>();
        List<String> strengths = new ArrayList<>();
        int score = 100;
        ResumeParsingStatus status = status(profile);

        if (status == ResumeParsingStatus.NOT_UPLOADED) {
            score -= 20;
            issue(issues, "HIGH", "COMPLETENESS", "No resume is currently uploaded.", "Upload a text-based PDF or DOCX resume for analysis.");
        } else strengths.add("A resume is securely uploaded and available for review.");
        if (status == ResumeParsingStatus.OCR_REQUIRED) {
            score -= 45;
            issue(issues, "HIGH", "ACCESSIBILITY", "The uploaded resume contains too little extractable text.", "Upload a text-based PDF or DOCX; image-only documents require OCR outside this feature.");
        } else if (status == ResumeParsingStatus.FAILED) {
            score -= 35;
            issue(issues, "HIGH", "PARSING", "The uploaded resume could not be parsed.", "Replace it with a valid, unencrypted PDF or DOCX file.");
        }

        if (email == null || email.isBlank()) {
            score -= 10;
            issue(issues, "HIGH", "CONTACT", "An email address is missing.", "Add a professional email address to your account.");
        }
        if (blank(profile.getPhone())) {
            score -= 8;
            issue(issues, "MEDIUM", "CONTACT", "A phone number is missing.", "Add a current phone number with country code where appropriate.");
        } else strengths.add("Core contact information is present.");
        if (blank(profile.getProfessionalSummary())) {
            score -= 8;
            issue(issues, "MEDIUM", "COMPLETENESS", "A professional summary is missing.", "Add a concise summary tailored to the type of role you want.");
        } else if (profile.getProfessionalSummary().length() < 60) {
            score -= 3;
            issue(issues, "LOW", "CONTENT", "The professional summary is very short.", "Use two or three focused sentences covering experience, strengths, and target roles.");
        } else strengths.add("The profile includes a substantive professional summary.");

        if (profile.getSkills() == null || profile.getSkills().isEmpty()) {
            score -= 15;
            issue(issues, "HIGH", "SKILLS", "No technical skills are listed.", "Add the technologies and tools you can demonstrate in work or projects.");
        } else if (profile.getSkills().size() < 3) {
            score -= 5;
            issue(issues, "MEDIUM", "SKILLS", "Very few skills are listed.", "Add relevant skills you can support with experience or projects.");
        } else strengths.add("The resume presents a useful technical skills section.");

        if (profile.getEducation() == null || profile.getEducation().isEmpty()) {
            score -= 8;
            issue(issues, "MEDIUM", "COMPLETENESS", "Education information is missing.", "Add relevant education, training, or qualifications.");
        } else strengths.add("Education information is present.");
        boolean noExperience = profile.getExperience() == null || profile.getExperience().isEmpty();
        boolean noProjects = profile.getProjects() == null || profile.getProjects().isEmpty();
        if (noExperience && noProjects) {
            score -= 18;
            issue(issues, "HIGH", "COMPLETENESS", "Neither experience nor projects are described.", "Add employment, internships, academic projects, or personal projects that demonstrate your work.");
        } else strengths.add(noExperience ? "Projects provide evidence of practical work." : "Experience entries provide evidence of professional work.");

        List<String> descriptions = descriptions(profile);
        if (!descriptions.isEmpty() && descriptions.stream().allMatch(value -> value.length() < 50)) {
            score -= 7;
            issue(issues, "MEDIUM", "CONTENT", "Experience and project descriptions are too brief.", "Describe your contribution, approach, technologies, and result.");
        }
        if (descriptions.stream().anyMatch(value -> value.length() > 1500)) {
            score -= 3;
            issue(issues, "LOW", "CONTENT", "At least one description is excessively long.", "Use concise bullets and keep each point focused on one result.");
        }
        if (!noExperience && descriptions.stream().noneMatch(value -> IMPACT.matcher(value).find())) {
            score -= 10;
            issue(issues, "HIGH", "IMPACT", "No measurable impact was found in experience descriptions.", "Add truthful outcomes such as time saved, scale handled, quality improved, or percentages changed.");
        } else if (!descriptions.isEmpty() && descriptions.stream().anyMatch(value -> IMPACT.matcher(value).find())) {
            strengths.add("Descriptions include measurable or outcome-oriented impact.");
        }
        if (hasRepeated(descriptions)) {
            score -= 5;
            issue(issues, "MEDIUM", "CONTENT", "Repeated descriptions reduce clarity.", "Remove duplicated bullets and use each entry for distinct evidence.");
        }

        CandidateProfileDocument.ProfessionalLinks links = profile.getLinks();
        if (links == null || (blank(links.getGithub()) && blank(links.getPortfolio()) && blank(links.getWebsite()))) {
            score -= 4;
            issue(issues, "LOW", "LINKS", "A GitHub, portfolio, or professional website is missing.", "Add a relevant work-sample link if you have one.");
        } else strengths.add("A professional work-sample link is available.");
        if (links != null && allLinks(links).stream().anyMatch(value -> !blank(value) && SafeExternalUrl.parse(value).isEmpty())) {
            score -= 8;
            issue(issues, "HIGH", "LINKS", "At least one professional link is malformed.", "Use complete HTTPS links without spaces or embedded credentials.");
        }

        if (profile.getExtractedTextLength() > 0 && profile.getExtractedTextLength() < 250) {
            score -= 12;
            issue(issues, "HIGH", "FORMATTING", "The extracted resume text is extremely short.", "Confirm that the document contains selectable text and all pages were exported.");
        }
        if (profile.getExtractedTextLength() > 15000 || profile.getExtractedPageCount() > 4) {
            score -= 5;
            issue(issues, "MEDIUM", "LENGTH", "The resume may be excessively long.", "Prioritize recent, relevant evidence and remove repetition.");
        }
        if (profile.getSpecialCharacterRatio() > 0.14) {
            score -= 6;
            issue(issues, "MEDIUM", "FORMATTING", "Extraction found an unusually high proportion of special characters.", "Use standard fonts and export a clean text-based document.");
        }
        score = Math.max(0, Math.min(100, score));
        return new ResumeQualityResponse(score, "Resume Quality Score", EXPLANATION,
                List.copyOf(new java.util.LinkedHashSet<>(strengths)), List.copyOf(issues));
    }

    private ResumeParsingStatus status(CandidateProfileDocument profile) {
        return profile.getResume() == null || profile.getResume().getParsingStatus() == null
                ? ResumeParsingStatus.NOT_UPLOADED : profile.getResume().getParsingStatus();
    }

    private List<String> descriptions(CandidateProfileDocument profile) {
        List<String> values = new ArrayList<>();
        if (profile.getExperience() != null) profile.getExperience().stream().map(Experience::getDescription)
                .filter(value -> !blank(value)).forEach(values::add);
        if (profile.getProjects() != null) profile.getProjects().stream().map(Project::getDescription)
                .filter(value -> !blank(value)).forEach(values::add);
        return values;
    }

    private boolean hasRepeated(List<String> values) {
        Set<String> unique = new HashSet<>();
        for (String value : values) {
            String normalized = value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
            if (normalized.length() >= 30 && !unique.add(normalized)) return true;
        }
        return false;
    }

    private List<String> allLinks(CandidateProfileDocument.ProfessionalLinks links) {
        List<String> values = new ArrayList<>(List.of(nullToEmpty(links.getLinkedIn()), nullToEmpty(links.getGithub()),
                nullToEmpty(links.getPortfolio()), nullToEmpty(links.getWebsite())));
        if (links.getOther() != null) values.addAll(links.getOther());
        return values;
    }

    private void issue(List<Issue> target, String severity, String category, String message, String recommendation) {
        target.add(new Issue(severity, category, message, recommendation));
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String nullToEmpty(String value) { return value == null ? "" : value; }
}
