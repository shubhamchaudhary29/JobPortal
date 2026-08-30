package com.example.backend.candidate.api;

import com.example.backend.candidate.application.CandidateProfileService;
import com.example.backend.shared.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CandidateProfileSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;
    @MockitoBean CandidateProfileService profiles;

    @Test
    void everyCandidateProfileAndResumeOperationRequiresCandidateRole() throws Exception {
        mvc.perform(get("/api/v1/candidate-profile")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/candidate-profile").header(HttpHeaders.AUTHORIZATION, bearer("RECRUITER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/candidate-profile").header(HttpHeaders.AUTHORIZATION, bearer("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/candidate-profile").header(HttpHeaders.AUTHORIZATION, bearer("USER")))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/v1/candidate-profile/resume").header(HttpHeaders.AUTHORIZATION, bearer("USER")))
                .andExpect(status().isNoContent());
    }

    @Test
    void routesExposeNoOtherCandidateIdentifierForIdor() throws Exception {
        mvc.perform(get("/api/v1/candidate-profile/candidate-b")
                        .header(HttpHeaders.AUTHORIZATION, bearer("USER")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/candidate-profile/candidate-b/resume")
                        .header(HttpHeaders.AUTHORIZATION, bearer("USER")))
                .andExpect(status().isNotFound());
    }

    private String bearer(String role) { return "Bearer " + jwt.generateToken("candidate-a@example.test", role); }
}
