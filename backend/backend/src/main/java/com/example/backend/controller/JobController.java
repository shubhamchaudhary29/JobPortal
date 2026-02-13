package com.example.backend.controller;

import com.example.backend.dto.CreateJobRequest;
import com.example.backend.entity.Jobs;
import com.example.backend.entity.User;
import com.example.backend.repository.JobRepository;
import com.example.backend.repository.UserRepository;
import com.example.backend.service.JobService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/jobs")
public class JobController {

    @Autowired private JobService jobService;
    @Autowired private JobRepository jobRepository;
    @Autowired private UserRepository userRepository;

    @PostMapping("/create")
    public Jobs createJob(@RequestBody CreateJobRequest request, Authentication authentication) {
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
        String email = authentication.getName();
        User user = userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));
        return jobRepository.findByRecruiterId(user.getId());
    }
}