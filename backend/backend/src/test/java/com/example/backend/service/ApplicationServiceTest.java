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
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApplicationServiceTest {
    @Mock ApplicationRepository applicationRepository;
    @Mock JobRepository jobRepository;
    @Mock UserRepository userRepository;
    @Mock ChatService chatService;
    @TempDir Path uploadDirectory;

    private ApplicationService applicationService;
    private Jobs job;
    private User recruiter;
    private User applicant;
    private Application application;

    @BeforeEach
    void setUp() {
        applicationService = new ApplicationService(uploadDirectory.toString());
        ReflectionTestUtils.setField(applicationService, "applicationRepository", applicationRepository);
        ReflectionTestUtils.setField(applicationService, "jobRepository", jobRepository);
        ReflectionTestUtils.setField(applicationService, "userRepository", userRepository);
        ReflectionTestUtils.setField(applicationService, "chatService", chatService);

        recruiter = user("recruiter1", "recruiter@test.com", "RECRUITER");
        applicant = user("applicant1", "applicant@test.com", "USER");
        job = new Jobs();
        job.setId("job1");
        job.setRecruiterId("recruiter1");
        application = new Application();
        application.setId("app1");
        application.setJobId("job1");
        application.setUserId("applicant@test.com");
    }

    @Test
    void applyForJob_Success() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf", "content".getBytes());
        when(applicationRepository.save(any(Application.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Application result = applicationService.applyForJob("job1", "applicant@test.com", file);
        assertEquals("job1", result.getJobId());
        assertTrue(result.getResumeUrl().matches("[0-9a-f-]+\\.pdf"));
        verify(applicationRepository).save(any(Application.class));
    }

    @Test
    void getApplicationsForJob_Success() {
        when(jobRepository.findById("job1")).thenReturn(Optional.of(job));
        when(userRepository.findByEmail("recruiter@test.com")).thenReturn(Optional.of(recruiter));
        when(applicationRepository.findByJobId("job1")).thenReturn(Collections.singletonList(application));
        List<Application> results = applicationService.getApplicationsForJob("job1", "recruiter@test.com");
        assertEquals(1, results.size());
    }

    @Test
    void getApplicationsForJob_Unauthorized() {
        when(jobRepository.findById("job1")).thenReturn(Optional.of(job));
        when(userRepository.findByEmail("applicant@test.com")).thenReturn(Optional.of(applicant));
        assertThrows(ForbiddenException.class, () -> applicationService.getApplicationsForJob("job1", "applicant@test.com"));
    }

    @Test
    void getApplicationsForJob_JobNotFound() {
        when(jobRepository.findById("job1")).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> applicationService.getApplicationsForJob("job1", "recruiter@test.com"));
    }

    @Test
    void owningCandidateCanDownloadResume() throws IOException {
        createStoredResume("resume.pdf");
        when(applicationRepository.findById("app1")).thenReturn(Optional.of(application));
        assertEquals(uploadDirectory.resolve("resume.pdf"), applicationService.getAuthorizedResume("app1", "applicant@test.com"));
    }

    @Test
    void jobOwningRecruiterCanDownloadResume() throws IOException {
        createStoredResume("resume.pdf");
        when(applicationRepository.findById("app1")).thenReturn(Optional.of(application));
        when(userRepository.findByEmail("recruiter@test.com")).thenReturn(Optional.of(recruiter));
        when(jobRepository.findById("job1")).thenReturn(Optional.of(job));
        assertEquals(uploadDirectory.resolve("resume.pdf"), applicationService.getAuthorizedResume("app1", "recruiter@test.com"));
    }

    @Test
    void unrelatedCandidateIsDenied() {
        assertUnrelatedUserDenied(user("other", "other@test.com", "USER"));
    }

    @Test
    void unrelatedRecruiterIsDenied() {
        assertUnrelatedUserDenied(user("other-recruiter", "other-recruiter@test.com", "RECRUITER"));
    }

    @Test
    void pathTraversalAndArbitraryPathsAreRejected() {
        application.setResumeUrl("../outside.pdf");
        when(applicationRepository.findById("app1")).thenReturn(Optional.of(application));
        assertThrows(ResourceNotFoundException.class, () -> applicationService.getAuthorizedResume("app1", "applicant@test.com"));
    }

    @Test
    void missingResumeReturnsNotFound() {
        application.setResumeUrl("missing.pdf");
        when(applicationRepository.findById("app1")).thenReturn(Optional.of(application));
        assertThrows(ResourceNotFoundException.class, () -> applicationService.getAuthorizedResume("app1", "applicant@test.com"));
    }

    private void assertUnrelatedUserDenied(User unrelated) {
        when(applicationRepository.findById("app1")).thenReturn(Optional.of(application));
        when(userRepository.findByEmail(unrelated.getEmail())).thenReturn(Optional.of(unrelated));
        when(jobRepository.findById("job1")).thenReturn(Optional.of(job));
        assertThrows(ForbiddenException.class, () -> applicationService.getAuthorizedResume("app1", unrelated.getEmail()));
    }

    private void createStoredResume(String name) throws IOException {
        Files.writeString(uploadDirectory.resolve(name), "pdf");
        application.setResumeUrl(name);
    }

    private User user(String id, String email, String role) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setRole(role);
        return user;
    }
}
