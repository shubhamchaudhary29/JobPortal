package com.example.backend.integration.aggregation.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.backend.integration.adzuna.AdzunaIngestionCoordinator;
import com.example.backend.integration.adzuna.AdzunaService;
import com.example.backend.integration.aggregation.AggregationConflictService;
import com.example.backend.integration.aggregation.EmployerIngestionService;
import com.example.backend.integration.aggregation.EmployerRegistryProperties;
import com.example.backend.integration.aggregation.IngestionAdminService;
import com.example.backend.integration.aggregation.IngestionCoordinator;
import com.example.backend.integration.aggregation.SyncRunService;
import com.example.backend.shared.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AdminManualSyncSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;
    @MockitoBean IngestionCoordinator coordinator;
    @MockitoBean AdzunaIngestionCoordinator adzuna;
    @MockitoBean IngestionAdminService admin;
    @MockitoBean AggregationConflictService conflicts;
    @MockitoBean SyncRunService runs;

    @Test
    void providerAndEmployerManualSyncRequireAdmin() throws Exception {
        when(coordinator.run(EmployerRegistryProperties.Source.GREENHOUSE, "board",
                SyncRunService.Trigger.MANUAL)).thenReturn(new IngestionCoordinator.Result(
                new EmployerIngestionService.Result(1, 0, 0, 0, 0), false, false, "run-greenhouse"));

        mvc.perform(post("/api/v1/admin/ingestion/greenhouse/sync?employer=board"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/ingestion/greenhouse/sync?employer=board")
                        .header(HttpHeaders.AUTHORIZATION, bearer("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/ingestion/greenhouse/sync?employer=board")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-greenhouse"))
                .andExpect(jsonPath("$.sync.inserted").value(1));
    }

    @Test
    void adzunaProviderWideSyncUsesItsSharedCoordinator() throws Exception {
        when(adzuna.run(SyncRunService.Trigger.MANUAL)).thenReturn(new AdzunaIngestionCoordinator.Result(
                new AdzunaService.SyncResult(1, 0, 0, 0, 0, 0, AdzunaService.Outcome.FULL_SUCCESS),
                false, false, "run-adzuna"));

        mvc.perform(post("/api/v1/admin/ingestion/adzuna/sync")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-adzuna"));
        mvc.perform(post("/api/v1/admin/ingestion/adzuna/sync?employer=board")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        mvc.perform(post("/api/v1/admin/ingestion/unknown/sync")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lockedManualSyncReturnsConflictWithRunId() throws Exception {
        when(coordinator.run(EmployerRegistryProperties.Source.LEVER, null,
                SyncRunService.Trigger.MANUAL)).thenReturn(
                new IngestionCoordinator.Result(null, true, false, "run-locked"));

        mvc.perform(post("/api/v1/admin/ingestion/lever/sync")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("LOCKED"))
                .andExpect(jsonPath("$.runId").value("run-locked"));
    }

    private String bearer(String role) {
        return "Bearer " + jwt.generateToken("actor@example.com", role);
    }
}
