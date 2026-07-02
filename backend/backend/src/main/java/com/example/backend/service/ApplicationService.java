package com.example.backend.service;

import com.example.backend.entity.Application;
import com.example.backend.entity.Jobs;
import com.example.backend.entity.User;
import com.example.backend.dto.ApplicationWithJobDTO;
import com.example.backend.exception.ForbiddenException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.exception.BadRequestException;
import com.example.backend.repository.ApplicationRepository;
import com.example.backend.repository.JobRepository;
import com.example.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class ApplicationService {

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private UserRepository userRepository;

    // @Lazy prevents circular dependency: ApplicationService → ChatService → ApplicationRepository → ApplicationService
    @Lazy
    @Autowired
    private ChatService chatService;

    private final String UPLOAD_DIR = System.getProperty("user.dir") + "/uploads/";

    public Application applyForJob(String jobId, String userId, MultipartFile file) throws IOException {

        File directory = new File(UPLOAD_DIR);
        if (!directory.exists()) {
            directory.mkdirs();
        }

        String fileName = System.currentTimeMillis() + "_" + file.getOriginalFilename();
        File dest = new File(UPLOAD_DIR + fileName);
        file.transferTo(dest);

        Application app = new Application();
        app.setJobId(jobId);
        app.setUserId(userId);
        app.setResumeUrl(dest.getAbsolutePath());

        return applicationRepository.save(app);
    }

    public List<Application> getApplicationsForJob(String jobId, String currentUserEmail) {

        Jobs job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found"));

        User currentUser = userRepository.findByEmail(currentUserEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (!job.getRecruiterId().equals(currentUser.getId())) {
            throw new ForbiddenException("Unauthorized: You are not the recruiter.");
        }

        return applicationRepository.findByJobId(jobId);
    }

    public Application getApplicationById(String applicationId) {
        return applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found with ID: " + applicationId));
    }

    public List<ApplicationWithJobDTO> getMyApplications(String userEmail) {
        // Query applications directly by email, which is stored in the userId field of Application
        List<Application> apps = applicationRepository.findByUserId(userEmail);

        return apps.stream()
                .map(app -> {
                    Jobs job = jobRepository.findById(app.getJobId()).orElse(null);
                    if (job == null) {
                        return null; // Gracefully skip deleted jobs
                    }
                    return new ApplicationWithJobDTO(
                            app.getId(),
                            app.getStatus() != null ? app.getStatus() : "APPLIED",
                            app.getAppliedAt(),
                            job.getId(),
                            job.getTitle(),
                            job.getCompany(),
                            job.getLocation(),
                            job.getSalary(),
                            job.getSourceUrl()
                    );
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public Application updateApplicationStatus(String applicationId, String newStatus, String recruiterEmail) {
        Application app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found with ID: " + applicationId));

        Jobs job = jobRepository.findById(app.getJobId())
                .orElseThrow(() -> new ResourceNotFoundException("Job not found with ID: " + app.getJobId()));

        User recruiter = userRepository.findByEmail(recruiterEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + recruiterEmail));

        if (!job.getRecruiterId().equals(recruiter.getId())) {
            throw new ForbiddenException("Unauthorized: You are not the recruiter of this job.");
        }

        // Validate status
        List<String> validStatuses = List.of("APPLIED", "UNDER_REVIEW", "SHORTLISTED", "ACCEPTED", "REJECTED");
        if (!validStatuses.contains(newStatus)) {
            throw new BadRequestException("Invalid status: " + newStatus);
        }

        app.setStatus(newStatus);
        Application saved = applicationRepository.save(app);

        // Auto-create chat room when a candidate is accepted (idempotent)
        if ("ACCEPTED".equals(newStatus)) {
            try {
                chatService.createChatRoom(applicationId);
            } catch (Exception e) {
                // Log but don't fail the status update if chat room creation fails
                System.err.println("[ChatService] Failed to create chat room for application "
                        + applicationId + ": " + e.getMessage());
            }
        }

        return saved;
    }

    public Application getApplicationForDownload(String applicationId, String email) {
        Application app = getApplicationById(applicationId);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + email));

        // Candidate check: Candidate can download their own resume
        if (app.getUserId().equals(email)) {
            return app;
        }

        // Recruiter check: Recruiter of the job can download the candidate's resume
        Jobs job = jobRepository.findById(app.getJobId())
                .orElseThrow(() -> new ResourceNotFoundException("Job not found: " + app.getJobId()));

        if (job.getRecruiterId().equals(user.getId())) {
            return app;
        }

        throw new ForbiddenException("Unauthorized: You do not have access to this candidate's resume.");
    }
}