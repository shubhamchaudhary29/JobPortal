package com.example.backend.integration.aggregation.api;

import com.example.backend.integration.aggregation.EmployerIngestionService;
import com.example.backend.integration.aggregation.EmployerRegistryProperties;
import com.example.backend.integration.aggregation.IngestionAdminService;
import com.example.backend.integration.aggregation.IngestionCoordinator;
import com.example.backend.integration.aggregation.AggregationConflictService;
import com.example.backend.integration.aggregation.SyncRunService;
import com.example.backend.shared.pagination.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.core.Authentication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operational data deliberately excludes provider URLs and exception details. */
@RestController
@RequestMapping("/api/v1/admin/ingestion")
public class AdminIngestionController {
    private final IngestionCoordinator coordinator;
    private final IngestionAdminService admin;
    private final AggregationConflictService conflicts;
    private final SyncRunService runs;
    public AdminIngestionController(IngestionCoordinator coordinator, IngestionAdminService admin,
                                    AggregationConflictService conflicts, SyncRunService runs) {
        this.coordinator = coordinator;
        this.admin = admin;
        this.conflicts = conflicts;
        this.runs = runs;
    }
    @GetMapping("/summary")
    public Summary summary() {
        IngestionAdminService.Counts counts = admin.counts();
        return new Summary(counts.active(), counts.inactive());
    }
    @PostMapping("/{provider}/sync")
    public ResponseEntity<IngestionCoordinator.Result> sync(@PathVariable String provider) {
        try { return ResponseEntity.ok(coordinator.run(EmployerRegistryProperties.Source.valueOf(provider.toUpperCase()))); }
        catch (IllegalArgumentException badProvider) { return ResponseEntity.badRequest().build(); }
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
}
