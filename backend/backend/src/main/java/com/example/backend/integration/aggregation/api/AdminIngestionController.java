package com.example.backend.integration.aggregation.api;

import com.example.backend.integration.aggregation.EmployerIngestionService;
import com.example.backend.integration.aggregation.EmployerRegistryProperties;
import com.example.backend.integration.aggregation.IngestionAdminService;
import com.example.backend.integration.aggregation.IngestionCoordinator;
import com.example.backend.integration.aggregation.AggregationConflictService;
import com.example.backend.integration.aggregation.SyncRunService;
import com.example.backend.integration.adzuna.AdzunaIngestionCoordinator;
import com.example.backend.shared.error.BadRequestException;
import com.example.backend.shared.pagination.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operational data deliberately excludes provider URLs and exception details. */
@RestController
@RequestMapping("/api/v1/admin/ingestion")
@Tag(name = "Aggregation administration", description = "ADMIN-only bounded operational APIs")
@SecurityRequirement(name = "bearerAuth")
public class AdminIngestionController {
    private final IngestionCoordinator coordinator;
    private final IngestionAdminService admin;
    private final AggregationConflictService conflicts;
    private final SyncRunService runs;
    private final AdzunaIngestionCoordinator adzuna;
    public AdminIngestionController(IngestionCoordinator coordinator, IngestionAdminService admin,
                                    AggregationConflictService conflicts, SyncRunService runs,
                                    AdzunaIngestionCoordinator adzuna) {
        this.coordinator = coordinator;
        this.admin = admin;
        this.conflicts = conflicts;
        this.runs = runs;
        this.adzuna = adzuna;
    }
    @GetMapping("/summary")
    public Summary summary() {
        IngestionAdminService.Counts counts = admin.counts();
        return new Summary(counts.active(), counts.inactive());
    }
    @PostMapping("/{provider}/sync")
    public ResponseEntity<?> sync(@PathVariable String provider,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String employer) {
        if ("adzuna".equalsIgnoreCase(provider)) {
            if (employer != null && !employer.isBlank()) {
                throw new BadRequestException("Adzuna does not support employer-specific synchronization");
            }
            AdzunaIngestionCoordinator.Result result = adzuna.run(SyncRunService.Trigger.MANUAL);
            if (result.locked()) return operationalFailure(HttpStatus.CONFLICT, "LOCKED", result.runId());
            if (result.leaseLost()) {
                return operationalFailure(HttpStatus.SERVICE_UNAVAILABLE, "LEASE_LOST", result.runId());
            }
            return ResponseEntity.ok(result);
        }
        EmployerRegistryProperties.Source source;
        try {
            source = EmployerRegistryProperties.Source.valueOf(provider.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException badProvider) {
            throw new BadRequestException("Unsupported provider");
        }
        IngestionCoordinator.Result result = coordinator.run(source, employer, SyncRunService.Trigger.MANUAL);
        if (result.locked()) return operationalFailure(HttpStatus.CONFLICT, "LOCKED", result.runId());
        if (result.leaseLost()) {
            return operationalFailure(HttpStatus.SERVICE_UNAVAILABLE, "LEASE_LOST", result.runId());
        }
        return ResponseEntity.ok(result);
    }
    @GetMapping("/conflicts")
    public PageResponse<AggregationConflictService.ConflictView> conflicts(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String status,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size) {
        return conflicts.list(status, page, size);
    }
    @PostMapping("/conflicts/{conflictId}/resolution")
    public AggregationConflictService.ConflictView resolve(@PathVariable String conflictId,
            @Valid @org.springframework.web.bind.annotation.RequestBody ResolutionRequest request,
            Authentication authentication) {
        return conflicts.resolve(conflictId, request.canonicalJobId(), request.duplicateJobId(),
                authentication == null ? "unknown" : authentication.getName());
    }
    @GetMapping("/history")
    public PageResponse<SyncRunService.RunView> history(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String provider,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String employer,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String outcome,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String trigger,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "20") int size) {
        return runs.history(provider, employer, outcome, trigger, page, size);
    }
    @GetMapping("/history/{runId}")
    public SyncRunService.RunView historyDetail(@PathVariable String runId) {
        return runs.detail(runId);
    }
    @GetMapping("/status")
    public Status status(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String provider,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String employer) {
        IngestionAdminService.Counts counts = admin.counts();
        return new Status(runs.latest(provider, employer), counts.active(), counts.inactive(),
                admin.providerCompanyCounts());
    }
    public record Summary(long activeImportedJobs, long inactiveImportedJobs) { }
    public record Status(java.util.List<SyncRunService.RunView> latestRuns,
                         long activeImportedJobs, long inactiveImportedJobs,
                         java.util.List<IngestionAdminService.ProviderCompanyCount> providerCompanyCounts) { }
    public record ResolutionRequest(@NotBlank String canonicalJobId, @NotBlank String duplicateJobId) { }
    private ResponseEntity<java.util.Map<String, String>> operationalFailure(
            HttpStatus status, String outcome, String runId) {
        java.util.LinkedHashMap<String, String> body = new java.util.LinkedHashMap<>();
        body.put("status", outcome);
        if (runId != null) body.put("runId", runId);
        return ResponseEntity.status(status).body(body);
    }
}
