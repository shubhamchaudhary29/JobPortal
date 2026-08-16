package com.example.backend.integration.adzuna.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.backend.integration.adzuna.AdzunaService;
import java.util.function.BooleanSupplier;
import com.example.backend.shared.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AdzunaIngestionControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwt;
    @MockitoBean AdzunaService ingestion;

    @Test
    void unauthenticatedRequestIsRejectedByTheSecurityFilterChain() throws Exception {
        mvc.perform(post("/api/v1/jobs/ingestion/adzuna")).andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedUserIsForbiddenByTheRealSecurityChain() throws Exception {
        mvc.perform(post("/api/v1/jobs/ingestion/adzuna").header(HttpHeaders.AUTHORIZATION, bearer("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void recruiterJwtTriggersManualSyncAndReturnsItsHttpResponse() throws Exception {
        when(ingestion.sync(any(BooleanSupplier.class))).thenReturn(new AdzunaService.SyncResult(2, 1, 3, 4, 0, 0,
                AdzunaService.Outcome.FULL_SUCCESS));

        mvc.perform(post("/api/v1/jobs/ingestion/adzuna").header(HttpHeaders.AUTHORIZATION, bearer("RECRUITER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.inserted").value(2))
                .andExpect(jsonPath("$.updated").value(1)).andExpect(jsonPath("$.unchanged").value(3))
                .andExpect(jsonPath("$.outcome").value("FULL_SUCCESS"));

        verify(ingestion).sync(any(BooleanSupplier.class));
    }

    private String bearer(String role) { return "Bearer " + jwt.generateToken("actor@example.com", role); }
}
