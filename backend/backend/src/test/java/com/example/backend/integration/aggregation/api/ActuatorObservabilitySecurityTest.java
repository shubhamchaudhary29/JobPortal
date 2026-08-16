package com.example.backend.integration.aggregation.api;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.backend.integration.aggregation.AggregationMetrics;
import com.example.backend.integration.aggregation.SyncRunService;
import com.example.backend.shared.security.JwtUtil;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ActuatorObservabilitySecurityTest {
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;
    @Autowired AggregationMetrics metrics;

    @Test
    void actuatorRequiresAdminAndHealthNeverDisclosesComponentsOrSecrets() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health").header(HttpHeaders.AUTHORIZATION, bearer("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/health").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(content().string(not(containsString("mongo"))))
                .andExpect(content().string(not(containsString("mongodb://"))))
                .andExpect(content().string(not(containsString("details"))));
    }

    @Test
    void metricsAreAdminOnlyAndUnexposedSensitiveEndpointsStayUnavailable() throws Exception {
        metrics.record("greenhouse", SyncRunService.Outcome.COMPLETED, SyncRunService.Trigger.SCHEDULED,
                new SyncRunService.Counts(1, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 1),
                Duration.ofMillis(25), false);

        mvc.perform(get("/actuator/metrics/jobportal.aggregation.runs"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics/jobportal.aggregation.runs")
                        .header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("jobportal.aggregation.runs"))
                .andExpect(content().string(not(containsString("employer"))))
                .andExpect(content().string(not(containsString("run_id"))));
        mvc.perform(get("/actuator").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._links.health").exists())
                .andExpect(jsonPath("$._links.metrics").exists())
                .andExpect(jsonPath("$._links.env").doesNotExist())
                .andExpect(jsonPath("$._links.configprops").doesNotExist());
    }

    private String bearer(String role) {
        return "Bearer " + jwt.generateToken("operator@example.com", role);
    }
}
