package com.example.backend.copilot.api;

import com.example.backend.copilot.application.ApplicationCopilotAnalysisService;
import com.example.backend.copilot.application.ApplicationWorkspaceService;
import com.example.backend.copilot.application.CoverLetterService;
import com.example.backend.copilot.application.CopilotAccessService;
import com.example.backend.copilot.application.ResumeVersionService;
import com.example.backend.copilot.application.TailoredResumeDocxExporter;
import com.example.backend.shared.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApplicationCopilotSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;
    @MockitoBean ApplicationCopilotAnalysisService analysis;
    @MockitoBean CopilotAccessService access;
    @MockitoBean ResumeVersionService resumes;
    @MockitoBean CoverLetterService coverLetters;
    @MockitoBean ApplicationWorkspaceService workspaces;
    @MockitoBean TailoredResumeDocxExporter exporter;

    @Test
    void allSensitiveRoutesRejectAnonymousRecruiterAndAdmin() throws Exception {
        for (String path : new String[]{"/api/v1/application-workspace", "/api/v1/resume-versions/version-1",
                "/api/v1/cover-letters/letter-1", "/api/v1/jobs/job-1/application-readiness",
                "/api/v1/jobs/job-1/tailoring-plan", "/api/v1/jobs/job-1/resume-versions",
                "/api/v1/jobs/job-1/cover-letters"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer("RECRUITER"))).andExpect(status().isForbidden());
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer("ADMIN"))).andExpect(status().isForbidden());
        }
    }

    @Test
    void authenticatedCandidateCanReachWorkspaceWithoutSupplyingUserId() throws Exception {
        mvc.perform(get("/api/v1/application-workspace?userId=attacker-controlled")
                .header(HttpHeaders.AUTHORIZATION, bearer("USER"))).andExpect(status().isOk());
    }

    private String bearer(String role) { return "Bearer " + jwt.generateToken("candidate@example.test", role); }
}
