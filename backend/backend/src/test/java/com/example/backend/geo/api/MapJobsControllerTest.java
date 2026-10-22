package com.example.backend.geo.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.backend.geo.application.MapJobService;
import com.example.backend.shared.error.BadRequestException;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest
@AutoConfigureMockMvc
class MapJobsControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean MapJobService jobs;
    private MapJobService.MapJobSlice slice;

    @BeforeEach
    void setUp() {
        MapJobService.MapJob job = new MapJobService.MapJob("job-1", "Engineer", "Acme", "Bengaluru, Karnataka",
                "full_time", 1.0, 2.0, "adzuna", LocalDateTime.of(2026, 10, 1, 0, 0), null,
                List.of(new MapJobService.MapJobPlace("1277333", "Bengaluru", null)));
        slice = new MapJobService.MapJobSlice(List.of(job), 0, 20, false, 1L);
        when(jobs.place(eq("1277333"), any(), any(), any(), any())).thenReturn(slice);
        when(jobs.unplaced(eq("remote"), any(), any(), any())).thenReturn(slice);
        when(jobs.nearby(anyDouble(), anyDouble(), any(), any(), any(), any())).thenReturn(new MapJobService.NearbyResult(
                12.95, 77.6, 25, List.of(new MapJobService.NearbyPlace("1277333", "Bengaluru", "Karnataka", 12.97,
                77.59, 2.4, 1)), slice));
    }

    @Test
    void allThreeListsAreAnonymousWithTheirCacheHeaders() throws Exception {
        mvc.perform(get("/api/v1/jobs/map/places/1277333/jobs"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=60"))
                .andExpect(jsonPath("$.content[0].id").value("job-1"))
                .andExpect(jsonPath("$.content[0].source").value("adzuna"))
                .andExpect(jsonPath("$.totalHint").value(1))
                .andExpect(jsonPath("$.content[0].description").doesNotExist())
                .andExpect(jsonPath("$.content[0].recruiterId").doesNotExist())
                .andExpect(jsonPath("$.content[0].externalId").doesNotExist())
                .andExpect(jsonPath("$.content[0].sourceIdentities").doesNotExist())
                .andExpect(jsonPath("$.content[0].applicationUrl").doesNotExist());
        mvc.perform(get("/api/v1/jobs/map/nearby").param("lat", "12.97").param("lng", "77.59"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "private, max-age=60"))
                .andExpect(jsonPath("$.centerLat").value(12.95))
                .andExpect(jsonPath("$.places[0].distanceKm").value(2.4))
                .andExpect(jsonPath("$.jobs.content[0].description").doesNotExist());
        mvc.perform(get("/api/v1/jobs/map/unplaced").param("kind", "remote"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "public, max-age=60"));
    }

    @Test
    void missingSnapshotAndTimeoutsAreServiceUnavailableWithDifferentRetryAfter() throws Exception {
        when(jobs.place(eq("1"), any(), any(), any(), any())).thenThrow(
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, MapJobService.SNAPSHOT_UNAVAILABLE));
        when(jobs.place(eq("2"), any(), any(), any(), any())).thenThrow(
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, MapJobService.QUERY_TIMEOUT));

        mvc.perform(get("/api/v1/jobs/map/places/1/jobs"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "10"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value(MapJobService.SNAPSHOT_UNAVAILABLE));
        mvc.perform(get("/api/v1/jobs/map/places/2/jobs"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "5"))
                .andExpect(jsonPath("$.code").value(MapJobService.QUERY_TIMEOUT));
    }

    @Test
    void invalidParametersAreBadRequests() throws Exception {
        when(jobs.unplaced(eq("foreign"), any(), any(), any())).thenThrow(new BadRequestException("kind must be remote or india"));
        mvc.perform(get("/api/v1/jobs/map/unplaced").param("kind", "foreign")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/jobs/map/nearby").param("lat", "12.97")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/jobs/map/nearby").param("lat", "north").param("lng", "77")).andExpect(status().isBadRequest());
        when(jobs.place(eq("1277333"), eq("nearby"), any(), any(), any())).thenThrow(new BadRequestException("scope must be all or unlisted"));
        mvc.perform(get("/api/v1/jobs/map/places/1277333/jobs").param("scope", "nearby")).andExpect(status().isBadRequest());
        when(jobs.locality(eq("bad id"), any(), any(), any())).thenThrow(new BadRequestException("Invalid locality id"));
        mvc.perform(get("/api/v1/jobs/map/localities/bad id/jobs")).andExpect(status().isBadRequest());
    }

    @Test
    void placeScopeIsPassedThrough() throws Exception {
        when(jobs.place(eq("1277333"), eq("unlisted"), any(), any(), any())).thenReturn(slice);
        mvc.perform(get("/api/v1/jobs/map/places/1277333/jobs").param("scope", "unlisted"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].id").value("job-1"));
    }

    @Test
    void writesAreNotPermitted() throws Exception {
        for (String path : List.of("/api/v1/jobs/map/places/1277333/jobs", "/api/v1/jobs/map/nearby",
                "/api/v1/jobs/map/unplaced", "/api/v1/jobs/map/localities/osm-n377219489/jobs")) {
            int code = mvc.perform(post(path)).andReturn().getResponse().getStatus();
            assertTrue(code == 401 || code == 403 || code == 405, path + " POST returned " + code);
        }
    }
}
