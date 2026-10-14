package com.example.backend.geo.api;

import com.example.backend.geo.application.MapSnapshotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public, cacheable map snapshot served from memory with precomputed raw and gzip bodies. */
@RestController
@RequestMapping("/api/v1/jobs/map")
@Tag(name = "Job map", description = "Per-place job counts for client-side map clustering")
public class MapSnapshotController {
    static final String CACHE_CONTROL = "public, max-age=60, stale-while-revalidate=240";
    private final MapSnapshotService snapshots;

    public MapSnapshotController(MapSnapshotService snapshots) { this.snapshots = snapshots; }

    @GetMapping("/snapshot")
    @Operation(summary = "Map snapshot: visible jobs per place",
            description = "Rebuilt every few minutes and served from memory. Supports If-None-Match (304) and gzip.")
    public ResponseEntity<?> snapshot(@RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
                                      @RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false) String acceptEncoding) {
        MapSnapshotService.Published published = snapshots.current().orElse(null);
        if (published == null) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                    "The map snapshot is being built; retry shortly");
            problem.setType(URI.create("https://jobportal.example/problems/snapshot-unavailable"));
            problem.setTitle("Snapshot unavailable");
            problem.setInstance(URI.create("/api/v1/jobs/map/snapshot"));
            problem.setProperty("code", "SNAPSHOT_UNAVAILABLE");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "10")
                    .header(HttpHeaders.CACHE_CONTROL, "no-store").contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(problem);
        }
        if (matches(ifNoneMatch, published.etag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).headers(cacheHeaders(published)).build();
        }
        boolean gzip = acceptsGzip(acceptEncoding);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok().headers(cacheHeaders(published))
                .contentType(MediaType.APPLICATION_JSON);
        if (gzip) response.header(HttpHeaders.CONTENT_ENCODING, "gzip");
        byte[] body = gzip ? published.gzip() : published.raw();
        return response.contentLength(body.length).body(body);
    }

    private static HttpHeaders cacheHeaders(MapSnapshotService.Published published) {
        HttpHeaders headers = new HttpHeaders();
        headers.setETag(published.etag());
        headers.setCacheControl(CACHE_CONTROL);
        headers.setVary(java.util.List.of(HttpHeaders.ACCEPT_ENCODING));
        return headers;
    }

    /** Weak comparison per RFC 9110: {@code W/} prefixes are ignored; {@code *} matches any current entity. */
    static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) return false;
        for (String candidate : ifNoneMatch.split(",")) {
            String tag = candidate.trim();
            if (tag.equals("*")) return true;
            if (tag.startsWith("W/")) tag = tag.substring(2);
            if (tag.equals(etag)) return true;
        }
        return false;
    }

    /** True when gzip is listed without {@code q=0}. */
    static boolean acceptsGzip(String acceptEncoding) {
        if (acceptEncoding == null) return false;
        for (String coding : acceptEncoding.toLowerCase(Locale.ROOT).split(",")) {
            String[] parts = coding.trim().split(";");
            if (!parts[0].trim().equals("gzip")) continue;
            for (int index = 1; index < parts.length; index++) {
                String parameter = parts[index].trim().replace(" ", "");
                if (parameter.matches("q=0(\\.0{0,3})?")) return false;
            }
            return true;
        }
        return false;
    }
}
