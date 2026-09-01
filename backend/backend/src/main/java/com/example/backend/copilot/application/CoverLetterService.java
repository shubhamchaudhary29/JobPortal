package com.example.backend.copilot.application;

import com.example.backend.candidate.application.parsing.SkillNormalizer;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument;
import com.example.backend.copilot.api.dto.CopilotRequests.CreateVersionRequest;
import com.example.backend.copilot.api.dto.CopilotRequests.UpdateCoverLetterRequest;
import com.example.backend.copilot.api.dto.CopilotResponses.CoverLetterResponse;
import com.example.backend.copilot.domain.CopilotModels.KeywordFinding;
import com.example.backend.copilot.domain.CopilotModels.ResumeStaleness;
import com.example.backend.copilot.infrastructure.CoverLetterVersionDocument;
import com.example.backend.copilot.infrastructure.CoverLetterVersionRepository;
import com.example.backend.shared.error.ConflictException;
import com.example.backend.shared.error.ResourceNotFoundException;
import com.example.backend.shared.pagination.PageRequestFactory;
import com.example.backend.shared.pagination.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.example.backend.copilot.domain.CopilotModels.MAX_VERSIONS_PER_JOB;
import static com.example.backend.copilot.domain.CopilotModels.TAILORING_VERSION;

@Service
public class CoverLetterService {
    private final CoverLetterVersionRepository versions;
    private final ApplicationCopilotAnalysisService analysis;
    private final CopilotAccessService access;
    private final SkillNormalizer skills;
    private final CopilotMetrics metrics;
    private final ApplicationWorkspaceService workspaces;

    public CoverLetterService(CoverLetterVersionRepository versions, ApplicationCopilotAnalysisService analysis,
                              CopilotAccessService access, SkillNormalizer skills, CopilotMetrics metrics,
                              ApplicationWorkspaceService workspaces) {
        this.versions = versions;
        this.analysis = analysis;
        this.access = access;
        this.skills = skills;
        this.metrics = metrics;
        this.workspaces = workspaces;
    }

    public CoverLetterResponse create(String jobId, CreateVersionRequest request) {
        ApplicationCopilotAnalysisService.AnalysisBundle bundle = analysis.analyze(jobId);
        if (!bundle.readiness().active()) throw new ConflictException("Cannot generate a cover letter for an inactive job");
        String userId = bundle.candidate().user().getId();
        long count = versions.countByUserIdAndJobId(userId, jobId);
        if (count >= MAX_VERSIONS_PER_JOB) throw new ConflictException("Cover letter version limit reached for this job");
        Instant now = Instant.now();
        CoverLetterVersionDocument document = new CoverLetterVersionDocument();
        document.setUserId(userId);
        document.setJobId(jobId);
        document.setVersionNumber((int) count + 1);
        document.setJobSnapshot(access.snapshot(bundle.job()));
        String requestedTitle = request == null ? null : clean(request.title());
        document.setTitle(requestedTitle == null ? "Cover letter — " + safe(bundle.job().getTitle()) : requestedTitle);
        document.setContent(generate(bundle));
        document.setBaseProfileUpdatedAt(bundle.candidate().profile().getUpdatedAt());
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        document.setTailoringVersion(TAILORING_VERSION);
        CoverLetterVersionDocument saved = versions.save(document);
        workspaces.linkCoverLetter(bundle, saved.getId());
        metrics.coverLetterCreated();
        return response(saved, bundle.candidate().profile().getUpdatedAt(), true);
    }

    public CoverLetterResponse get(String id) {
        var candidate = access.candidate();
        CoverLetterVersionDocument document = owned(id, candidate.user().getId());
        return response(document, candidate.profile().getUpdatedAt(), active(document.getJobId()));
    }

    public PageResponse<CoverLetterResponse> list(String jobId, int page, int size) {
        access.validateId(jobId);
        var candidate = access.candidate();
        Pageable pageable = PageRequestFactory.create(page, size, "createdAt,desc", Set.of("createdAt", "updatedAt", "versionNumber"), "createdAt");
        boolean active = active(jobId);
        Page<CoverLetterResponse> result = versions.findByUserIdAndJobId(candidate.user().getId(), jobId, pageable)
                .map(value -> response(value, candidate.profile().getUpdatedAt(), active));
        return PageResponse.from(result);
    }

    public CoverLetterResponse update(String id, UpdateCoverLetterRequest request) {
        var candidate = access.candidate();
        CoverLetterVersionDocument document = owned(id, candidate.user().getId());
        if (request.title() != null) document.setTitle(cleanKeepEmpty(request.title()));
        if (request.content() != null) document.setContent(cleanKeepEmpty(request.content()));
        document.setUpdatedAt(Instant.now());
        CoverLetterVersionDocument saved = versions.save(document);
        return response(saved, candidate.profile().getUpdatedAt(), active(saved.getJobId()));
    }

    private String generate(ApplicationCopilotAnalysisService.AnalysisBundle bundle) {
        String company = clean(bundle.job().getCompany());
        String greeting = company == null ? "Dear Hiring Team," : "Dear Hiring Team at " + company + ",";
        List<KeywordFinding> present = new ArrayList<>();
        present.addAll(bundle.keywords().strong());
        present.addAll(bundle.keywords().supported());
        present.addAll(bundle.keywords().underrepresented());
        StringBuilder letter = new StringBuilder(greeting).append("\n\nI am applying for the ")
                .append(safe(bundle.job().getTitle())).append(" role");
        if (company != null) letter.append(" at ").append(company);
        letter.append(".");
        if (!present.isEmpty()) letter.append(" My documented profile includes ")
                .append(String.join(", ", present.stream().limit(4).map(KeywordFinding::keyword).toList())).append(".");

        CandidateProfileDocument profile = bundle.candidate().profile();
        Set<String> relevant = new LinkedHashSet<>(present.stream().map(KeywordFinding::keyword).toList());
        CandidateProfileDocument.Experience experience = list(profile.getExperience()).stream()
                .filter(value -> notBlank(value.getDescription()) && relevantEvidence(value.getTechnologies(),
                        safe(value.getTitle()) + " " + safe(value.getDescription()), relevant)).findFirst().orElse(null);
        CandidateProfileDocument.Project project = list(profile.getProjects()).stream()
                .filter(value -> notBlank(value.getDescription()) && relevantEvidence(value.getTechnologies(),
                        safe(value.getName()) + " " + safe(value.getDescription()), relevant)).findFirst().orElse(null);
        if (experience != null) {
            letter.append("\n\nIn my documented role as ").append(safe(experience.getTitle()));
            if (notBlank(experience.getOrganization())) letter.append(" at ").append(experience.getOrganization().strip());
            letter.append(", my profile states: “").append(bounded(experience.getDescription())).append("”");
        }
        if (project != null) {
            letter.append("\n\nMy ").append(safe(project.getName())).append(" project provides another relevant example: “")
                    .append(bounded(project.getDescription())).append("”");
        }
        letter.append("\n\nThis opportunity aligns with the documented strengths above. I would welcome the opportunity to discuss how my background may contribute to the role.")
                .append("\n\nSincerely,\n").append(safe(bundle.candidate().user().getFullName()));
        return letter.toString();
    }

    private boolean relevantEvidence(List<String> explicit, String text, Set<String> relevant) {
        Set<String> found = new LinkedHashSet<>(skills.normalizeTechnologyNames(list(explicit)));
        skills.extractKnownSkills(text).forEach(value -> found.add(value.getName()));
        return found.stream().anyMatch(relevant::contains);
    }

    private String bounded(String value) {
        String clean = value.strip().replaceAll("\\s+", " ");
        return clean.length() <= 600 ? clean : clean.substring(0, 600).strip();
    }

    private CoverLetterResponse response(CoverLetterVersionDocument value, Instant profileUpdatedAt, boolean active) {
        boolean stale = profileUpdatedAt != null && (value.getBaseProfileUpdatedAt() == null
                || profileUpdatedAt.isAfter(value.getBaseProfileUpdatedAt()));
        return new CoverLetterResponse(value.getId(), value.getJobId(), value.getVersionNumber(), value.getJobSnapshot(),
                value.getTitle(), value.getContent(), value.getCreatedAt(), value.getUpdatedAt(),
                value.getBaseProfileUpdatedAt(), value.getTailoringVersion(),
                stale ? ResumeStaleness.OUTDATED : ResumeStaleness.CURRENT,
                stale ? "Base profile changed since this version was created." : null, active);
    }

    private CoverLetterVersionDocument owned(String id, String userId) {
        access.validateId(id);
        return versions.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Cover letter version not found"));
    }
    private boolean active(String jobId) {
        try { return access.active(access.job(jobId)); }
        catch (ResourceNotFoundException ignored) { return false; }
    }
    private String clean(String value) { return value == null || value.isBlank() ? null : value.strip().replaceAll("\\s+", " "); }
    private String cleanKeepEmpty(String value) { return value == null ? null : value.strip(); }
    private String safe(String value) { return value == null ? "" : value; }
    private boolean notBlank(String value) { return value != null && !value.isBlank(); }
    private <T> List<T> list(List<T> values) { return values == null ? List.of() : values; }
}
