package com.example.backend.geo.api;

import com.example.backend.geo.application.CompanyOfficeService;
import com.example.backend.shared.pagination.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN-only company office pins; changes are audited with the acting administrator. */
@RestController
@RequestMapping("/api/v1/admin/geo/offices")
@Tag(name = "Company office administration", description = "ADMIN-only, audited office pins for the job map")
@SecurityRequirement(name = "bearerAuth")
public class AdminCompanyOfficeController {
    private final CompanyOfficeService offices;

    public AdminCompanyOfficeController(CompanyOfficeService offices) { this.offices = offices; }

    @GetMapping
    public PageResponse<CompanyOfficeService.OfficeView> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean enabled, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return offices.list(q, enabled, page, size);
    }

    @GetMapping("/candidates")
    @Operation(summary = "Pinning worklist", description = "(company, city) pairs with the most jobs and no enabled office")
    public List<CompanyOfficeService.Candidate> candidates(@RequestParam(defaultValue = "50") int limit) {
        return offices.candidates(limit);
    }

    @GetMapping("/osm-report")
    @Operation(summary = "OpenStreetMap office matches",
            description = "Pairs with no, one or several OSM candidates, jobs auto-apply would move, and the top 50 matches "
                    + "with openstreetmap.org links; review before setting GEO_OSM_OFFICE_AUTO_APPLY=true")
    public CompanyOfficeService.OsmReport osmReport() { return offices.osmReport(); }

    @PostMapping("/osm/{osmRef}/pick")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Use an OpenStreetMap match as the company's office",
            description = "Creates a normal audited office at the OSM point (60 km guard applies) and records a PICK decision")
    public CompanyOfficeService.OfficeView pickOsm(@PathVariable String osmRef, @Valid @RequestBody PickRequest request,
                                                   Authentication authentication) {
        return offices.pickOsm(osmRef, request.companyKey(), request.placeId(), request.label(), actor(authentication));
    }

    @PostMapping("/osm/{osmRef}/reject")
    @Operation(summary = "Not this company's office", description = "Records a REJECT decision; the match is never applied")
    public CompanyOfficeService.Decision rejectOsm(@PathVariable String osmRef, @Valid @RequestBody RejectRequest request,
                                                   Authentication authentication) {
        return offices.rejectOsm(osmRef, request.companyKey(), request.placeId(), actor(authentication));
    }

    @GetMapping("/{id}")
    public CompanyOfficeService.OfficeView get(@PathVariable String id) { return offices.get(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Pin an office", description = "placeId optional: defaults to the nearest city of 100k+ people; "
            + "the pin must be within 60 km of the city")
    public CompanyOfficeService.OfficeView create(@Valid @RequestBody CreateRequest request, Authentication authentication) {
        return offices.create(request.company(), request.label(), request.lat(), request.lng(), request.placeId(),
                actor(authentication));
    }

    @PatchMapping("/{id}")
    public CompanyOfficeService.OfficeView update(@PathVariable String id, @Valid @RequestBody UpdateRequest request,
                                                  Authentication authentication) {
        return offices.update(id, request.label(), request.lat(), request.lng(), request.enabled(), actor(authentication));
    }

    @GetMapping("/{id}/audit")
    public PageResponse<CompanyOfficeService.AuditView> audit(@PathVariable String id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return offices.audit(id, page, size);
    }

    private static String actor(Authentication authentication) {
        return authentication == null ? "unknown" : authentication.getName();
    }

    public record CreateRequest(@NotBlank @Size(max = 200) String company, @NotBlank @Size(max = 80) String label,
                                @NotNull Double lat, @NotNull Double lng, @Size(max = 20) String placeId) { }

    public record UpdateRequest(@Size(max = 80) String label, Double lat, Double lng, Boolean enabled) { }

    public record PickRequest(@NotBlank @Size(max = 200) String companyKey, @NotBlank @Size(max = 20) String placeId,
                              @Size(max = 80) String label) { }

    public record RejectRequest(@NotBlank @Size(max = 200) String companyKey, @NotBlank @Size(max = 20) String placeId) { }
}
