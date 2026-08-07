package com.example.backend.service;

import com.example.backend.entity.Application;
import com.example.backend.entity.Jobs;
import com.example.backend.entity.User;
import com.example.backend.entity.UserRole;
import com.example.backend.entity.ApplicationStatus;
import com.example.backend.dto.ApplicationWithJobDTO;
import com.example.backend.exception.ForbiddenException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.exception.BadRequestException;
import com.example.backend.repository.ApplicationRepository;
import com.example.backend.repository.JobRepository;
import com.example.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.LinkOption;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);

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

    private final Path uploadDirectory;
    private final long maxResumeBytes;

    public ApplicationService(@Value("${app.upload-dir:uploads}") String uploadDirectory,
                              @Value("${app.resume.max-bytes:5242880}") long maxResumeBytes) {
        this.uploadDirectory = Paths.get(uploadDirectory).toAbsolutePath().normalize();
        this.maxResumeBytes = maxResumeBytes;
    }

    public Application applyForJob(String jobId, String userId, MultipartFile file) throws IOException {
        validatePdf(file);
        User candidate = userRepository.findByEmail(userId).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (candidate.getRole() != UserRole.USER) throw new ForbiddenException("Only candidates can apply");
        jobRepository.findById(jobId).orElseThrow(() -> new ResourceNotFoundException("Job not found"));
        if (applicationRepository.existsByUserIdAndJobId(userId, jobId)) throw new com.example.backend.exception.ConflictException("Application already exists");
        Files.createDirectories(uploadDirectory);
        String storageName = UUID.randomUUID() + ".pdf";
        Path destination = uploadDirectory.resolve(storageName).normalize();
        if (!destination.startsWith(uploadDirectory)) throw new BadRequestException("Invalid upload destination");
        Files.copy(file.getInputStream(), destination);

        Application app = new Application();
        app.setJobId(jobId);
        app.setUserId(userId);
        app.setResumeUrl(storageName);

        try {
            return applicationRepository.save(app);
        } catch (RuntimeException ex) {
            Files.deleteIfExists(destination);
            throw ex;
        }
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
                            app.getStatus() != null ? app.getStatus().name() : ApplicationStatus.APPLIED.name(),
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

    public Application updateApplicationStatus(String applicationId, ApplicationStatus newStatus, String recruiterEmail) {
        Application app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found with ID: " + applicationId));

        Jobs job = jobRepository.findById(app.getJobId())
                .orElseThrow(() -> new ResourceNotFoundException("Job not found with ID: " + app.getJobId()));

        User recruiter = userRepository.findByEmail(recruiterEmail)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with email: " + recruiterEmail));

        if (!job.getRecruiterId().equals(recruiter.getId())) {
            throw new ForbiddenException("Unauthorized: You are not the recruiter of this job.");
        }

        if (newStatus == ApplicationStatus.WITHDRAWN || !app.getStatus().canTransitionTo(newStatus))
            throw new com.example.backend.exception.ConflictException("Invalid application status transition");

        app.setStatus(newStatus);
        Application saved = applicationRepository.save(app);

        // Auto-create chat room when a candidate is accepted (idempotent)
        if (newStatus == ApplicationStatus.ACCEPTED) {
            try {
                chatService.createChatRoom(applicationId);
            } catch (Exception e) {
                // Log but don't fail the status update if chat room creation fails
                log.warn("Chat room creation failed after an application status update: {}",
                        e.getClass().getSimpleName());
            }
        }

        return saved;
    }

    public Application getApplicationForDownload(String applicationId, String email) {
        Application app = getApplicationById(applicationId);

        // Candidate check: Candidate can download their own resume
        if (app.getUserId().equals(email)) {
            return app;
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        // Recruiter check: Recruiter of the job can download the candidate's resume
        Jobs job = jobRepository.findById(app.getJobId())
                .orElseThrow(() -> new ResourceNotFoundException("Job not found: " + app.getJobId()));

        if (job.getRecruiterId().equals(user.getId())) {
            return app;
        }

        throw new ForbiddenException("Unauthorized: You do not have access to this candidate's resume.");
    }

    public Path getAuthorizedResume(String applicationId, String email) {
        Application application = getApplicationForDownload(applicationId, email);
        String storedName = application.getResumeUrl();
        if (storedName == null || storedName.isBlank()) {
            throw new ResourceNotFoundException("Resume not found");
        }

        Path fileName = Paths.get(storedName).getFileName();
        if (fileName == null) {
            throw new ResourceNotFoundException("Resume not found");
        }

        Path resume = uploadDirectory.resolve(fileName).normalize();
        if (!resume.startsWith(uploadDirectory) || Files.isSymbolicLink(resume)
                || !Files.isRegularFile(resume, LinkOption.NOFOLLOW_LINKS)) {
            throw new ResourceNotFoundException("Resume not found");
        }
        return resume;
    }

    public Application getAuthorizedApplication(String applicationId, String email) {
        return getApplicationForDownload(applicationId, email);
    }

    public Application withdraw(String applicationId, String email) {
        Application app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Application not found"));
        if (!app.getUserId().equals(email)) throw new ResourceNotFoundException("Application not found");
        if (!app.getStatus().canTransitionTo(ApplicationStatus.WITHDRAWN))
            throw new com.example.backend.exception.ConflictException("Invalid application status transition");
        app.setStatus(ApplicationStatus.WITHDRAWN);
        return applicationRepository.save(app);
    }

    private void validatePdf(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) throw new BadRequestException("Resume must not be empty");
        if (file.getSize() > maxResumeBytes) throw new BadRequestException("Resume exceeds maximum size");
        if (!MediaType.APPLICATION_PDF_VALUE.equalsIgnoreCase(file.getContentType()))
            throw new BadRequestException("Resume must be a PDF");
        byte[] bytes = file.getBytes();
        byte[] header = "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (bytes.length < 10 || !Arrays.equals(Arrays.copyOf(bytes, header.length), header))
            throw new BadRequestException("Resume content is not a valid PDF");
        String tail = new String(bytes, Math.max(0, bytes.length - 1024), Math.min(1024, bytes.length),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        if (!tail.contains("%%EOF")) throw new BadRequestException("Resume content is not a complete PDF");
    }
}
