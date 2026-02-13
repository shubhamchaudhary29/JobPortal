package com.example.backend.controller;

import com.example.backend.entity.Application;
import com.example.backend.repository.ApplicationRepository;
import com.example.backend.repository.JobRepository;
import com.example.backend.service.ApplicationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    public ResponseEntity<Resource> downloadResume(@PathVariable String applicationId) throws IOException {

        Application application = applicationService.getApplicationById(applicationId);

        Path path = Paths.get(application.getResumeUrl());
        Resource resource = new UrlResource(path.toUri());

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.getFilename() + "\"")
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
}