package com.example.backend.controller;

import com.example.backend.entity.Application;
import com.example.backend.service.ApplicationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.Authentication;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApplicationControllerTest {

    @Mock
    private ApplicationService applicationService;

    @Mock
    private Authentication authentication;

    @InjectMocks
    private ApplicationController applicationController;

    @Test
    void applyForJob_Success() throws IOException { // Added throws IOException
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", MediaType.APPLICATION_PDF_VALUE,
                "content".getBytes());
        Application app = new Application();
        app.setId("1");

        when(authentication.getName()).thenReturn("user1");
        when(applicationService.applyForJob(eq("job1"), eq("user1"), any())).thenReturn(app);

        Application result = applicationController.applyForJob(file, "job1", authentication);

        assertEquals("1", result.getId());
    }
}
