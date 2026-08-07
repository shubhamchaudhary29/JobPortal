package com.example.backend.job.api;

import com.example.backend.job.api.dto.CreateJobRequest;
import com.example.backend.job.api.dto.JobResponse;
import com.example.backend.job.api.dto.UpdateJobRequest;
import com.example.backend.job.application.JobService;
import com.example.backend.shared.pagination.PageRequestFactory;
import com.example.backend.shared.pagination.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/jobs")
@Tag(name = "Jobs", description = "Paginated jobs; sortable by createdAt, title, location, company, salary, or experience")
public class JobController {
    private static final Set<String> SORTS = Set.of("createdAt", "title", "location", "company", "salary", "experience");
    private final JobService jobs;

    public JobController(JobService jobs) { this.jobs = jobs; }

    @PostMapping
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Create a job", description = "Recruiters only")
    public ResponseEntity<JobResponse> create(@Valid @RequestBody CreateJobRequest request) {
        JobResponse response = jobs.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(response.id()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping
    @Operation(summary = "Search jobs", description = "Filters: q, location, source. Defaults: page=0, size=20, sort=createdAt,desc; maximum size=100")
    public PageResponse<JobResponse> search(@RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size,
                                            @RequestParam(defaultValue = "createdAt,desc") String sort,
                                            @RequestParam(required = false) String q,
                                            @RequestParam(required = false) String location,
                                            @RequestParam(required = false) String source) {
        return jobs.search(q, location, source, page(page, size, sort));
    }

    @GetMapping("/mine")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "List jobs owned by the authenticated recruiter")
    public PageResponse<JobResponse> mine(@RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size,
                                          @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return jobs.mine(page(page, size, sort));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a job")
    public JobResponse get(@PathVariable String id) { return jobs.get(id); }

    @PutMapping("/{id}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Update an owned job")
    public JobResponse update(@PathVariable String id, @Valid @RequestBody UpdateJobRequest request) {
        return jobs.update(id, request);
    }

    @DeleteMapping("/{id}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Delete an owned job")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        jobs.delete(id);
        return ResponseEntity.noContent().build();
    }

    private Pageable page(int page, int size, String sort) {
        return PageRequestFactory.create(page, size, sort, SORTS, "createdAt");
    }
}
