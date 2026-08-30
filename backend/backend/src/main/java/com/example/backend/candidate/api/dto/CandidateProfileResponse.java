package com.example.backend.candidate.api.dto;

import com.example.backend.candidate.domain.ResumeParsingStatus;

import java.time.Instant;
import java.util.List;

public record CandidateProfileResponse(
        String userId,
        String fullName,
        String email,
        String phone,
        String location,
        String professionalSummary,
        List<Skill> skills,
        List<Education> education,
        List<Experience> experience,
        List<Project> projects,
        List<Certification> certifications,
        ProfessionalLinks links,
        JobPreferences preferences,
        ResumeMetadata resume,
        List<String> parsingWarnings,
        ResumeQualityResponse quality,
        Instant createdAt,
        Instant updatedAt) {

    public record Skill(String name, String originalName, String category, Double confidence, String source) { }
    public record Education(String institution, String degree, String fieldOfStudy, String startDate,
                            String endDate, String grade, String description) { }
    public record Experience(String organization, String title, String employmentType, String location,
                             String startDate, String endDate, boolean currentlyWorking, String description,
                             List<String> technologies) { }
    public record Project(String name, String description, List<String> technologies, String url,
                          String startDate, String endDate) { }
    public record Certification(String name, String issuer, String issueDate, String credentialUrl) { }
    public record ProfessionalLinks(String linkedIn, String github, String portfolio, String website,
                                    List<String> other) { }
    public record JobPreferences(List<String> preferredJobTitles, List<String> preferredLocations,
                                 String remotePreference, List<String> employmentTypes, Long minimumSalary) { }
    public record ResumeMetadata(String filename, String contentType, long size, Instant uploadedAt,
                                 ResumeParsingStatus parsingStatus, String parserVersion,
                                 String errorCode, String errorMessage) { }
}
