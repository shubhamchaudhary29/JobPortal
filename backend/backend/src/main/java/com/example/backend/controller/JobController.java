package com.example.backend.controller;

import com.example.backend.dto.CreateJobRequest;
import com.example.backend.entity.Jobs;
import com.example.backend.service.JobService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;

import java.util.List;

@RestController
@RequestMapping("/jobs")
public class JobController {

    @Autowired private JobService jobService;
    @PostMapping("/create")
    public Jobs createJob(@Valid @RequestBody CreateJobRequest request, Authentication authentication) {
        String recruiterEmail = authentication.getName();
        return jobService.createJob(request, recruiterEmail);
    }

    @GetMapping
    public List<Jobs> getAllJobs() {
        return jobService.getAllJobs();
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<Jobs> getJobById(@PathVariable String jobId ){
        Jobs job = jobService.getJobById(jobId);
        return ResponseEntity.ok(job);
    }

    @GetMapping("/myjobs")
    public List<Jobs> getMyJobs(Authentication authentication) {
        return jobService.getMyJobs(authentication.getName());
    }

    @PutMapping("/{jobId}")
    public Jobs updateJob(@PathVariable String jobId, @Valid @RequestBody CreateJobRequest request,
                          Authentication authentication) {
        return jobService.updateJob(jobId, request, authentication.getName());
    }

    @DeleteMapping("/{jobId}")
    public ResponseEntity<Void> deleteJob(@PathVariable String jobId, Authentication authentication) {
        jobService.deleteJob(jobId, authentication.getName());
        return ResponseEntity.noContent().build();
    }
}
