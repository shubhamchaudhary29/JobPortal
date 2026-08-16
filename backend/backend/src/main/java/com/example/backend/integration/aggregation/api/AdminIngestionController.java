package com.example.backend.integration.aggregation.api;

import com.example.backend.integration.aggregation.EmployerIngestionService;
import com.example.backend.integration.aggregation.EmployerRegistryProperties;
import com.example.backend.integration.aggregation.IngestionAdminService;
import com.example.backend.integration.aggregation.IngestionCoordinator;
import com.example.backend.integration.aggregation.AggregationConflictService;
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
    public AdminIngestionController(IngestionCoordinator coordinator, IngestionAdminService admin,
                                    AggregationConflictService conflicts) {
        this.coordinator = coordinator;
        this.admin = admin;
        this.conflicts = conflicts;
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
    public record Summary(long activeImportedJobs, long inactiveImportedJobs) { }
    public record ResolutionRequest(@NotBlank String canonicalJobId, @NotBlank String duplicateJobId) { }
}
