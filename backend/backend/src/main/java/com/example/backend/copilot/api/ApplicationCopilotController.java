package com.example.backend.copilot.api;

import com.example.backend.copilot.api.dto.CopilotRequests.CreateVersionRequest;
import com.example.backend.copilot.api.dto.CopilotRequests.UpdateCoverLetterRequest;
import com.example.backend.copilot.api.dto.CopilotRequests.UpdateResumeVersionRequest;
import com.example.backend.copilot.api.dto.CopilotRequests.UpdateWorkspaceRequest;
import com.example.backend.copilot.api.dto.CopilotResponses.CoverLetterResponse;
import com.example.backend.copilot.api.dto.CopilotResponses.ReadinessResponse;
import com.example.backend.copilot.api.dto.CopilotResponses.ResumeVersionResponse;
import com.example.backend.copilot.api.dto.CopilotResponses.TailoringPlanResponse;
import com.example.backend.copilot.api.dto.CopilotResponses.WorkspaceAnalyticsResponse;
import com.example.backend.copilot.api.dto.CopilotResponses.WorkspaceResponse;
import com.example.backend.copilot.application.ApplicationCopilotAnalysisService;
import com.example.backend.copilot.application.ApplicationWorkspaceService;
import com.example.backend.copilot.application.CoverLetterService;
import com.example.backend.copilot.application.CopilotAccessService;
import com.example.backend.copilot.application.ResumeVersionService;
import com.example.backend.copilot.application.TailoredResumeDocxExporter;
import com.example.backend.copilot.domain.CopilotModels.PersonalApplicationStage;
import com.example.backend.shared.pagination.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Application Copilot", description = "Candidate-only evidence-based application preparation and tracking")
public class ApplicationCopilotController {
    private static final MediaType DOCX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    private final ApplicationCopilotAnalysisService analysis;
    private final CopilotAccessService access;
    private final ResumeVersionService resumes;
    private final CoverLetterService coverLetters;
    private final ApplicationWorkspaceService workspaces;
    private final TailoredResumeDocxExporter exporter;

    public ApplicationCopilotController(ApplicationCopilotAnalysisService analysis, CopilotAccessService access,
                                        ResumeVersionService resumes, CoverLetterService coverLetters,
                                        ApplicationWorkspaceService workspaces, TailoredResumeDocxExporter exporter) {
        this.analysis = analysis;
        this.access = access;
        this.resumes = resumes;
        this.coverLetters = coverLetters;
        this.workspaces = workspaces;
        this.exporter = exporter;
    }

    @GetMapping("/jobs/{jobId}/application-readiness")
    @Operation(summary = "Analyze truthful application readiness and keyword evidence")
    public ReadinessResponse readiness(@PathVariable String jobId) {
        var bundle = analysis.analyze(jobId);
        return new ReadinessResponse(access.snapshot(bundle.job()), bundle.match().overallScore(),
                bundle.match().matchLevel(), bundle.readiness(), bundle.keywords());
    }

    @GetMapping("/jobs/{jobId}/tailoring-plan")
    @Operation(summary = "Create a non-mutating evidence-based tailoring plan")
    public TailoringPlanResponse tailoringPlan(@PathVariable String jobId) {
        var bundle = analysis.analyze(jobId);
        return new TailoringPlanResponse(access.snapshot(bundle.job()), bundle.tailoringPlan());
    }

    @PostMapping("/jobs/{jobId}/resume-versions")
    public ResumeVersionResponse createResume(@PathVariable String jobId,
                                              @Valid @RequestBody(required = false) CreateVersionRequest request) {
        return resumes.create(jobId, request);
    }
    @GetMapping("/resume-versions/{id}") public ResumeVersionResponse resume(@PathVariable String id) { return resumes.get(id); }
    @PutMapping("/resume-versions/{id}") public ResumeVersionResponse updateResume(
            @PathVariable String id, @Valid @RequestBody UpdateResumeVersionRequest request) { return resumes.update(id, request); }
    @GetMapping("/jobs/{jobId}/resume-versions") public PageResponse<ResumeVersionResponse> resumes(
            @PathVariable String jobId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) { return resumes.list(jobId, page, size); }

    @GetMapping("/resume-versions/{id}/export")
    public ResponseEntity<ByteArrayResource> export(@PathVariable String id) throws IOException {
        var value = exporter.export(resumes.ownedDocument(id));
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(value.filename(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().contentType(DOCX).contentLength(value.bytes().length)
                .cacheControl(CacheControl.noStore()).header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new ByteArrayResource(value.bytes()));
    }

    @PostMapping("/jobs/{jobId}/cover-letters")
    public CoverLetterResponse createCoverLetter(@PathVariable String jobId,
                                                 @Valid @RequestBody(required = false) CreateVersionRequest request) {
        return coverLetters.create(jobId, request);
    }
    @GetMapping("/cover-letters/{id}") public CoverLetterResponse coverLetter(@PathVariable String id) { return coverLetters.get(id); }
    @PutMapping("/cover-letters/{id}") public CoverLetterResponse updateCoverLetter(
            @PathVariable String id, @Valid @RequestBody UpdateCoverLetterRequest request) { return coverLetters.update(id, request); }
    @GetMapping("/jobs/{jobId}/cover-letters") public PageResponse<CoverLetterResponse> coverLetters(
            @PathVariable String jobId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) { return coverLetters.list(jobId, page, size); }

    @GetMapping("/application-workspace") public PageResponse<WorkspaceResponse> workspace(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) PersonalApplicationStage stage,
            @RequestParam(required = false) String search) { return workspaces.list(page, size, stage, search); }
    @GetMapping("/application-workspace/analytics") public WorkspaceAnalyticsResponse analytics() { return workspaces.analytics(); }
    @GetMapping("/application-workspace/{jobId}") public WorkspaceResponse workspace(@PathVariable String jobId) { return workspaces.get(jobId); }
    @PutMapping("/application-workspace/{jobId}") public WorkspaceResponse updateWorkspace(
            @PathVariable String jobId, @Valid @RequestBody(required = false) UpdateWorkspaceRequest request) {
        return workspaces.update(jobId, request);
    }
    @DeleteMapping("/application-workspace/{jobId}") public ResponseEntity<Void> deleteWorkspace(@PathVariable String jobId) {
        workspaces.delete(jobId); return ResponseEntity.noContent().build();
    }
}
