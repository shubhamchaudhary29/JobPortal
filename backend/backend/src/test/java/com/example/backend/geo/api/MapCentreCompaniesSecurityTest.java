package com.example.backend.geo.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class MapCentreCompaniesSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean MapJobService jobs;

    @Test
    void centreCompaniesArePublicAndNothingElseIsWidened() throws Exception {
        when(jobs.centreCompanies(eq("1277333"), any())).thenReturn(new MapJobService.CentreCompanies("1277333", "Bengaluru",
                3, List.of(new MapJobService.CentreCompany("Wonka", 3))));
        when(jobs.centreCompanies(eq("999"), any())).thenThrow(new ResourceNotFoundException("Place not found"));

        mvc.perform(get("/api/v1/jobs/map/places/1277333/companies")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public, max-age=60"))
                .andExpect(jsonPath("$.companies[0].company").value("Wonka"));
        mvc.perform(get("/api/v1/jobs/map/places/999/companies")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/jobs/map/places/1277333/companies")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/jobs/map/places/1277333")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/jobs/map/places/1277333/companies/extra")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/jobs/map/companies")).andExpect(status().isUnauthorized());
    }

    @Test
    void companyFilteredPlaceListIsPublic() throws Exception {
        MapJobService.MapJob job = new MapJobService.MapJob("job-1", "Engineer", "Wonka", "Bengaluru- We Work", null, null,
                null, "lever", null, null, List.of(new MapJobService.MapJobPlace("1277333", "Bengaluru", null, null, null, null,
                null, "city", "We Work")));
        when(jobs.place(eq("1277333"), eq("unlisted"), eq("Wonka"), any(), any(), any()))
                .thenReturn(new MapJobService.MapJobSlice(List.of(job), 0, 20, false, null));

        mvc.perform(get("/api/v1/jobs/map/places/1277333/jobs").param("scope", "unlisted").param("company", "Wonka"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].places[0].precision").value("city"))
                .andExpect(jsonPath("$.content[0].places[0].siteLabel").value("We Work"));
    }
}
