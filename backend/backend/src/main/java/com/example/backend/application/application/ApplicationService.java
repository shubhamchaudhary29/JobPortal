package com.example.backend.application.application;

import com.example.backend.application.ApplicationMapper;
import com.example.backend.application.api.dto.ApplicationResponse;
import com.example.backend.application.api.dto.ApplicationSummaryResponse;
import com.example.backend.application.domain.ApplicationStatus;
import com.example.backend.application.infrastructure.ApplicationDocument;
import com.example.backend.application.infrastructure.ApplicationRepository;
import com.example.backend.job.application.JobService;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.job.infrastructure.JobApplicationReferenceCoordinator;
import com.example.backend.messaging.application.CreateConversationCommand;
import com.example.backend.messaging.application.MessagingService;
import com.example.backend.shared.error.ConflictException;
import com.example.backend.shared.error.ForbiddenException;
import com.example.backend.shared.error.ResourceNotFoundException;
import com.example.backend.shared.pagination.PageResponse;
import com.example.backend.shared.security.CurrentUserProvider;
import com.example.backend.user.application.ApplicationStats;
import com.example.backend.user.application.ApplicationStatsProvider;
import com.example.backend.user.domain.UserRole;
import com.example.backend.user.infrastructure.UserDocument;
import com.example.backend.user.infrastructure.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.file.Files;

@Service
public class ApplicationService implements ApplicationStatsProvider {
    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);
    private final ApplicationRepository applications;
    private final JobService jobs;
    private final UserRepository users;
    private final MessagingService messaging;
    private final ResumeStorageService storage;
    private final CurrentUserProvider currentUser;
    private final JobApplicationReferenceCoordinator jobReferences;

    public ApplicationService(ApplicationRepository applications, JobService jobs, UserRepository users,
                              MessagingService messaging, ResumeStorageService storage,
                              CurrentUserProvider currentUser, JobApplicationReferenceCoordinator jobReferences) {
        this.applications = applications;
        this.jobs = jobs;
        this.users = users;
        this.messaging = messaging;
        this.storage = storage;
        this.currentUser = currentUser;
        this.jobReferences = jobReferences;
    }

    public ApplicationResponse apply(String jobId, MultipartFile file) throws IOException {
        String email = currentUser.email();
        UserDocument candidate = requireUser(email);
        if (candidate.getRole() != UserRole.USER) throw new ForbiddenException("Only candidates can apply");
        jobs.requireJob(jobId);
        if (applications.existsByUserIdAndJobId(email, jobId)) throw new ConflictException("Application already exists");
        if (!jobReferences.acquireApplicationReference(jobId)) {
            throw new ConflictException("Job is not accepting applications");
        }
        String storedName = null;
        try {
            storedName = storage.store(file);
            ApplicationDocument document = new ApplicationDocument();
            document.setJobId(jobId);
            document.setUserId(email);
            document.setResumeUrl(storedName);
            return ApplicationMapper.toResponse(applications.save(document));
        } catch (IOException | RuntimeException failure) {
            if (storedName != null) storage.deleteQuietly(storedName);
            jobReferences.releaseApplicationReference(jobId);
            throw failure;
        }
    }

    public PageResponse<ApplicationResponse> applicants(String jobId, Pageable pageable) {
        jobs.requireOwnedJob(jobId, currentUser.email());
        return PageResponse.from(applications.findByJobId(jobId, pageable).map(ApplicationMapper::toResponse));
    }

    public boolean hasApplied(String jobId) {
        return applications.existsByUserIdAndJobId(currentUser.email(), jobId);
    }

    public PageResponse<ApplicationSummaryResponse> mine(Pageable pageable) {
        return PageResponse.from(applications.findByUserId(currentUser.email(), pageable)
                .map(application -> ApplicationMapper.toSummary(application, jobs.requireJob(application.getJobId()))));
    }

    public ApplicationResponse getAuthorized(String id) {
        return ApplicationMapper.toResponse(requireAuthorized(id, currentUser.email()));
    }

    public ApplicationResponse updateStatus(String id, ApplicationStatus target) {
        ApplicationDocument application = require(id);
        JobDocument job = jobs.requireOwnedJob(application.getJobId(), currentUser.email());
        if (target == ApplicationStatus.WITHDRAWN || !status(application).canTransitionTo(target))
            throw new ConflictException("Invalid application status transition");
        application.setStatus(target);
        ApplicationDocument saved = applications.save(application);
        if (target == ApplicationStatus.ACCEPTED) createConversation(saved, job);
        return ApplicationMapper.toResponse(saved);
    }

    public ApplicationResponse withdraw(String id) {
        ApplicationDocument application = require(id);
        if (!application.getUserId().equals(currentUser.email())) throw new ResourceNotFoundException("Application not found");
        if (!status(application).canTransitionTo(ApplicationStatus.WITHDRAWN))
            throw new ConflictException("Invalid application status transition");
        application.setStatus(ApplicationStatus.WITHDRAWN);
        return ApplicationMapper.toResponse(applications.save(application));
    }

    public ResumeDownload authorizedResume(String id) throws IOException {
        var path = storage.resolve(requireAuthorized(id, currentUser.email()).getResumeUrl());
        return new ResumeDownload(new InputStreamResource(Files.newInputStream(path)), Files.size(path));
    }

    @Override
    public ApplicationStats forCandidate(String email) {
        int total = applications.countByUserId(email);
        int accepted = applications.countByUserIdAndStatus(email, ApplicationStatus.ACCEPTED);
        int rejected = applications.countByUserIdAndStatus(email, ApplicationStatus.REJECTED);
        return new ApplicationStats(total, accepted, rejected, total - accepted - rejected);
    }

    private ApplicationDocument requireAuthorized(String id, String email) {
        ApplicationDocument application = require(id);
        if (application.getUserId().equals(email)) return application;
        UserDocument user = requireUser(email);
        JobDocument job = jobs.requireJob(application.getJobId());
        if (user.getId().equals(job.getRecruiterId())) return application;
        throw new ResourceNotFoundException("Application not found");
    }

    private ApplicationDocument require(String id) {
        return applications.findById(id).orElseThrow(() -> new ResourceNotFoundException("Application not found"));
    }

    private UserDocument requireUser(String email) {
        return users.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private ApplicationStatus status(ApplicationDocument application) {
        return application.getStatus() == null ? ApplicationStatus.APPLIED : application.getStatus();
    }

    private void createConversation(ApplicationDocument application, JobDocument job) {
        try {
            UserDocument candidate = requireUser(application.getUserId());
            UserDocument recruiter = users.findById(job.getRecruiterId())
                    .orElseThrow(() -> new ResourceNotFoundException("Recruiter not found"));
            messaging.createConversation(new CreateConversationCommand(application.getId(), job.getId(), job.getTitle(),
                    candidate.getId(), candidate.getEmail(), candidate.getFullName(), recruiter.getId(),
                    recruiter.getEmail(), recruiter.getFullName()));
        } catch (RuntimeException ex) {
            log.warn("Conversation creation failed after status update: {}", ex.getClass().getSimpleName());
        }
    }

    public record ResumeDownload(Resource resource, long contentLength) { }
}
