package com.example.backend.matching.api;

import com.example.backend.matching.application.JobMatchingService;
import com.example.backend.shared.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class JobMatchingSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;
    @MockitoBean JobMatchingService matching;

    @Test
    void matchingRoutesRequireCandidateAuthentication() throws Exception {
        for (String path : new String[]{"/api/v1/jobs/matched", "/api/v1/jobs/job-1/match"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer("RECRUITER"))).andExpect(status().isForbidden());
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))).andExpect(status().isForbidden());
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer("USER"))).andExpect(status().isOk());
        }
    }

    @Test
    void controllerAcceptsOnlyJobIdAndNeverAnotherCandidateId() throws Exception {
        mvc.perform(get("/api/v1/jobs/job-1/match?candidateId=someone-else")
                        .header(HttpHeaders.AUTHORIZATION, bearer("USER")))
                .andExpect(status().isOk());
        verify(matching).match("job-1");
    }

    private String bearer(String role) { return "Bearer " + jwt.generateToken("candidate@example.test", role); }
}
