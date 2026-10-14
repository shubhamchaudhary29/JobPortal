package com.example.backend.geo.api;

import com.example.backend.geo.application.MapJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Anonymous job lists for the map: at a place, near a point, and without a place. */
@RestController
@RequestMapping("/api/v1/jobs/map")
@Tag(name = "Job map", description = "Per-place job counts and job lists for the map")
public class MapJobsController {
    static final String PUBLIC_CACHE = "public, max-age=60";
    static final String PRIVATE_CACHE = "private, max-age=60";
    private final MapJobService jobs;

    public MapJobsController(MapJobService jobs) { this.jobs = jobs; }

    @GetMapping("/places/{placeId}/jobs")
    @Operation(summary = "Jobs at one place", description = "Newest first; slice paging (size 1-20, page 0-49)")
    public ResponseEntity<MapJobService.MapJobSlice> place(@PathVariable String placeId,
                                                           @RequestParam(required = false) String q,
                                                           @RequestParam(required = false) Integer page,
                                                           @RequestParam(required = false) Integer size) {
        return cached(PUBLIC_CACHE, jobs.place(placeId, q, page, size));
    }

    @GetMapping("/nearby")
    @Operation(summary = "Places and jobs near a point",
            description = "lat/lng are rounded to a 0.05° grid before use; radiusKm 1-100 (default 25)")
    public ResponseEntity<MapJobService.NearbyResult> nearby(@RequestParam Double lat, @RequestParam Double lng,
                                                             @RequestParam(required = false) Integer radiusKm,
                                                             @RequestParam(required = false) String q,
                                                             @RequestParam(required = false) Integer page,
                                                             @RequestParam(required = false) Integer size) {
        return cached(PRIVATE_CACHE, jobs.nearby(lat, lng, radiusKm, q, page, size));
    }

    @GetMapping("/unplaced")
    @Operation(summary = "Jobs without a place", description = "kind=remote, or kind=india for state/India-wide jobs")
    public ResponseEntity<MapJobService.MapJobSlice> unplaced(@RequestParam(required = false) String kind,
                                                              @RequestParam(required = false) String q,
                                                              @RequestParam(required = false) Integer page,
                                                              @RequestParam(required = false) Integer size) {
        return cached(PUBLIC_CACHE, jobs.unplaced(kind, q, page, size));
    }

    /** Service unavailability: no snapshot yet (retry in 10 s) or a query over its time budget (retry in 5 s). */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> unavailable(ResponseStatusException failure, HttpServletRequest request) {
        boolean timeout = MapJobService.QUERY_TIMEOUT.equals(failure.getReason());
        HttpStatus status = HttpStatus.valueOf(failure.getStatusCode().value());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, timeout
                ? "The job list took too long; retry shortly" : "The map snapshot is being built; retry shortly");
        problem.setType(URI.create("https://jobportal.example/problems/"
                + (timeout ? "map-query-timeout" : "snapshot-unavailable")));
        problem.setTitle(timeout ? "Map query timed out" : "Snapshot unavailable");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", timeout ? MapJobService.QUERY_TIMEOUT : MapJobService.SNAPSHOT_UNAVAILABLE);
        return ResponseEntity.status(status).header(HttpHeaders.RETRY_AFTER, timeout ? "5" : "10")
                .header(HttpHeaders.CACHE_CONTROL, "no-store").contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static <T> ResponseEntity<T> cached(String cacheControl, T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, cacheControl).body(body);
    }
}
