package com.example.backend.copilot.application;

import com.example.backend.candidate.application.parsing.SkillNormalizer;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument;
import com.example.backend.copilot.api.dto.CopilotRequests.CreateVersionRequest;
import com.example.backend.copilot.api.dto.CopilotRequests.UpdateResumeVersionRequest;
import com.example.backend.copilot.api.dto.CopilotResponses.ResumeVersionResponse;
import com.example.backend.copilot.domain.CopilotModels.KeywordFinding;
import com.example.backend.copilot.domain.CopilotModels.ResumeCertification;
import com.example.backend.copilot.domain.CopilotModels.ResumeContent;
import com.example.backend.copilot.domain.CopilotModels.ResumeEducation;
import com.example.backend.copilot.domain.CopilotModels.ResumeExperience;
import com.example.backend.copilot.domain.CopilotModels.ResumeLinks;
import com.example.backend.copilot.domain.CopilotModels.ResumeProject;
import com.example.backend.copilot.domain.CopilotModels.ResumeStaleness;
import com.example.backend.copilot.infrastructure.TailoredResumeVersionDocument;
import com.example.backend.copilot.infrastructure.TailoredResumeVersionRepository;
import com.example.backend.shared.error.BadRequestException;
import com.example.backend.shared.error.ConflictException;
import com.example.backend.shared.error.ResourceNotFoundException;
import com.example.backend.shared.pagination.PageRequestFactory;
import com.example.backend.shared.pagination.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.example.backend.copilot.domain.CopilotModels.MAX_VERSIONS_PER_JOB;
import static com.example.backend.copilot.domain.CopilotModels.TAILORING_VERSION;

@Service
public class ResumeVersionService {
    private static final List<String> DEFAULT_SECTIONS = List.of(
            "summary", "skills", "experience", "projects", "education", "certifications", "links");
    private final TailoredResumeVersionRepository versions;
    private final ApplicationCopilotAnalysisService analysis;
    private final CopilotAccessService access;
    private final SkillNormalizer skillNormalizer;
    private final CopilotMetrics metrics;
    private final ApplicationWorkspaceService workspaces;

    public ResumeVersionService(TailoredResumeVersionRepository versions,
                                ApplicationCopilotAnalysisService analysis, CopilotAccessService access,
                                SkillNormalizer skillNormalizer, CopilotMetrics metrics,
                                ApplicationWorkspaceService workspaces) {
        this.versions = versions;
        this.analysis = analysis;
        this.access = access;
        this.skillNormalizer = skillNormalizer;
        this.metrics = metrics;
        this.workspaces = workspaces;
    }

    public ResumeVersionResponse create(String jobId, CreateVersionRequest request) {
        ApplicationCopilotAnalysisService.AnalysisBundle bundle = analysis.analyze(jobId);
        if (!bundle.readiness().active()) throw new ConflictException("Cannot generate a new resume version for an inactive job");
        String userId = bundle.candidate().user().getId();
        long count = versions.countByUserIdAndJobId(userId, jobId);
        if (count >= MAX_VERSIONS_PER_JOB) throw new ConflictException("Resume version limit reached for this job");
        Instant now = Instant.now();
        TailoredResumeVersionDocument document = new TailoredResumeVersionDocument();
        document.setUserId(userId);
        document.setJobId(jobId);
        document.setVersionNumber((int) count + 1);
        document.setJobSnapshot(access.snapshot(bundle.job()));
        String requestedTitle = request == null ? null : clean(request.title());
        document.setTitle(requestedTitle == null
                ? bundle.candidate().user().getFullName() + " — " + safe(bundle.job().getTitle()) : requestedTitle);
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        document.setBaseProfileUpdatedAt(bundle.candidate().profile().getUpdatedAt());
        document.setMatchingVersion(bundle.match().scoringVersion());
        document.setTailoringVersion(TAILORING_VERSION);
        document.setContent(content(bundle));
        document.setTailoringActions(bundle.tailoringPlan().actions());
        document.setKeywordAnalysis(bundle.keywords());
        TailoredResumeVersionDocument saved = versions.save(document);
        workspaces.linkResume(bundle, saved.getId());
        metrics.resumeCreated();
        return response(saved, bundle.candidate().profile().getUpdatedAt(), true);
    }

    public ResumeVersionResponse get(String id) {
        var candidate = access.candidate();
        TailoredResumeVersionDocument document = owned(id, candidate.user().getId());
        return response(document, candidate.profile().getUpdatedAt(), active(document.getJobId()));
    }

    public PageResponse<ResumeVersionResponse> list(String jobId, int page, int size) {
        access.validateId(jobId);
        var candidate = access.candidate();
        Pageable pageable = PageRequestFactory.create(page, size, "createdAt,desc", Set.of("createdAt", "updatedAt", "versionNumber"), "createdAt");
        boolean active = active(jobId);
        Page<ResumeVersionResponse> result = versions.findByUserIdAndJobId(candidate.user().getId(), jobId, pageable)
                .map(value -> response(value, candidate.profile().getUpdatedAt(), active));
        return PageResponse.from(result);
    }

    public ResumeVersionResponse update(String id, UpdateResumeVersionRequest request) {
        var candidate = access.candidate();
        TailoredResumeVersionDocument document = owned(id, candidate.user().getId());
        ResumeContent current = document.getContent();
        if (current == null) throw new ConflictException("Resume version has no editable content");
        List<String> skills = validateSkillOrder(request.skillOrder(), current.skills());
        List<ResumeExperience> experience = updateExperience(current.experience(), request.experienceDescriptions());
        List<ResumeProject> projects = updateProjects(current.projects(), request.projectDescriptions());
        List<String> sectionOrder = validateSections(request.sectionOrder(), current.sectionOrder());
        String summary = request.summary() == null ? current.summary() : cleanKeepEmpty(request.summary());
        document.setContent(new ResumeContent(current.fullName(), current.email(), current.phone(), current.location(),
                summary, skills, experience, projects, current.education(), current.certifications(), current.links(), sectionOrder));
        if (request.title() != null) document.setTitle(cleanKeepEmpty(request.title()));
        document.setUpdatedAt(Instant.now());
        TailoredResumeVersionDocument saved = versions.save(document);
        return response(saved, candidate.profile().getUpdatedAt(), active(saved.getJobId()));
    }

    public TailoredResumeVersionDocument ownedDocument(String id) {
        var candidate = access.candidate();
        return owned(id, candidate.user().getId());
    }

    private ResumeContent content(ApplicationCopilotAnalysisService.AnalysisBundle bundle) {
        CandidateProfileDocument profile = bundle.candidate().profile();
        Map<String, Integer> keywordRank = new LinkedHashMap<>();
        List<KeywordFinding> orderedKeywords = new ArrayList<>();
        orderedKeywords.addAll(bundle.keywords().strong()); orderedKeywords.addAll(bundle.keywords().supported());
        orderedKeywords.addAll(bundle.keywords().underrepresented());
        for (int i = 0; i < orderedKeywords.size(); i++) keywordRank.putIfAbsent(key(orderedKeywords.get(i).keyword()), i);
        List<String> skills = list(profile.getSkills()).stream().map(CandidateProfileDocument.Skill::getName)
                .sorted(Comparator.comparingInt(value -> keywordRank.getOrDefault(key(value), Integer.MAX_VALUE)))
                .toList();
        List<CandidateProfileDocument.Experience> experiences = stableRelevantSort(list(profile.getExperience()),
                value -> relevance(value.getTechnologies(), value.getTitle(), value.getDescription(), keywordRank));
        List<CandidateProfileDocument.Project> projects = stableRelevantSort(list(profile.getProjects()),
                value -> relevance(value.getTechnologies(), value.getName(), value.getDescription(), keywordRank));
        var links = profile.getLinks() == null ? new CandidateProfileDocument.ProfessionalLinks() : profile.getLinks();
        return new ResumeContent(bundle.candidate().user().getFullName(), bundle.candidate().user().getEmail(),
                profile.getPhone(), profile.getLocation(), profile.getProfessionalSummary(), skills,
                experiences.stream().map(this::experience).toList(), projects.stream().map(this::project).toList(),
                list(profile.getEducation()).stream().map(this::education).toList(),
                list(profile.getCertifications()).stream().map(this::certification).toList(),
                new ResumeLinks(links.getLinkedIn(), links.getGithub(), links.getPortfolio(), links.getWebsite(), list(links.getOther())),
                DEFAULT_SECTIONS);
    }

    private int relevance(List<String> explicit, String heading, String description, Map<String, Integer> ranks) {
        LinkedHashSet<String> found = new LinkedHashSet<>(skillNormalizer.normalizeTechnologyNames(list(explicit)));
        String text = safe(heading) + " " + safe(description);
        skillNormalizer.extractKnownSkills(text).forEach(value -> found.add(value.getName()));
        return (int) found.stream().filter(value -> ranks.containsKey(key(value))).count();
    }

    private <T> List<T> stableRelevantSort(List<T> values, java.util.function.ToIntFunction<T> score) {
        List<Indexed<T>> indexed = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) indexed.add(new Indexed<>(i, values.get(i), score.applyAsInt(values.get(i))));
        indexed.sort(Comparator.comparingInt((Indexed<T> value) -> value.score()).reversed().thenComparingInt(Indexed::index));
        return indexed.stream().map(Indexed::value).toList();
    }

    private List<String> validateSkillOrder(List<String> requested, List<String> current) {
        if (requested == null) return current;
        Map<String, String> allowed = new LinkedHashMap<>();
        list(current).forEach(value -> allowed.put(key(value), value));
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : requested) {
            String existing = allowed.get(key(value));
            if (existing == null) throw new BadRequestException("Tailored skills may only reorder or omit existing profile skills");
            if (!result.add(existing)) throw new BadRequestException("Tailored skills cannot contain duplicates");
        }
        return List.copyOf(result);
    }

    private List<ResumeExperience> updateExperience(List<ResumeExperience> current, List<String> descriptions) {
        if (descriptions == null) return current;
        if (descriptions.size() != list(current).size()) throw new BadRequestException("Experience descriptions must match the version structure");
        List<ResumeExperience> result = new ArrayList<>();
        for (int i = 0; i < current.size(); i++) {
            ResumeExperience value = current.get(i);
            result.add(new ResumeExperience(value.organization(), value.title(), value.employmentType(), value.location(),
                    value.startDate(), value.endDate(), value.currentlyWorking(), cleanKeepEmpty(descriptions.get(i)), value.technologies()));
        }
        return List.copyOf(result);
    }

    private List<ResumeProject> updateProjects(List<ResumeProject> current, List<String> descriptions) {
        if (descriptions == null) return current;
        if (descriptions.size() != list(current).size()) throw new BadRequestException("Project descriptions must match the version structure");
        List<ResumeProject> result = new ArrayList<>();
        for (int i = 0; i < current.size(); i++) {
            ResumeProject value = current.get(i);
            result.add(new ResumeProject(value.name(), cleanKeepEmpty(descriptions.get(i)), value.technologies(), value.url(),
                    value.startDate(), value.endDate()));
        }
        return List.copyOf(result);
    }

    private List<String> validateSections(List<String> requested, List<String> current) {
        if (requested == null) return current;
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String value : requested) {
            String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
            if (!DEFAULT_SECTIONS.contains(normalized)) throw new BadRequestException("Unsupported resume section");
            if (!values.add(normalized)) throw new BadRequestException("Resume sections cannot contain duplicates");
        }
        DEFAULT_SECTIONS.forEach(values::add);
        return List.copyOf(values);
    }

    private ResumeVersionResponse response(TailoredResumeVersionDocument value, Instant profileUpdatedAt, boolean active) {
        boolean stale = profileUpdatedAt != null && (value.getBaseProfileUpdatedAt() == null
                || profileUpdatedAt.isAfter(value.getBaseProfileUpdatedAt()));
        return new ResumeVersionResponse(value.getId(), value.getJobId(), value.getVersionNumber(), value.getJobSnapshot(),
                value.getTitle(), value.getCreatedAt(), value.getUpdatedAt(), value.getBaseProfileUpdatedAt(),
                value.getMatchingVersion(), value.getTailoringVersion(), value.getContent(), list(value.getTailoringActions()),
                value.getKeywordAnalysis(), stale ? ResumeStaleness.OUTDATED : ResumeStaleness.CURRENT,
                stale ? "Base profile changed since this version was created." : null, active);
    }

    private boolean active(String jobId) {
        try { return access.active(access.job(jobId)); }
        catch (ResourceNotFoundException ignored) { return false; }
    }

    private TailoredResumeVersionDocument owned(String id, String userId) {
        access.validateId(id);
        return versions.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Resume version not found"));
    }

    private ResumeExperience experience(CandidateProfileDocument.Experience value) {
        return new ResumeExperience(value.getOrganization(), value.getTitle(), value.getEmploymentType(), value.getLocation(),
                value.getStartDate(), value.getEndDate(), value.isCurrentlyWorking(), value.getDescription(), list(value.getTechnologies()));
    }
    private ResumeProject project(CandidateProfileDocument.Project value) {
        return new ResumeProject(value.getName(), value.getDescription(), list(value.getTechnologies()), value.getUrl(),
                value.getStartDate(), value.getEndDate());
    }
    private ResumeEducation education(CandidateProfileDocument.Education value) {
        return new ResumeEducation(value.getInstitution(), value.getDegree(), value.getFieldOfStudy(), value.getStartDate(),
                value.getEndDate(), value.getGrade(), value.getDescription());
    }
    private ResumeCertification certification(CandidateProfileDocument.Certification value) {
        return new ResumeCertification(value.getName(), value.getIssuer(), value.getIssueDate(), value.getCredentialUrl());
    }
    private String key(String value) { return safe(value).strip().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", ""); }
    private String clean(String value) { return value == null || value.isBlank() ? null : value.strip().replaceAll("\\s+", " "); }
    private String cleanKeepEmpty(String value) { return value == null ? null : value.strip(); }
    private String safe(String value) { return value == null ? "" : value; }
    private <T> List<T> list(List<T> values) { return values == null ? List.of() : values; }
    private record Indexed<T>(int index, T value, int score) { }
}
