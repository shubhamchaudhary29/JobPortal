package com.example.backend.application.api;

import com.example.backend.application.api.dto.ApplicationResponse;
import com.example.backend.application.api.dto.ApplicationStatusResponse;
import com.example.backend.application.api.dto.ApplicationSummaryResponse;
import com.example.backend.application.api.dto.UpdateApplicationStatusRequest;
import com.example.backend.application.application.ApplicationService;
import com.example.backend.shared.pagination.PageRequestFactory;
import com.example.backend.shared.pagination.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Applications", description = "Ownership-protected job applications and PDF resumes")
@SecurityRequirement(name = "bearerAuth")
public class ApplicationController {
    private static final Set<String> SORTS = Set.of("appliedAt", "status");
    private final ApplicationService applications;

    public ApplicationController(ApplicationService applications) { this.applications = applications; }

    @PostMapping(value = "/jobs/{jobId}/applications", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Apply to a job", description = "Candidate identity and initial status are server-derived; PDF only")
    public ResponseEntity<ApplicationResponse> apply(@PathVariable String jobId,
                                                      @RequestPart("file") MultipartFile file) throws IOException {
        ApplicationResponse response = applications.apply(jobId, file);
        return ResponseEntity.created(URI.create("/api/v1/applications/" + response.id())).body(response);
    }

    @GetMapping("/jobs/{jobId}/applications")
    @Operation(summary = "List applicants for an owned job")
    public PageResponse<ApplicationResponse> applicants(@PathVariable String jobId,
                                                        @RequestParam(defaultValue = "0") int page,
                                                        @RequestParam(defaultValue = "20") int size,
                                                        @RequestParam(defaultValue = "appliedAt,desc") String sort) {
        return applications.applicants(jobId, page(page, size, sort));
    }

    @GetMapping("/jobs/{jobId}/application-status")
    @Operation(summary = "Check whether the authenticated candidate applied")
    public ApplicationStatusResponse applicationStatus(@PathVariable String jobId) {
        return new ApplicationStatusResponse(applications.hasApplied(jobId));
    }

    @GetMapping("/applications")
    @Operation(summary = "List the authenticated candidate's applications")
    public PageResponse<ApplicationSummaryResponse> mine(@RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "20") int size,
                                                         @RequestParam(defaultValue = "appliedAt,desc") String sort) {
        return applications.mine(page(page, size, sort));
    }

    @GetMapping("/applications/{id}")
    @Operation(summary = "Get an application as its candidate or owning recruiter")
    public ApplicationResponse get(@PathVariable String id) { return applications.getAuthorized(id); }

    @PatchMapping("/applications/{id}/status")
    @Operation(summary = "Advance an application status", description = "Owning recruiter only; explicit state transitions")
    public ApplicationResponse updateStatus(@PathVariable String id,
                                            @Valid @RequestBody UpdateApplicationStatusRequest request) {
        return applications.updateStatus(id, request.status());
    }

    @PostMapping("/applications/{id}/withdrawal")
    @Operation(summary = "Withdraw an owned application")
    public ApplicationResponse withdraw(@PathVariable String id) { return applications.withdraw(id); }

    @GetMapping("/applications/{id}/resume")
    @Operation(summary = "Download an authorized PDF resume")
    public ResponseEntity<org.springframework.core.io.Resource> resume(@PathVariable String id) throws IOException {
        var download = applications.authorizedResume(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"resume.pdf\"")
                .header("X-Content-Type-Options", "nosniff").contentLength(download.contentLength())
                .body(download.resource());
    }

    private Pageable page(int page, int size, String sort) {
        return PageRequestFactory.create(page, size, sort, SORTS, "appliedAt");
    }
}
