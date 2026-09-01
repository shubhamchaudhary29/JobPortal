package com.example.backend.copilot.application;

import com.example.backend.application.domain.ApplicationStatus;
import com.example.backend.application.infrastructure.ApplicationDocument;
import com.example.backend.application.infrastructure.ApplicationRepository;
import com.example.backend.copilot.api.dto.CopilotRequests.UpdateWorkspaceRequest;
import com.example.backend.copilot.api.dto.CopilotResponses.WorkspaceAnalyticsResponse;
import com.example.backend.copilot.api.dto.CopilotResponses.WorkspaceResponse;
import com.example.backend.copilot.domain.CopilotModels.PersonalApplicationStage;
import com.example.backend.copilot.infrastructure.CandidateJobWorkspaceDocument;
import com.example.backend.copilot.infrastructure.CandidateJobWorkspaceRepository;
import com.example.backend.copilot.infrastructure.WorkspaceQueryRepository;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.job.infrastructure.JobRepository;
import com.example.backend.shared.error.ConflictException;
import com.example.backend.shared.error.ResourceNotFoundException;
import com.example.backend.shared.pagination.PageResponse;
import com.example.backend.shared.pagination.SortResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public class ApplicationWorkspaceService {
    private static final Set<PersonalApplicationStage> APPLIED_STAGES = Set.of(
            PersonalApplicationStage.APPLIED, PersonalApplicationStage.OA, PersonalApplicationStage.INTERVIEW,
            PersonalApplicationStage.OFFER, PersonalApplicationStage.REJECTED, PersonalApplicationStage.WITHDRAWN);
    private final CandidateJobWorkspaceRepository workspaces;
    private final WorkspaceQueryRepository query;
    private final ApplicationRepository applications;
    private final JobRepository jobs;
    private final CopilotAccessService access;
    private final ApplicationCopilotAnalysisService analysis;

    public ApplicationWorkspaceService(CandidateJobWorkspaceRepository workspaces, WorkspaceQueryRepository query,
                                       ApplicationRepository applications, JobRepository jobs,
                                       CopilotAccessService access, ApplicationCopilotAnalysisService analysis) {
        this.workspaces = workspaces;
        this.query = query;
        this.applications = applications;
        this.jobs = jobs;
        this.access = access;
        this.analysis = analysis;
    }

    public WorkspaceResponse update(String jobId, UpdateWorkspaceRequest request) {
        ApplicationCopilotAnalysisService.AnalysisBundle bundle = analysis.analyze(jobId);
        CandidateJobWorkspaceDocument workspace = ensure(bundle);
        if (request != null) {
            if (request.stage() != null) workspace.setStage(request.stage());
            if (request.notes() != null) workspace.setNotes(cleanKeepEmpty(request.notes()));
            if (request.followUpAt() != null || workspace.getFollowUpAt() != null) workspace.setFollowUpAt(request.followUpAt());
            if (request.appliedExternally() != null) workspace.setAppliedExternally(request.appliedExternally());
        }
        synchronizeApplicationState(workspace, bundle.candidate().user().getEmail());
        if (APPLIED_STAGES.contains(stage(workspace)) && workspace.getAppliedAt() == null) workspace.setAppliedAt(Instant.now());
        workspace.setUpdatedAt(Instant.now());
        CandidateJobWorkspaceDocument saved = workspaces.save(workspace);
        return response(saved, access.active(bundle.job()), saved.getRecruiterStatus());
    }

    public WorkspaceResponse get(String jobId) {
        access.validateId(jobId);
        var candidate = access.candidate();
        CandidateJobWorkspaceDocument workspace = workspaces.findByUserIdAndJobId(candidate.user().getId(), jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Application workspace not found"));
        ApplicationStatus recruiterStatus = applications.findByUserIdAndJobId(candidate.user().getEmail(), jobId)
                .map(ApplicationDocument::getStatus).orElse(null);
        return response(workspace, currentActive(jobId), recruiterStatus);
    }

    public PageResponse<WorkspaceResponse> list(int page, int size, PersonalApplicationStage stage, String search) {
        var candidate = access.candidate();
        WorkspaceQueryRepository.Result result = query.find(candidate.user().getId(), stage, search, page, size);
        Set<String> jobIds = result.content().stream().map(CandidateJobWorkspaceDocument::getJobId).collect(Collectors.toSet());
        Map<String, Boolean> active = new HashMap<>();
        jobs.findAllById(jobIds).forEach(job -> active.put(job.getId(), access.active(job)));
        Map<String, ApplicationStatus> statuses = applications.findByUserIdAndJobIdIn(candidate.user().getEmail(), jobIds)
                .stream().collect(Collectors.toMap(ApplicationDocument::getJobId, value ->
                        value.getStatus() == null ? ApplicationStatus.APPLIED : value.getStatus(), (left, right) -> left));
        List<WorkspaceResponse> content = result.content().stream().map(value ->
                response(value, active.getOrDefault(value.getJobId(), false), statuses.get(value.getJobId()))).toList();
        int totalPages = result.total() == 0 ? 0 : (int) Math.ceil(result.total() / (double) size);
        return new PageResponse<>(content, page, size, result.total(), totalPages, page == 0,
                totalPages == 0 || page >= totalPages - 1, List.of(new SortResponse("updatedAt", "DESC")));
    }

    public void delete(String jobId) {
        access.validateId(jobId);
        var candidate = access.candidate();
        CandidateJobWorkspaceDocument workspace = workspaces.findByUserIdAndJobId(candidate.user().getId(), jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Application workspace not found"));
        boolean official = applications.existsByUserIdAndJobId(candidate.user().getEmail(), jobId);
        if (official || workspace.getResumeVersionId() != null || workspace.getCoverLetterVersionId() != null
                || stage(workspace) != PersonalApplicationStage.SAVED)
            throw new ConflictException("Only an unused saved job can be removed from the workspace");
        workspaces.delete(workspace);
    }

    public WorkspaceAnalyticsResponse analytics() {
        String userId = access.candidate().user().getId();
        long saved = count(userId, PersonalApplicationStage.SAVED);
        long preparing = count(userId, PersonalApplicationStage.PREPARING);
        long appliedOnly = count(userId, PersonalApplicationStage.APPLIED);
        long oa = count(userId, PersonalApplicationStage.OA);
        long interviews = count(userId, PersonalApplicationStage.INTERVIEW);
        long offers = count(userId, PersonalApplicationStage.OFFER);
        long rejected = count(userId, PersonalApplicationStage.REJECTED);
        long withdrawn = count(userId, PersonalApplicationStage.WITHDRAWN);
        long applied = appliedOnly + oa + interviews + offers + rejected + withdrawn;
        long responses = oa + interviews + offers + rejected;
        String message = applied == 0 ? "Track an applied role before rates are available."
                : applied < 3 ? "Rates are based on a very small number of tracked applications." : null;
        return new WorkspaceAnalyticsResponse(saved, preparing, applied, oa, interviews, offers, rejected, withdrawn,
                rate(responses, applied), rate(interviews + offers, applied), rate(offers, applied), message);
    }

    public void linkResume(ApplicationCopilotAnalysisService.AnalysisBundle bundle, String resumeId) {
        link(bundle, workspace -> {
            workspace.setResumeVersionId(resumeId);
            if (stage(workspace) == PersonalApplicationStage.SAVED) workspace.setStage(PersonalApplicationStage.PREPARING);
        });
    }

    public void linkCoverLetter(ApplicationCopilotAnalysisService.AnalysisBundle bundle, String coverLetterId) {
        link(bundle, workspace -> {
            workspace.setCoverLetterVersionId(coverLetterId);
            if (stage(workspace) == PersonalApplicationStage.SAVED) workspace.setStage(PersonalApplicationStage.PREPARING);
        });
    }

    private void link(ApplicationCopilotAnalysisService.AnalysisBundle bundle, Consumer<CandidateJobWorkspaceDocument> change) {
        CandidateJobWorkspaceDocument workspace = ensure(bundle);
        change.accept(workspace);
        workspace.setUpdatedAt(Instant.now());
        workspaces.save(workspace);
    }

    private CandidateJobWorkspaceDocument ensure(ApplicationCopilotAnalysisService.AnalysisBundle bundle) {
        String userId = bundle.candidate().user().getId();
        CandidateJobWorkspaceDocument existing = workspaces.findByUserIdAndJobId(userId, bundle.job().getId()).orElse(null);
        if (existing != null) {
            snapshotAnalysis(existing, bundle);
            return existing;
        }
        CandidateJobWorkspaceDocument created = new CandidateJobWorkspaceDocument();
        created.setUserId(userId);
        created.setJobId(bundle.job().getId());
        created.setCreatedAt(Instant.now());
        created.setUpdatedAt(created.getCreatedAt());
        snapshotAnalysis(created, bundle);
        synchronizeApplicationState(created, bundle.candidate().user().getEmail());
        try { return workspaces.save(created); }
        catch (DuplicateKeyException race) {
            return workspaces.findByUserIdAndJobId(userId, bundle.job().getId()).orElseThrow(() -> race);
        }
    }

    private void snapshotAnalysis(CandidateJobWorkspaceDocument workspace,
                                  ApplicationCopilotAnalysisService.AnalysisBundle bundle) {
        workspace.setJobSnapshot(access.snapshot(bundle.job()));
        workspace.setMatchScore(bundle.match().overallScore());
        workspace.setMatchingVersion(bundle.match().scoringVersion());
        workspace.setReadiness(bundle.readiness());
        workspace.setKeywordAnalysis(bundle.keywords());
    }

    private void synchronizeApplicationState(CandidateJobWorkspaceDocument workspace, String email) {
        applications.findByUserIdAndJobId(email, workspace.getJobId()).ifPresent(application -> {
            workspace.setRecruiterStatus(application.getStatus() == null ? ApplicationStatus.APPLIED : application.getStatus());
            workspace.setAppliedExternally(false);
            if (stage(workspace) == PersonalApplicationStage.SAVED || stage(workspace) == PersonalApplicationStage.PREPARING)
                workspace.setStage(PersonalApplicationStage.APPLIED);
            if (workspace.getAppliedAt() == null && application.getAppliedAt() != null)
                workspace.setAppliedAt(application.getAppliedAt().toInstant(ZoneOffset.UTC));
        });
    }

    private WorkspaceResponse response(CandidateJobWorkspaceDocument value, boolean active,
                                       ApplicationStatus recruiterStatus) {
        PersonalApplicationStage effectiveStage = recruiterStatus != null
                && (stage(value) == PersonalApplicationStage.SAVED || stage(value) == PersonalApplicationStage.PREPARING)
                ? PersonalApplicationStage.APPLIED : stage(value);
        return new WorkspaceResponse(value.getJobId(), value.getJobSnapshot(), active, value.getMatchScore(),
                value.getMatchingVersion(), value.getReadiness(), value.getKeywordAnalysis(), value.getResumeVersionId(),
                value.getCoverLetterVersionId(), effectiveStage, recruiterStatus, value.isAppliedExternally(),
                value.getAppliedAt(), value.getNotes(), value.getFollowUpAt(), followUpStatus(value),
                value.getCreatedAt(), value.getUpdatedAt());
    }

    private String followUpStatus(CandidateJobWorkspaceDocument value) {
        if (value.getFollowUpAt() == null) return "NONE";
        if (Set.of(PersonalApplicationStage.OFFER, PersonalApplicationStage.REJECTED,
                PersonalApplicationStage.WITHDRAWN).contains(stage(value))) return "COMPLETED";
        return value.getFollowUpAt().isBefore(Instant.now()) ? "OVERDUE" : "UPCOMING";
    }

    private boolean currentActive(String jobId) {
        return jobs.findById(jobId).filter(value -> value.getReconciliationTargetId() == null)
                .map(access::active).orElse(false);
    }
    private PersonalApplicationStage stage(CandidateJobWorkspaceDocument value) {
        return value.getStage() == null ? PersonalApplicationStage.SAVED : value.getStage();
    }
    private long count(String userId, PersonalApplicationStage stage) { return workspaces.countByUserIdAndStage(userId, stage); }
    private Double rate(long numerator, long denominator) {
        return denominator == 0 ? null : Math.round(numerator * 1000.0 / denominator) / 10.0;
    }
    private String cleanKeepEmpty(String value) { return value == null ? null : value.strip(); }
}
