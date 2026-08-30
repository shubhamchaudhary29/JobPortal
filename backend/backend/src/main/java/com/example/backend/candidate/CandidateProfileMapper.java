package com.example.backend.candidate;

import com.example.backend.candidate.api.dto.CandidateProfileResponse;
import com.example.backend.candidate.api.dto.UpdateCandidateProfileRequest;
import com.example.backend.candidate.application.ResumeQualityAnalyzer;
import com.example.backend.candidate.application.parsing.SkillNormalizer;
import com.example.backend.candidate.domain.ResumeParsingStatus;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument.Certification;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument.Education;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument.Experience;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument.JobPreferences;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument.ProfessionalLinks;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument.Project;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument.ResumeMetadata;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument.Skill;
import com.example.backend.shared.error.BadRequestException;
import com.example.backend.user.infrastructure.UserDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class CandidateProfileMapper {
    private final SkillNormalizer skillNormalizer;
    private final ResumeQualityAnalyzer quality;
    public CandidateProfileMapper(SkillNormalizer skillNormalizer, ResumeQualityAnalyzer quality) {
        this.skillNormalizer = skillNormalizer;
        this.quality = quality;
    }

    public CandidateProfileResponse toResponse(UserDocument user, CandidateProfileDocument profile) {
        ResumeMetadata resume = profile.getResume() == null ? new ResumeMetadata() : profile.getResume();
        if (resume.getParsingStatus() == null) resume.setParsingStatus(ResumeParsingStatus.NOT_UPLOADED);
        ProfessionalLinks links = profile.getLinks() == null ? new ProfessionalLinks() : profile.getLinks();
        JobPreferences preferences = profile.getPreferences() == null ? new JobPreferences() : profile.getPreferences();
        return new CandidateProfileResponse(profile.getUserId(), user.getFullName(), user.getEmail(), profile.getPhone(),
                profile.getLocation(), profile.getProfessionalSummary(), mapSkills(profile.getSkills()),
                mapEducation(profile.getEducation()), mapExperience(profile.getExperience()), mapProjects(profile.getProjects()),
                mapCertifications(profile.getCertifications()), new CandidateProfileResponse.ProfessionalLinks(
                        links.getLinkedIn(), links.getGithub(), links.getPortfolio(), links.getWebsite(), list(links.getOther())),
                new CandidateProfileResponse.JobPreferences(list(preferences.getPreferredJobTitles()),
                        list(preferences.getPreferredLocations()), preferences.getRemotePreference(),
                        list(preferences.getEmploymentTypes()), preferences.getMinimumSalary()),
                new CandidateProfileResponse.ResumeMetadata(resume.getFilename(), resume.getContentType(), resume.getSize(),
                        resume.getUploadedAt(), resume.getParsingStatus(), resume.getParserVersion(), resume.getErrorCode(),
                        resume.getErrorMessage()), list(profile.getParsingWarnings()), quality.analyze(profile, user.getEmail()),
                profile.getCreatedAt(), profile.getUpdatedAt());
    }

    public void applyUpdate(CandidateProfileDocument profile, UpdateCandidateProfileRequest request) {
        profile.setPhone(clean(request.phone()));
        profile.setLocation(clean(request.location()));
        profile.setProfessionalSummary(clean(request.professionalSummary()));
        profile.setSkills(normalizeSkills(request.skills()));
        profile.setEducation(mapEducationRequest(request.education()));
        profile.setExperience(mapExperienceRequest(request.experience()));
        profile.setProjects(mapProjectRequest(request.projects()));
        profile.setCertifications(mapCertificationRequest(request.certifications()));
        profile.setLinks(mapLinksRequest(request.links()));
        profile.setPreferences(mapPreferencesRequest(request.preferences()));
    }

    private List<Skill> normalizeSkills(List<UpdateCandidateProfileRequest.Skill> requests) {
        Map<String, Skill> result = new LinkedHashMap<>();
        for (UpdateCandidateProfileRequest.Skill request : list(requests)) {
            Skill normalized = skillNormalizer.normalize(request.name(), "MANUAL", null);
            if (request.category() != null && !request.category().isBlank()) normalized.setCategory(clean(request.category()));
            normalized.setOriginalName(clean(request.originalName()) == null ? normalized.getOriginalName() : clean(request.originalName()));
            normalized.setConfidence(null);
            normalized.setSource("MANUAL");
            result.putIfAbsent(key(normalized.getName()), normalized);
        }
        return new ArrayList<>(result.values());
    }

    private List<Education> mapEducationRequest(List<UpdateCandidateProfileRequest.Education> requests) {
        List<Education> result = new ArrayList<>();
        for (UpdateCandidateProfileRequest.Education value : list(requests)) result.add(new Education(clean(value.institution()),
                clean(value.degree()), clean(value.fieldOfStudy()), clean(value.startDate()), clean(value.endDate()),
                clean(value.grade()), clean(value.description())));
        return result;
    }

    private List<Experience> mapExperienceRequest(List<UpdateCandidateProfileRequest.Experience> requests) {
        List<Experience> result = new ArrayList<>();
        for (UpdateCandidateProfileRequest.Experience value : list(requests)) {
            if (value.currentlyWorking() && clean(value.endDate()) != null)
                throw new BadRequestException("A current experience entry cannot have an end date");
            result.add(new Experience(clean(value.organization()), clean(value.title()), clean(value.employmentType()),
                    clean(value.location()), clean(value.startDate()), clean(value.endDate()), value.currentlyWorking(),
                    clean(value.description()), skillNormalizer.normalizeTechnologyNames(value.technologies())));
        }
        return result;
    }

    private List<Project> mapProjectRequest(List<UpdateCandidateProfileRequest.Project> requests) {
        List<Project> result = new ArrayList<>();
        for (UpdateCandidateProfileRequest.Project value : list(requests)) result.add(new Project(clean(value.name()),
                clean(value.description()), skillNormalizer.normalizeTechnologyNames(value.technologies()), clean(value.url()),
                clean(value.startDate()), clean(value.endDate())));
        return result;
    }

    private List<Certification> mapCertificationRequest(List<UpdateCandidateProfileRequest.Certification> requests) {
        List<Certification> result = new ArrayList<>();
        for (UpdateCandidateProfileRequest.Certification value : list(requests)) result.add(new Certification(
                clean(value.name()), clean(value.issuer()), clean(value.issueDate()), clean(value.credentialUrl())));
        return result;
    }

    private ProfessionalLinks mapLinksRequest(UpdateCandidateProfileRequest.ProfessionalLinks value) {
        if (value == null) return new ProfessionalLinks();
        return new ProfessionalLinks(clean(value.linkedIn()), clean(value.github()), clean(value.portfolio()),
                clean(value.website()), distinct(value.other()));
    }

    private JobPreferences mapPreferencesRequest(UpdateCandidateProfileRequest.JobPreferences value) {
        if (value == null) return new JobPreferences();
        return new JobPreferences(distinct(value.preferredJobTitles()), distinct(value.preferredLocations()),
                clean(value.remotePreference()), distinct(value.employmentTypes()), value.minimumSalary());
    }

    private List<CandidateProfileResponse.Skill> mapSkills(List<Skill> values) {
        return list(values).stream().map(value -> new CandidateProfileResponse.Skill(value.getName(), value.getOriginalName(),
                value.getCategory(), value.getConfidence(), value.getSource())).toList();
    }
    private List<CandidateProfileResponse.Education> mapEducation(List<Education> values) {
        return list(values).stream().map(value -> new CandidateProfileResponse.Education(value.getInstitution(), value.getDegree(),
                value.getFieldOfStudy(), value.getStartDate(), value.getEndDate(), value.getGrade(), value.getDescription())).toList();
    }
    private List<CandidateProfileResponse.Experience> mapExperience(List<Experience> values) {
        return list(values).stream().map(value -> new CandidateProfileResponse.Experience(value.getOrganization(), value.getTitle(),
                value.getEmploymentType(), value.getLocation(), value.getStartDate(), value.getEndDate(), value.isCurrentlyWorking(),
                value.getDescription(), list(value.getTechnologies()))).toList();
    }
    private List<CandidateProfileResponse.Project> mapProjects(List<Project> values) {
        return list(values).stream().map(value -> new CandidateProfileResponse.Project(value.getName(), value.getDescription(),
                list(value.getTechnologies()), value.getUrl(), value.getStartDate(), value.getEndDate())).toList();
    }
    private List<CandidateProfileResponse.Certification> mapCertifications(List<Certification> values) {
        return list(values).stream().map(value -> new CandidateProfileResponse.Certification(value.getName(), value.getIssuer(),
                value.getIssueDate(), value.getCredentialUrl())).toList();
    }

    private List<String> distinct(List<String> values) {
        Map<String, String> distinct = new LinkedHashMap<>();
        for (String value : list(values)) if (clean(value) != null) distinct.putIfAbsent(key(value), clean(value));
        return new ArrayList<>(distinct.values());
    }
    private String key(String value) { return value.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", ""); }
    private String clean(String value) { return value == null || value.isBlank() ? null : value.strip().replaceAll("\\s+", " "); }
    private <T> List<T> list(List<T> value) { return value == null ? List.of() : value; }
}
