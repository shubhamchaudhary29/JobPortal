package com.example.backend.integration.aggregation.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.backend.integration.aggregation.AggregationConflictDocument;
import com.example.backend.integration.aggregation.AggregationConflictService;
import com.example.backend.integration.aggregation.IngestionAdminService;
import com.example.backend.integration.aggregation.IngestionCoordinator;
import com.example.backend.shared.pagination.PageResponse;
import com.example.backend.shared.security.JwtUtil;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AdminConflictSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;
    @MockitoBean IngestionCoordinator coordinator;
    @MockitoBean IngestionAdminService admin;
    @MockitoBean AggregationConflictService conflicts;

    @Test
    void conflictListingRequiresAdminAndReturnsBoundedPage() throws Exception {
        AggregationConflictService.ConflictView view = view(AggregationConflictDocument.Status.OPEN);
        when(conflicts.list("OPEN", 0, 20)).thenReturn(new PageResponse<>(
                List.of(view), 0, 20, 1, 1, true, true, List.of()));

        mvc.perform(get("/api/v1/admin/ingestion/conflicts?status=OPEN"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/ingestion/conflicts?status=OPEN")
                        .header(HttpHeaders.AUTHORIZATION, bearer("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/ingestion/conflicts?status=OPEN")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("conflict-1"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void adminCanResolveConflict() throws Exception {
        when(conflicts.resolve(eq("conflict-1"), eq("canonical"), eq("duplicate"),
                eq("actor@example.com"))).thenReturn(view(AggregationConflictDocument.Status.RESOLVED));

        mvc.perform(post("/api/v1/admin/ingestion/conflicts/conflict-1/resolution")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"canonicalJobId\":\"canonical\",\"duplicateJobId\":\"duplicate\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
    }

    private AggregationConflictService.ConflictView view(AggregationConflictDocument.Status status) {
        LocalDateTime now = LocalDateTime.of(2026, 5, 1, 0, 0);
        return new AggregationConflictService.ConflictView("conflict-1",
                AggregationConflictDocument.Type.IDENTITY_FINGERPRINT, status,
                "greenhouse:board:one", "fingerprint", Set.of("canonical", "duplicate"),
                now, now, 1, status == AggregationConflictDocument.Status.RESOLVED ? "canonical" : null,
                status == AggregationConflictDocument.Status.RESOLVED ? "duplicate" : null,
                status == AggregationConflictDocument.Status.RESOLVED ? now : null,
                status == AggregationConflictDocument.Status.RESOLVED ? "actor@example.com" : null, null);
    }

    private String bearer(String role) {
        return "Bearer " + jwt.generateToken("actor@example.com", role);
    }
}
