package com.example.backend.controller;

import com.example.backend.repository.ApplicationRepository;
import com.example.backend.security.JwtFilter;
import com.example.backend.security.JwtUtil;
import com.example.backend.service.ApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.Import;
import com.example.backend.config.SecurityConfig;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ApplicationController.class, properties = "app.cors.allowed-origins=http://localhost")
@Import({SecurityConfig.class, JwtFilter.class})
class ApplicationDownloadSecurityTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean ApplicationService applicationService;
    @MockitoBean ApplicationRepository applicationRepository;
    @MockitoBean JwtUtil jwtUtil;

    @Test
    void anonymousResumeDownloadIsDenied() throws Exception {
        mockMvc.perform(get("/applications/download/app1")).andExpect(status().isUnauthorized());
    }
}
