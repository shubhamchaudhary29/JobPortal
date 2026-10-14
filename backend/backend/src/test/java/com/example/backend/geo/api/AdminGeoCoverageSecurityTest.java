package com.example.backend.geo.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.backend.geo.application.GeoCoverageService;
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

    private String bearer(String role) {
        return "Bearer " + jwt.generateToken("actor@example.com", role);
    }
}
