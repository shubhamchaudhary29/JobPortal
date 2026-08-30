package com.example.backend.candidate.api;

import com.example.backend.candidate.application.CandidateProfileService;
import com.example.backend.shared.error.GlobalExceptionHandler;
import com.example.backend.shared.security.JwtUtil;
import com.example.backend.user.infrastructure.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CandidateProfileController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class CandidateProfileControllerContractTest {
    @Autowired MockMvc mvc;
    @MockitoBean CandidateProfileService profiles;
    @MockitoBean JwtUtil jwt;
    @MockitoBean UserRepository users;

    @Test
    void validatesNestedFieldsUrlsOversizeAndDateShapes() throws Exception {
        mvc.perform(put("/api/v1/candidate-profile").contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":"A","phone":"DROP TABLE users","skills":[{"name":""}],
                 "education":[{"institution":"","startDate":"yesterday"}],
                 "links":{"github":"javascript:alert(1)"}}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.fullName").exists())
                .andExpect(jsonPath("$.fieldErrors.phone").exists())
                .andExpect(jsonPath("$.fieldErrors['links.github']").exists());
    }

    @Test
    void rejectsMassAssignmentAndMalformedJsonWithControlledErrors() throws Exception {
        mvc.perform(put("/api/v1/candidate-profile").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Candidate\",\"userId\":\"candidate-b\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mvc.perform(put("/api/v1/candidate-profile").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }
}
