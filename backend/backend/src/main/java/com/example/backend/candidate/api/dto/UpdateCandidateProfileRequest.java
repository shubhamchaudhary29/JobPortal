package com.example.backend.candidate.api.dto;

import com.example.backend.shared.validation.ValidExternalUrl;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateCandidateProfileRequest(
        @NotBlank @Size(min = 2, max = 100) String fullName,
        @Size(max = 30) @Pattern(regexp = "^[+()0-9 .-]*$", message = "contains unsupported characters") String phone,
        @Size(max = 160) String location,
        @Size(max = 3000) String professionalSummary,
        @Size(max = 100) List<@Valid Skill> skills,
        @Size(max = 30) List<@Valid Education> education,
        @Size(max = 50) List<@Valid Experience> experience,
        @Size(max = 50) List<@Valid Project> projects,
        @Size(max = 50) List<@Valid Certification> certifications,
        @Valid ProfessionalLinks links,
        @Valid JobPreferences preferences) {

    public record Skill(
            @NotBlank @Size(max = 80) String name,
            @Size(max = 80) String originalName,
            @Size(max = 80) String category,
            @DecimalMin("0.0") @DecimalMax("1.0") Double confidence,
            @Size(max = 30) String source) { }

    public record Education(
            @NotBlank @Size(max = 200) String institution,
            @Size(max = 160) String degree,
            @Size(max = 160) String fieldOfStudy,
            @Pattern(regexp = "^$|^\\d{4}(-\\d{2})?$", message = "must be YYYY or YYYY-MM") String startDate,
            @Pattern(regexp = "^$|^\\d{4}(-\\d{2})?$", message = "must be YYYY or YYYY-MM") String endDate,
            @Size(max = 80) String grade,
            @Size(max = 3000) String description) { }

    public record Experience(
            @NotBlank @Size(max = 200) String organization,
            @NotBlank @Size(max = 160) String title,
            @Size(max = 80) String employmentType,
            @Size(max = 160) String location,
            @Pattern(regexp = "^$|^\\d{4}(-\\d{2})?$", message = "must be YYYY or YYYY-MM") String startDate,
            @Pattern(regexp = "^$|^\\d{4}(-\\d{2})?$", message = "must be YYYY or YYYY-MM") String endDate,
            boolean currentlyWorking,
            @Size(max = 6000) String description,
            @Size(max = 100) List<@Size(max = 80) String> technologies) { }

    public record Project(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 6000) String description,
            @Size(max = 100) List<@Size(max = 80) String> technologies,
            @ValidExternalUrl String url,
            @Pattern(regexp = "^$|^\\d{4}(-\\d{2})?$", message = "must be YYYY or YYYY-MM") String startDate,
            @Pattern(regexp = "^$|^\\d{4}(-\\d{2})?$", message = "must be YYYY or YYYY-MM") String endDate) { }

    public record Certification(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 200) String issuer,
            @Pattern(regexp = "^$|^\\d{4}(-\\d{2})?$", message = "must be YYYY or YYYY-MM") String issueDate,
            @ValidExternalUrl String credentialUrl) { }

    public record ProfessionalLinks(
            @ValidExternalUrl String linkedIn,
            @ValidExternalUrl String github,
            @ValidExternalUrl String portfolio,
            @ValidExternalUrl String website,
            @Size(max = 20) List<@ValidExternalUrl String> other) { }

    public record JobPreferences(
            @Size(max = 20) List<@Size(max = 100) String> preferredJobTitles,
            @Size(max = 20) List<@Size(max = 160) String> preferredLocations,
            @Pattern(regexp = "^$|^(REMOTE|HYBRID|ONSITE|FLEXIBLE)$") String remotePreference,
            @Size(max = 10) List<@Size(max = 80) String> employmentTypes,
            @PositiveOrZero Long minimumSalary) { }
}
