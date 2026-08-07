package com.example.backend.shared.configuration;

import com.example.backend.BackendApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = BackendApplication.class)
@AutoConfigureMockMvc
class OpenApiSmokeTest {
    private final MockMvc mvc;

    @Autowired
    OpenApiSmokeTest(MockMvc mvc) { this.mvc = mvc; }

    @Test
    void openApiPublishesVersionedDtoContractsAndSecuritySchemes() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.info.version").value("v1"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth").exists())
                .andExpect(jsonPath("$.components.securitySchemes.refreshCookie").exists())
                .andExpect(jsonPath("$.paths['/api/v1/jobs']").exists())
                .andExpect(content().string(containsString("JobResponse")))
                .andExpect(content().string(not(containsString("RefreshTokenDocument"))))
                .andExpect(content().string(not(containsString("resumeUrl"))));
    }
}
