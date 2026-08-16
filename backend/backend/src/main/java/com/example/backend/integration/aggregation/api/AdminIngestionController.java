package com.example.backend.integration.aggregation.api;

import com.example.backend.integration.aggregation.EmployerIngestionService;
import com.example.backend.integration.aggregation.EmployerRegistryProperties;
import com.example.backend.integration.aggregation.IngestionAdminService;
import com.example.backend.integration.aggregation.IngestionCoordinator;
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
    public AdminIngestionController(IngestionCoordinator coordinator, IngestionAdminService admin) { this.coordinator = coordinator; this.admin = admin; }
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
    public record Summary(long activeImportedJobs, long inactiveImportedJobs) { }
}
