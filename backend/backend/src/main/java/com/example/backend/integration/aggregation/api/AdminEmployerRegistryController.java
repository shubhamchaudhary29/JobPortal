package com.example.backend.integration.aggregation.api;

import com.example.backend.integration.aggregation.EmployerRegistryAdminService;
import com.example.backend.shared.pagination.PageResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

/** ADMIN-only employer registry management; changes are audited with the acting administrator. */
@RestController
@RequestMapping("/api/v1/admin/ingestion/registry")
@Tag(name = "Employer registry administration", description = "ADMIN-only, audited ATS board registry")
@SecurityRequirement(name = "bearerAuth")
public class AdminEmployerRegistryController {
    private final EmployerRegistryAdminService registry;

    public AdminEmployerRegistryController(EmployerRegistryAdminService registry) { this.registry = registry; }

    @GetMapping
    public PageResponse<EmployerRegistryAdminService.EntryView> list(
            @RequestParam(required = false) String provider, @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return registry.list(provider, enabled, q, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmployerRegistryAdminService.EntryView create(@Valid @RequestBody CreateRequest request,
                                                         Authentication authentication) {
        return registry.create(request.provider(), request.boardId(), request.company(), request.enabled(),
                actor(authentication));
    }

    @PatchMapping("/{id}")
    public EmployerRegistryAdminService.EntryView update(@PathVariable String id, @Valid @RequestBody UpdateRequest request,
                                                         Authentication authentication) {
        return registry.update(id, request.company(), request.enabled(), request.disabledReason(), actor(authentication));
    }

    @PostMapping("/{id}/validation")
    public EmployerRegistryAdminService.ValidationView validate(@PathVariable String id, Authentication authentication) {
        return registry.validate(id, actor(authentication));
    }

    @GetMapping("/{id}/audit")
    public PageResponse<EmployerRegistryAdminService.AuditView> audit(@PathVariable String id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return registry.audit(id, page, size);
    }

    private static String actor(Authentication authentication) {
        return authentication == null ? "unknown" : authentication.getName();
    }

    public record CreateRequest(@NotBlank @Size(max = 40) String provider, @NotBlank @Size(max = 100) String boardId,
                                @NotBlank @Size(max = 200) String company, Boolean enabled) { }

    public record UpdateRequest(@Size(max = 200) String company, Boolean enabled, @Size(max = 300) String disabledReason) { }
}
