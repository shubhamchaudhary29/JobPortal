package com.example.backend.geo.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.backend.geo.application.GeoCoverageService;
import com.example.backend.geo.application.GeoPrecisionService;
import com.example.backend.shared.security.JwtUtil;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AdminGeoCoverageSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;
    @MockitoBean GeoCoverageService coverage;
    @MockitoBean GeoPrecisionService precision;

    @Test
    void coverageRequiresAdmin() throws Exception {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        byStatus.put("PLACE", 3L);
        when(coverage.coverage()).thenReturn(new GeoCoverageService.Coverage("1-abc", 4, byStatus, 75.0, 1,
                List.of(new GeoCoverageService.UnresolvedLocation("Multiple Locations", 2, Instant.EPOCH))));

        mvc.perform(get("/api/v1/admin/geo/coverage"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/geo/coverage").header(HttpHeaders.AUTHORIZATION, bearer("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/geo/coverage").header(HttpHeaders.AUTHORIZATION, bearer("RECRUITER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/geo/coverage").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolverVersion").value("1-abc"))
                .andExpect(jsonPath("$.byStatus.PLACE").value(3))
                .andExpect(jsonPath("$.placeResolvedPercent").value(75.0))
                .andExpect(jsonPath("$.topUnresolved[0].location").value("Multiple Locations"));
    }

    @Test
    void precisionRequiresAdmin() throws Exception {
        Map<String, Long> byPrecision = new LinkedHashMap<>();
        byPrecision.put("office", 1L);
        byPrecision.put("locality", 0L);
        byPrecision.put("city", 5L);
        when(precision.precision()).thenReturn(new GeoPrecisionService.Precision("3-abc", Instant.EPOCH, 6, byPrecision,
                List.of(new GeoPrecisionService.LocationCount("Bengaluru, Karnataka, India", 4)),
                List.of(new GeoPrecisionService.CityGain("1277333", "Bengaluru", 5, 4, 1, 4, 0, 0, 120)),
                new GeoPrecisionService.AdzunaPoints(2, 1, 1, 0, 0), new GeoPrecisionService.Ncr(1, 10, 10.0),
                new GeoPrecisionService.ReResolution(30000, 0.0)));

        mvc.perform(get("/api/v1/admin/geo/precision")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/geo/precision").header(HttpHeaders.AUTHORIZATION, bearer("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/geo/precision").header(HttpHeaders.AUTHORIZATION, bearer("RECRUITER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/geo/precision").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.byPrecision.city").value(5))
                .andExpect(jsonPath("$.topCityCentreStrings[0].location").value("Bengaluru, Karnataka, India"))
                .andExpect(jsonPath("$.topCities[0].centreJobs").value(4))
                .andExpect(jsonPath("$.ncr.percentOfDelhi").value(10.0));
    }

    private String bearer(String role) {
        return "Bearer " + jwt.generateToken("actor@example.com", role);
    }
}
