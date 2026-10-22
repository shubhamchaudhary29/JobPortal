package com.example.backend.geo.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.backend.geo.application.MapJobService;
import com.example.backend.shared.error.ResourceNotFoundException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class MapOfficeJobsSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean MapJobService jobs;

    @Test
    void officeJobListIsPublicAndNothingElseIsWidened() throws Exception {
        MapJobService.MapJob job = new MapJobService.MapJob("job-1", "Engineer", "Acme", "Bengaluru", null, null, null,
                "adzuna", null, null, List.of(new MapJobService.MapJobPlace("1277333", "Bengaluru", null, "o1", "Bellandur")));
        when(jobs.office(eq("o1"), any(), any(), any())).thenReturn(new MapJobService.MapJobSlice(List.of(job), 0, 20, false, 1L));
        when(jobs.office(eq("missing"), any(), any(), any())).thenThrow(new ResourceNotFoundException("Office not found"));

        mvc.perform(get("/api/v1/jobs/map/offices/o1/jobs")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public, max-age=60"))
                .andExpect(jsonPath("$.content[0].places[0].officeLabel").value("Bellandur"))
                .andExpect(jsonPath("$.content[0].places[0].officeId").value("o1"));
        mvc.perform(get("/api/v1/jobs/map/offices/missing/jobs")).andExpect(status().isNotFound());
        // An auto-applied OpenStreetMap office (GEO-8D) has an osm-… id on the same public endpoint.
        when(jobs.office(eq("osm-n101"), any(), any(), any())).thenReturn(new MapJobService.MapJobSlice(List.of(job), 0, 20, false, 1L));
        mvc.perform(get("/api/v1/jobs/map/offices/osm-n101/jobs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("job-1"));
        mvc.perform(get("/api/v1/jobs/map/offices")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/jobs/map/offices/o1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/jobs/map/offices/o1/jobs/extra")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/geo/offices")).andExpect(status().isUnauthorized());
    }

    @Test
    void localityJobListIsPublicAndNothingElseIsWidened() throws Exception {
        MapJobService.MapJob job = new MapJobService.MapJob("job-1", "Engineer", "Acme", "Whitefield, Bengaluru", null, null,
                null, "lever", null, null, List.of(new MapJobService.MapJobPlace("1277333", "Bengaluru", null, null, null,
                "osm-n377219489", "Whitefield")));
        when(jobs.locality(eq("osm-n377219489"), any(), any(), any()))
                .thenReturn(new MapJobService.MapJobSlice(List.of(job), 0, 20, false, 1L));
        when(jobs.locality(eq("osm-n1"), any(), any(), any())).thenThrow(new ResourceNotFoundException("Locality not found"));

        mvc.perform(get("/api/v1/jobs/map/localities/osm-n377219489/jobs")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public, max-age=60"))
                .andExpect(jsonPath("$.content[0].places[0].localityId").value("osm-n377219489"))
                .andExpect(jsonPath("$.content[0].places[0].localityName").value("Whitefield"));
        mvc.perform(get("/api/v1/jobs/map/localities/osm-n1/jobs")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/jobs/map/localities")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/jobs/map/localities/osm-n377219489")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/jobs/map/localities/osm-n377219489/jobs/extra")).andExpect(status().isUnauthorized());
    }
}
