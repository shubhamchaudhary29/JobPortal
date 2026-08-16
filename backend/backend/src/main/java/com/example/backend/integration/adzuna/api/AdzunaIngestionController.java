package com.example.backend.integration.adzuna.api;

import com.example.backend.integration.adzuna.AdzunaIngestionCoordinator;
import com.example.backend.shared.error.ForbiddenException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A deliberate operator action; normal job searches never invoke the provider. */
@RestController
@RequestMapping("/api/v1/jobs/ingestion")
public class AdzunaIngestionController {
    private final AdzunaIngestionCoordinator ingestion;

    public AdzunaIngestionController(AdzunaIngestionCoordinator ingestion) { this.ingestion = ingestion; }

    @PostMapping("/adzuna")
    public ResponseEntity<?> sync(Authentication authentication) {
        boolean recruiter = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_RECRUITER".equals(authority.getAuthority()));
        if (!recruiter) throw new ForbiddenException("Only recruiters can trigger job ingestion");
        AdzunaIngestionCoordinator.Result result = ingestion.run();
        if (result.locked()) return ResponseEntity.status(HttpStatus.CONFLICT).build();
        if (result.leaseLost()) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(java.util.Map.of("status", "LEASE_LOST"));
        return ResponseEntity.ok(result.sync());
    }
}
