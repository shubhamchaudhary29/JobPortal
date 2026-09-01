package com.example.backend.copilot.api;

import com.example.backend.copilot.application.ApplicationCopilotAnalysisService;
import com.example.backend.copilot.application.ApplicationWorkspaceService;
import com.example.backend.copilot.application.CoverLetterService;
import com.example.backend.copilot.application.CopilotAccessService;
import com.example.backend.copilot.application.ResumeVersionService;
import com.example.backend.copilot.application.TailoredResumeDocxExporter;
import com.example.backend.copilot.infrastructure.TailoredResumeVersionDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class ApplicationCopilotControllerContractTest {
    @Autowired MockMvc mvc;
    @MockitoBean ApplicationCopilotAnalysisService analysis;
    @MockitoBean CopilotAccessService access;
    @MockitoBean ResumeVersionService resumes;
    @MockitoBean CoverLetterService coverLetters;
    @MockitoBean ApplicationWorkspaceService workspaces;
    @MockitoBean TailoredResumeDocxExporter exporter;

    @Test
    void rejectsUnboundedMalformedAndClientControlledOwnershipFields() throws Exception {
        mvc.perform(put("/api/v1/application-workspace/job-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"" + "x".repeat(5001) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/application-workspace/job-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"NOT_A_STAGE\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/cover-letters/letter-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/jobs/job-1/resume-versions").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"candidate-b\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void privateDocxExportUsesSafeDownloadHeaders() throws Exception {
        TailoredResumeVersionDocument version = new TailoredResumeVersionDocument(); version.setId("version-1");
        when(resumes.ownedDocument("version-1")).thenReturn(version);
        when(exporter.export(version)).thenReturn(new TailoredResumeDocxExporter.Export(new byte[]{1, 2, 3}, "tailored-resume-safe.docx"));
        mvc.perform(get("/api/v1/resume-versions/version-1/export"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/"))));
    }
}
