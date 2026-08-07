package com.example.backend.service;

import com.example.backend.dto.CreateJobRequest;
import com.example.backend.entity.Jobs;
import com.example.backend.entity.User;
import com.example.backend.entity.UserRole;
import com.example.backend.exception.ForbiddenException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.repository.JobRepository;
import com.example.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobServiceTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private JobService jobService;

    private User recruiter;
    private User regularUser;
    private Jobs job;
    private CreateJobRequest createJobRequest;

    @BeforeEach
    void setUp() {
        recruiter = new User();
        recruiter.setId("recruiter1");
        recruiter.setEmail("recruiter@test.com");
        recruiter.setRole(UserRole.RECRUITER);

        regularUser = new User();
        regularUser.setId("user1");
        regularUser.setEmail("user@test.com");
        regularUser.setRole(UserRole.USER);

        job = new Jobs();
        job.setId("job1");
        job.setTitle("Test Job");
        job.setRecruiterId("recruiter1");

        createJobRequest = new CreateJobRequest("New Job", "Description", "Location", "Company", 1000.0, 2.0);
    }

    @Test
    void createJob_Success() {
        when(userRepository.findByEmail("recruiter@test.com")).thenReturn(Optional.of(recruiter));
        when(jobRepository.save(any(Jobs.class))).thenReturn(job);

        Jobs result = jobService.createJob(createJobRequest, "recruiter@test.com");

        assertNotNull(result);
        verify(jobRepository, times(1)).save(any(Jobs.class));
    }

    @Test
    void createJob_NotRecruiter() {
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(regularUser));

        assertThrows(ForbiddenException.class, () -> 
            jobService.createJob(createJobRequest, "user@test.com"));
    }

    @Test
    void getJobById_Success() {
        when(jobRepository.findById("job1")).thenReturn(Optional.of(job));

        Jobs result = jobService.getJobById("job1");

        assertNotNull(result);
        assertEquals("job1", result.getId());
    }

    @Test
    void getJobById_NotFound() {
        when(jobRepository.findById("job1")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> 
            jobService.getJobById("job1"));
    }
}
