package com.example.backend.job.application;

import com.example.backend.job.api.dto.CreateJobRequest;
import com.example.backend.job.api.dto.UpdateJobRequest;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.job.infrastructure.JobRepository;
import com.example.backend.job.infrastructure.JobSearchRepository;
import com.example.backend.shared.error.ForbiddenException;
import com.example.backend.shared.error.BadRequestException;
import com.example.backend.shared.error.ResourceNotFoundException;
import com.example.backend.shared.security.CurrentUserProvider;
import com.example.backend.user.domain.UserRole;
import com.example.backend.user.infrastructure.UserDocument;
import com.example.backend.user.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JobServiceTest {
    private JobRepository jobs;
    private UserRepository users;
    private JobSearchRepository search;
    private CurrentUserProvider current;
    private JobService service;

    @BeforeEach
    void setUp() {
        jobs = mock(JobRepository.class);
        users = mock(UserRepository.class);
        current = mock(CurrentUserProvider.class);
        search = mock(JobSearchRepository.class);
        service = new JobService(jobs, search, users, current);
        when(current.email()).thenReturn("recruiter@example.test");
    }

    @Test
    void recruiterCreatesJobWithoutClientControlledOwnership() {
        UserDocument recruiter = user("r1", UserRole.RECRUITER);
        when(users.findByEmail("recruiter@example.test")).thenReturn(Optional.of(recruiter));
        when(jobs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var response = service.create(new CreateJobRequest("Title", "Description", "Pune", "Company", 10, 1));
        assertEquals("Title", response.title());
        var saved = org.mockito.ArgumentCaptor.forClass(JobDocument.class);
        verify(jobs).save(saved.capture());
        assertEquals("r1", saved.getValue().getRecruiterId());
    }

    @Test
    void candidateCannotCreateAndOtherRecruiterCannotUpdate() {
        when(users.findByEmail("recruiter@example.test")).thenReturn(Optional.of(user("candidate", UserRole.USER)));
        assertThrows(ForbiddenException.class, () -> service.create(
                new CreateJobRequest("Title", "Description", "Pune", "Company", 10, 1)));

        when(users.findByEmail("recruiter@example.test")).thenReturn(Optional.of(user("r1", UserRole.RECRUITER)));
        JobDocument foreign = new JobDocument(); foreign.setId("job1"); foreign.setRecruiterId("r2");
        when(jobs.findById("job1")).thenReturn(Optional.of(foreign));
        assertThrows(ForbiddenException.class, () -> service.update("job1",
                new UpdateJobRequest("Title", "Description", "Pune", "Company", 10, 1)));
    }

    @Test
    void longFiltersAreRejectedBeforeDatabaseAccess() {
        assertThrows(BadRequestException.class, () -> service.search("x".repeat(101), null, null,
                PageRequest.of(0, 20)));
        verifyNoInteractions(search);
    }

    @Test
    void missingJobUsesResourceNotFound() {
        when(jobs.findById("missing")).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.get("missing"));
    }

    private UserDocument user(String id, UserRole role) {
        UserDocument user = new UserDocument(); user.setId(id); user.setRole(role); return user;
    }
}
