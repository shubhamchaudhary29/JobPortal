package com.example.backend.integration.aggregation.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.backend.integration.aggregation.AggregationConflictService;
import com.example.backend.integration.aggregation.IngestionAdminService;
import com.example.backend.integration.aggregation.IngestionCoordinator;
import com.example.backend.integration.aggregation.SyncRunService;
import com.example.backend.shared.pagination.PageResponse;
import com.example.backend.shared.security.JwtUtil;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AdminSyncHistorySecurityTest {
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;
    @MockitoBean IngestionCoordinator coordinator;
    @MockitoBean IngestionAdminService admin;
    @MockitoBean AggregationConflictService conflicts;
    @MockitoBean SyncRunService runs;

    @Test
    void historyRequiresAdminAndReturnsBoundedPage() throws Exception {
        when(runs.history("lever", "board", "FAILED", "MANUAL", 0, 20)).thenReturn(
                new PageResponse<>(List.of(view("run-1")), 0, 20, 1, 1, true, true, List.of()));

        mvc.perform(get("/api/v1/admin/ingestion/history"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/ingestion/history")
                        .header(HttpHeaders.AUTHORIZATION, bearer("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/ingestion/history?provider=lever&employer=board&outcome=FAILED&trigger=MANUAL")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].runId").value("run-1"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void detailAndLatestStatusExposeOnlyBoundedOperationalViews() throws Exception {
        when(runs.detail("run-1")).thenReturn(view("run-1"));
        when(runs.latest(null, null)).thenReturn(List.of(view("run-1")));
        when(admin.counts()).thenReturn(new IngestionAdminService.Counts(7, 2));
        when(admin.providerCompanyCounts()).thenReturn(List.of(
                new IngestionAdminService.ProviderCompanyCount("lever", "board", "Acme", 3, 1)));

        mvc.perform(get("/api/v1/admin/ingestion/history/run-1")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureDetail").value("safe failure"))
                .andExpect(jsonPath("$.expiresAt").doesNotExist());
        mvc.perform(get("/api/v1/admin/ingestion/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestRuns[0].runId").value("run-1"))
                .andExpect(jsonPath("$.activeImportedJobs").value(7))
                .andExpect(jsonPath("$.providerCompanyCounts[0].company").value("Acme"));
    }

    private SyncRunService.RunView view(String id) {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return new SyncRunService.RunView(id, "lever", "board", SyncRunService.Trigger.MANUAL,
                now, now.plusSeconds(2), SyncRunService.Outcome.FAILED,
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
                "ProviderException", "safe failure");
    }

    private String bearer(String role) {
        return "Bearer " + jwt.generateToken("actor@example.com", role);
    }
}
