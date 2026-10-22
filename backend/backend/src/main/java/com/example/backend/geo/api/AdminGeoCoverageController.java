package com.example.backend.geo.api;

import com.example.backend.geo.application.GeoCoverageService;
import com.example.backend.geo.application.GeoPrecisionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/geo")
@Tag(name = "Geo administration", description = "ADMIN-only place-resolution coverage and precision")
@SecurityRequirement(name = "bearerAuth")
public class AdminGeoCoverageController {
    private final GeoCoverageService coverage;
    private final GeoPrecisionService precision;

    public AdminGeoCoverageController(GeoCoverageService coverage, GeoPrecisionService precision) {
        this.coverage = coverage;
        this.precision = precision;
    }

    @GetMapping("/coverage")
    @Operation(summary = "Place-resolution coverage of active jobs",
            description = "Counts by status, stale-version jobs, resolver version, and the top unresolved strings; cached 60 s")
    public GeoCoverageService.Coverage coverage() { return coverage.coverage(); }

    @GetMapping("/precision")
    @Operation(summary = "How precisely visible jobs are placed",
            description = "Jobs by office/locality/city/state/country/remote/foreign/unresolved, top city-centre strings, "
                    + "per-city locality potential, Adzuna points and NCR share; cached 5 minutes")
    public GeoPrecisionService.Precision precision() { return precision.precision(); }
}
