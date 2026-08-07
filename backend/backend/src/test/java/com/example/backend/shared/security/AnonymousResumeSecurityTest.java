package com.example.backend.shared.security;

import com.example.backend.BackendApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = BackendApplication.class)
@AutoConfigureMockMvc
class AnonymousResumeSecurityTest {
    private final MockMvc mvc;

    @Autowired
    AnonymousResumeSecurityTest(MockMvc mvc) { this.mvc = mvc; }

    @Test
    void anonymousResumeAccessReturnsProblemDetails401() throws Exception {
        mvc.perform(get("/api/v1/applications/private/resume"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
