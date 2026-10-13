package com.example.backend.integration.aggregation.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.backend.integration.search.SearchIngestionCoordinator;
import com.example.backend.integration.search.SearchIngestionCoordinators;
import com.example.backend.integration.search.SearchSyncResult;
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
    @MockitoBean SearchIngestionCoordinators search;
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
                .andExpect(jsonPath("$.outcome").value("COMPLETED"))
                .andExpect(jsonPath("$.sync.inserted").value(1));
    }

    @Test
    void adzunaProviderWideSyncUsesItsSharedCoordinator() throws Exception {
        SearchIngestionCoordinator adzuna = org.mockito.Mockito.mock(SearchIngestionCoordinator.class);
        when(search.find("adzuna")).thenReturn(java.util.Optional.of(adzuna));
        when(adzuna.run(SyncRunService.Trigger.MANUAL)).thenReturn(new SearchIngestionCoordinator.Result(
                new SearchSyncResult(1, 0, 0, 0, 0, 0, 0, 0, 0, 1, SearchSyncResult.Outcome.FULL_SUCCESS, false),
                false, false, "run-adzuna", SyncRunService.Outcome.COMPLETED));

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

        when(coordinator.run(EmployerRegistryProperties.Source.GREENHOUSE, null,
                SyncRunService.Trigger.MANUAL)).thenReturn(
                new IngestionCoordinator.Result(new EmployerIngestionService.Result(1, 0, 0, 0, 0),
                        false, true, "run-lost"));
        mvc.perform(post("/api/v1/admin/ingestion/greenhouse/sync")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("LEASE_LOST"))
                .andExpect(jsonPath("$.runId").value("run-lost"));
    }

    @Test
    void providerCatalogIsAdminOnlyAndListsEveryProviderInCatalogOrder() throws Exception {
        mvc.perform(get("/api/v1/admin/ingestion/providers")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/ingestion/providers")
                        .header(HttpHeaders.AUTHORIZATION, bearer("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/ingestion/providers")
                        .header(HttpHeaders.AUTHORIZATION, bearer("RECRUITER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/ingestion/providers")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].name").value("adzuna"))
                .andExpect(jsonPath("$[0].kind").value("SEARCH_API"))
                .andExpect(jsonPath("$[0].displayName").value("Adzuna"))
                .andExpect(jsonPath("$[0].employerScoped").value(false))
                .andExpect(jsonPath("$[1].name").value("greenhouse"))
                .andExpect(jsonPath("$[1].kind").value("ATS_BOARD"))
                .andExpect(jsonPath("$[1].employerScoped").value(true))
                .andExpect(jsonPath("$[2].name").value("lever"))
                .andExpect(jsonPath("$[3].name").value("ashby"))
                .andExpect(jsonPath("$[3].kind").value("ATS_BOARD"))
                .andExpect(jsonPath("$[3].employerScoped").value(true))
                .andExpect(jsonPath("$[4].name").value("smartrecruiters"))
                .andExpect(jsonPath("$[4].displayName").value("SmartRecruiters"))
                .andExpect(jsonPath("$[5].name").value("workable"))
                .andExpect(jsonPath("$[0].scheduleProperty").doesNotExist())
                .andExpect(jsonPath("$[1].defaultBaseUrl").doesNotExist());
    }

    @Test
    void unknownProvidersAreStillRejectedBeforeAnyCoordinatorRuns() throws Exception {
        mvc.perform(post("/api/v1/admin/ingestion/%20lever/sync")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isBadRequest());
        for (String provider : new String[] {"unknown", "other", "linkedin", "GREENHOUSE2"}) {
            mvc.perform(post("/api/v1/admin/ingestion/" + provider + "/sync")
                            .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        }
        org.mockito.Mockito.verifyNoInteractions(coordinator, search);
        when(coordinator.run(EmployerRegistryProperties.Source.LEVER, null, SyncRunService.Trigger.MANUAL))
                .thenReturn(new IngestionCoordinator.Result(new EmployerIngestionService.Result(0, 0, 1, 0, 0),
                        false, false, "run-lever"));
        mvc.perform(post("/api/v1/admin/ingestion/LeVeR/sync")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-lever"));
        when(coordinator.run(EmployerRegistryProperties.Source.ASHBY, "acme", SyncRunService.Trigger.MANUAL))
                .thenReturn(new IngestionCoordinator.Result(new EmployerIngestionService.Result(2, 0, 0, 0, 0),
                        false, false, "run-ashby"));
        mvc.perform(post("/api/v1/admin/ingestion/ashby/sync?employer=acme")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-ashby"))
                .andExpect(jsonPath("$.sync.inserted").value(2));
    }

    private String bearer(String role) {
        return "Bearer " + jwt.generateToken("actor@example.com", role);
    }
}
