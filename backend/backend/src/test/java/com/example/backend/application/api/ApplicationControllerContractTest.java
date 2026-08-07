package com.example.backend.application.api;

import com.example.backend.application.api.dto.ApplicationResponse;
import com.example.backend.application.application.ApplicationService;
import com.example.backend.application.domain.ApplicationStatus;
import com.example.backend.shared.error.GlobalExceptionHandler;
import com.example.backend.shared.security.JwtUtil;
import com.example.backend.user.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ApplicationController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class ApplicationControllerContractTest {
    private final MockMvc mvc;
    @MockitoBean ApplicationService applications;
    @MockitoBean JwtUtil jwtUtil;
    @MockitoBean UserRepository users;

    @Autowired
    ApplicationControllerContractTest(MockMvc mvc) { this.mvc = mvc; }

    @Test
    void creationReturns201LocationAndNeverExposesResumeStorage() throws Exception {
        when(applications.apply(eq("job-1"), any())).thenReturn(
                new ApplicationResponse("application-1", "job-1", "candidate@example.test",
                        ApplicationStatus.APPLIED, null));
        var pdf = new MockMultipartFile("file", "resume.pdf", MediaType.APPLICATION_PDF_VALUE,
                "%PDF-1.4\n%%EOF".getBytes());

        mvc.perform(multipart("/api/v1/jobs/job-1/applications").file(pdf))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/applications/application-1"))
                .andExpect(jsonPath("$.id").value("application-1"))
                .andExpect(jsonPath("$.resumeUrl").doesNotExist());
    }

    @Test
    void invalidEnumsMethodsAndMediaTypesUseControlledProblemDetails() throws Exception {
        mvc.perform(patch("/api/v1/applications/application-1/status")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"NOT_A_STATUS\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mvc.perform(post("/api/v1/applications/application-1/status"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(post("/api/v1/jobs/job-1/applications").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }
}
