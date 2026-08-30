package com.example.backend.candidate.infrastructure;

import com.example.backend.candidate.domain.ResumeParsingStatus;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "candidate_profiles")
@CompoundIndex(name = "candidate_profile_user_unique", def = "{'userId': 1}", unique = true)
public class CandidateProfileDocument {
    @Id private String id;
    private String userId;
    private String phone;
    private String location;
    private String professionalSummary;
    private List<Skill> skills = new ArrayList<>();
    private List<Education> education = new ArrayList<>();
    private List<Experience> experience = new ArrayList<>();
    private List<Project> projects = new ArrayList<>();
    private List<Certification> certifications = new ArrayList<>();
    private ProfessionalLinks links = new ProfessionalLinks();
    private JobPreferences preferences = new JobPreferences();
    private ResumeMetadata resume = new ResumeMetadata();
    private List<String> parsingWarnings = new ArrayList<>();
    private int extractedTextLength;
    private int extractedPageCount;
    private double specialCharacterRatio;
    private Instant createdAt;
    private Instant updatedAt;

    public CandidateProfileDocument() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Skill {
        private String name;
        private String originalName;
        private String category;
        private Double confidence;
        private String source;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Education {
        private String institution;
        private String degree;
        private String fieldOfStudy;
        private String startDate;
        private String endDate;
        private String grade;
        private String description;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Experience {
        private String organization;
        private String title;
        private String employmentType;
        private String location;
        private String startDate;
        private String endDate;
        private boolean currentlyWorking;
        private String description;
        private List<String> technologies = new ArrayList<>();
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Project {
        private String name;
        private String description;
        private List<String> technologies = new ArrayList<>();
        private String url;
        private String startDate;
        private String endDate;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class Certification {
        private String name;
        private String issuer;
        private String issueDate;
        private String credentialUrl;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ProfessionalLinks {
        private String linkedIn;
        private String github;
        private String portfolio;
        private String website;
        private List<String> other = new ArrayList<>();
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class JobPreferences {
        private List<String> preferredJobTitles = new ArrayList<>();
        private List<String> preferredLocations = new ArrayList<>();
        private String remotePreference;
        private List<String> employmentTypes = new ArrayList<>();
        private Long minimumSalary;
    }

    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ResumeMetadata {
        @JsonIgnore private String storedName;
        private String filename;
        private String contentType;
        private long size;
        @JsonIgnore private String sha256;
        private Instant uploadedAt;
        private ResumeParsingStatus parsingStatus = ResumeParsingStatus.NOT_UPLOADED;
        private String parserVersion;
        private String errorCode;
        private String errorMessage;
    }
}
