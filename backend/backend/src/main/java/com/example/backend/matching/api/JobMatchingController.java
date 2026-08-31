package com.example.backend.matching.api;

import com.example.backend.matching.api.dto.JobMatchResponse;
import com.example.backend.matching.api.dto.MatchedJobResponse;
import com.example.backend.matching.application.JobMatchingService;
import com.example.backend.shared.pagination.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/jobs")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Job matching", description = "Candidate-only deterministic profile-to-job compatibility")
public class JobMatchingController {
    private final JobMatchingService matching;
    public JobMatchingController(JobMatchingService matching) { this.matching = matching; }

    @GetMapping("/matched")
    @Operation(summary = "Rank visible jobs for the authenticated candidate")
    public PageResponse<MatchedJobResponse> matched(@RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size,
                                                     @RequestParam(defaultValue = "0") double minMatch,
                                                     @RequestParam(required = false) String location,
                                                     @RequestParam(required = false) String source,
                                                     @RequestParam(required = false) String employmentType,
                                                     @RequestParam(required = false) String workMode,
                                                     @RequestParam(required = false) String role,
                                                     @RequestParam(defaultValue = "matchScore") String sort) {
        return matching.matched(page, size, minMatch, location, source, employmentType, workMode, role, sort);
    }

    @GetMapping("/{id}/match")
    @Operation(summary = "Match one visible job to the authenticated candidate")
    public JobMatchResponse match(@PathVariable String id) { return matching.match(id); }
}
