package com.example.backend.application.application;

import com.example.backend.application.domain.ApplicationStatus;
import com.example.backend.application.infrastructure.ApplicationDocument;
import com.example.backend.application.infrastructure.ApplicationRepository;
import com.example.backend.job.application.JobService;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.job.infrastructure.JobApplicationReferenceCoordinator;
import com.example.backend.messaging.application.MessagingService;
import com.example.backend.shared.error.ConflictException;
import com.example.backend.shared.error.ResourceNotFoundException;
import com.example.backend.shared.error.ForbiddenException;
import com.example.backend.shared.security.CurrentUserProvider;
import com.example.backend.user.domain.UserRole;
import com.example.backend.user.infrastructure.UserDocument;
import com.example.backend.user.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.data.domain.PageRequest;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApplicationServiceTest {
    private ApplicationRepository applications;
    private JobService jobs;
    private UserRepository users;
    private ResumeStorageService storage;
    private CurrentUserProvider current;
    private ApplicationService service;
    private JobApplicationReferenceCoordinator jobReferences;

    @BeforeEach
    void setUp() {
        applications = mock(ApplicationRepository.class);
        jobs = mock(JobService.class);
        users = mock(UserRepository.class);
        storage = mock(ResumeStorageService.class);
        current = mock(CurrentUserProvider.class);
        jobReferences = mock(JobApplicationReferenceCoordinator.class);
        service = new ApplicationService(applications, jobs, users, mock(MessagingService.class), storage, current,
                jobReferences);
        when(current.email()).thenReturn("candidate@example.test");
        when(jobReferences.acquireApplicationReference(anyString())).thenReturn(true);
    }

    @Test
    void candidateIdentityAndInitialStatusAreServerControlled() throws Exception {
        UserDocument candidate = new UserDocument(); candidate.setEmail("candidate@example.test"); candidate.setRole(UserRole.USER);
        when(users.findByEmail(candidate.getEmail())).thenReturn(Optional.of(candidate));
        when(jobs.requireJob("job1")).thenReturn(new JobDocument());
        when(storage.store(any())).thenReturn("random.pdf");
        when(applications.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var response = service.apply("job1", new MockMultipartFile("file", new byte[]{1}));
        assertEquals("candidate@example.test", response.candidateEmail());
        assertEquals(ApplicationStatus.APPLIED, response.status());
    }

    @Test
    void duplicateApplicationAndInvalidTransitionAreConflicts() throws Exception {
        UserDocument candidate = new UserDocument(); candidate.setEmail("candidate@example.test"); candidate.setRole(UserRole.USER);
        when(users.findByEmail(candidate.getEmail())).thenReturn(Optional.of(candidate));
        when(jobs.requireJob("job1")).thenReturn(new JobDocument());
        when(applications.existsByUserIdAndJobId(candidate.getEmail(), "job1")).thenReturn(true);
        assertThrows(ConflictException.class, () -> service.apply("job1", new MockMultipartFile("file", new byte[]{1})));

        ApplicationDocument application = new ApplicationDocument(); application.setId("a1"); application.setJobId("job1");
        when(applications.findById("a1")).thenReturn(Optional.of(application));
        when(jobs.requireOwnedJob("job1", candidate.getEmail())).thenReturn(new JobDocument());
        assertThrows(ConflictException.class, () -> service.updateStatus("a1", ApplicationStatus.ACCEPTED));
    }

    @Test
    void unrelatedUserGetsPrivacyPreservingNotFound() {
        ApplicationDocument application = new ApplicationDocument(); application.setId("a1");
        application.setJobId("job1"); application.setUserId("owner@example.test");
        when(applications.findById("a1")).thenReturn(Optional.of(application));
        UserDocument unrelated = new UserDocument(); unrelated.setId("u2"); unrelated.setEmail("candidate@example.test");
        when(users.findByEmail("candidate@example.test")).thenReturn(Optional.of(unrelated));
        JobDocument job = new JobDocument(); job.setRecruiterId("r1");
        when(jobs.requireJob("job1")).thenReturn(job);
        assertThrows(ResourceNotFoundException.class, () -> service.getAuthorized("a1"));
    }

    @Test
    void applicantListingChecksJobOwnershipBeforeQueryingApplications() {
        when(jobs.requireOwnedJob("job1", "candidate@example.test"))
                .thenThrow(new ForbiddenException("Not owner"));
        assertThrows(ForbiddenException.class,
                () -> service.applicants("job1", PageRequest.of(0, 20)));
        verify(applications, never()).findByJobId(anyString(), any());
    }

    @Test
    void persistenceFailureCleansUpStoredResume() throws Exception {
        UserDocument candidate = new UserDocument(); candidate.setEmail("candidate@example.test"); candidate.setRole(UserRole.USER);
        when(users.findByEmail(candidate.getEmail())).thenReturn(Optional.of(candidate));
        when(jobs.requireJob("job1")).thenReturn(new JobDocument());
        when(storage.store(any())).thenReturn("11111111-1111-1111-1111-111111111111.pdf");
        when(applications.save(any())).thenThrow(new RuntimeException("database unavailable"));
        var file = new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[]{1});
        assertThrows(RuntimeException.class, () -> service.apply("job1", file));
        verify(storage).deleteQuietly("11111111-1111-1111-1111-111111111111.pdf");
        verify(jobReferences).releaseApplicationReference("job1");
    }

    @Test
    void inactiveOrReconciliationPendingImportRejectsApplicationBeforeResumeStorage() {
        UserDocument candidate = new UserDocument();
        candidate.setEmail("candidate@example.test");
        candidate.setRole(UserRole.USER);
        when(users.findByEmail(candidate.getEmail())).thenReturn(Optional.of(candidate));
        when(jobs.requireJob("inactive")).thenReturn(new JobDocument());
        when(jobReferences.acquireApplicationReference("inactive")).thenReturn(false);

        assertThrows(ConflictException.class,
                () -> service.apply("inactive", new MockMultipartFile("file", new byte[]{1})));
        verifyNoInteractions(storage);

        when(jobs.requireJob("pending")).thenThrow(new ResourceNotFoundException("Job not found"));
        assertThrows(ResourceNotFoundException.class,
                () -> service.apply("pending", new MockMultipartFile("file", new byte[]{1})));
        verify(jobReferences, never()).acquireApplicationReference("pending");
    }
}
