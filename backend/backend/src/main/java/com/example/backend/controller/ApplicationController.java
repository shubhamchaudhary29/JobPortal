package com.example.backend.controller;

import com.example.backend.dto.ApplicationWithJobDTO;
import com.example.backend.dto.UpdateStatusRequest;
import com.example.backend.entity.Application;
import com.example.backend.exception.ForbiddenException;
import com.example.backend.repository.ApplicationRepository;
import com.example.backend.service.ApplicationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/applications")
public class ApplicationController {

    @Autowired
    private ApplicationService applicationService;

    @Autowired
    private ApplicationRepository applicationRepository;

    @PostMapping("/apply")
    public Application applyForJob(
            @RequestParam("file") MultipartFile file,
            @RequestParam("jobId") String jobId,
            Authentication authentication) throws IOException {

        String userId = authentication.getName();

        return applicationService.applyForJob(jobId, userId, file);
    }

    @GetMapping("/{jobId}")
    public List<Application> getApplicationsForJob(
            @PathVariable String jobId,
            Authentication authentication) {

        String currentUserId = authentication.getName();

        return applicationService.getApplicationsForJob(jobId, currentUserId);
    }

    @GetMapping("/download/{applicationId}")
    public ResponseEntity<Resource> downloadResume(
            @PathVariable String applicationId,
            Authentication authentication) throws IOException {

        String email = authentication.getName();
        Path path = applicationService.getAuthorizedResume(applicationId, email);
        Resource resource = new InputStreamResource(java.nio.file.Files.newInputStream(path));

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"resume.pdf\"")
                .header("X-Content-Type-Options", "nosniff")
                .contentLength(java.nio.file.Files.size(path))
                .body(resource);
    }

    @GetMapping("/status/{jobId}")
    public ResponseEntity<Boolean> hasUserApplied(
            @PathVariable String jobId,
            Authentication authentication) {

        String currentUserId = authentication.getName(); // Get logged-in email/id
        boolean hasApplied = applicationRepository.existsByUserIdAndJobId(currentUserId, jobId);

        return ResponseEntity.ok(hasApplied);
    }

    @GetMapping("/my")
    public ResponseEntity<List<ApplicationWithJobDTO>> getMyApplications(Authentication authentication) {
        String email = authentication.getName();
        List<ApplicationWithJobDTO> myApps = applicationService.getMyApplications(email);
        return ResponseEntity.ok(myApps);
    }

    @PatchMapping("/{applicationId}/status")
    public ResponseEntity<Application> updateApplicationStatus(
            @PathVariable String applicationId,
            @Valid @RequestBody UpdateStatusRequest request,
            Authentication authentication) {

        boolean isRecruiter = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_RECRUITER"));

        if (!isRecruiter) {
            throw new ForbiddenException("Unauthorized: Only recruiters can update application status.");
        }

        Application updated = applicationService.updateApplicationStatus(
                applicationId,
                request.status(),
                authentication.getName()
        );
        return ResponseEntity.ok(updated);
    }

    @GetMapping("/item/{applicationId}")
    public Application getApplication(@PathVariable String applicationId, Authentication authentication) {
        return applicationService.getAuthorizedApplication(applicationId, authentication.getName());
    }

    @PostMapping("/{applicationId}/withdraw")
    public Application withdraw(@PathVariable String applicationId, Authentication authentication) {
        return applicationService.withdraw(applicationId, authentication.getName());
    }
}
