package com.example.backend.geo.api;

import com.example.backend.geo.application.GeoCoverageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/geo")
@Tag(name = "Geo administration", description = "ADMIN-only place-resolution coverage")
@SecurityRequirement(name = "bearerAuth")
public class AdminGeoCoverageController {
    private final GeoCoverageService coverage;
    public AdminGeoCoverageController(GeoCoverageService coverage) { this.coverage = coverage; }

    @GetMapping("/coverage")
    @Operation(summary = "Place-resolution coverage of active jobs",
            description = "Counts by status, stale-version jobs, resolver version, and the top unresolved strings; cached 60 s")
    public GeoCoverageService.Coverage coverage() { return coverage.coverage(); }
}
