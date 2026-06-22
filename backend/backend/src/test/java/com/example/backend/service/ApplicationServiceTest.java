package com.example.backend.service;

import com.example.backend.entity.Application;
import com.example.backend.entity.Jobs;
import com.example.backend.entity.User;
import com.example.backend.exception.ForbiddenException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.repository.ApplicationRepository;
import com.example.backend.repository.JobRepository;
import com.example.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApplicationServiceTest {

    @Mock
    private ApplicationRepository applicationRepository;

    @Mock
    private JobRepository jobRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ApplicationService applicationService;

    private Jobs job;
    private User recruiter;
    private User applicant;
    private Application application;

    @BeforeEach
    void setUp() {
        recruiter = new User();
        recruiter.setId("recruiter1");
        recruiter.setEmail("recruiter@test.com");
        recruiter.setRole("RECRUITER");

        applicant = new User();
        applicant.setId("applicant1");
        applicant.setEmail("applicant@test.com");
        applicant.setRole("USER");

        job = new Jobs();
        job.setId("job1");
        job.setRecruiterId("recruiter1");

        application = new Application();
        application.setId("app1");
        application.setJobId("job1");
        application.setUserId("applicant1");
    }

    @Test
    void applyForJob_Success() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf", "content".getBytes());
        when(applicationRepository.save(any(Application.class))).thenReturn(application);

        Application result = applicationService.applyForJob("job1", "applicant1", file);

        assertNotNull(result);
        assertEquals("job1", result.getJobId());
        verify(applicationRepository, times(1)).save(any(Application.class));
    }

    @Test
    void getApplicationsForJob_Success() {
        when(jobRepository.findById("job1")).thenReturn(Optional.of(job));
        when(userRepository.findByEmail("recruiter@test.com")).thenReturn(Optional.of(recruiter));
        when(applicationRepository.findByJobId("job1")).thenReturn(Collections.singletonList(application));

        List<Application> results = applicationService.getApplicationsForJob("job1", "recruiter@test.com");

        assertFalse(results.isEmpty());
        assertEquals(1, results.size());
    }

    @Test
    void getApplicationsForJob_Unauthorized() {
        when(jobRepository.findById("job1")).thenReturn(Optional.of(job));
        when(userRepository.findByEmail("applicant@test.com")).thenReturn(Optional.of(applicant));

        assertThrows(ForbiddenException.class, () -> 
            applicationService.getApplicationsForJob("job1", "applicant@test.com"));
    }

    @Test
    void getApplicationsForJob_JobNotFound() {
        when(jobRepository.findById("job1")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> 
            applicationService.getApplicationsForJob("job1", "recruiter@test.com"));
    }
}
