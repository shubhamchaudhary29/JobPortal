package com.example.backend.service;

import com.example.backend.dto.CreateJobRequest;
import com.example.backend.entity.Jobs;
import com.example.backend.entity.User;
import com.example.backend.repository.JobRepository;
import com.example.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class JobService {

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private UserRepository userRepository;

    public Jobs createJob(CreateJobRequest request, String recruiterEmail) {
        User recruiter = userRepository.findByEmail(recruiterEmail)
                .orElseThrow(() -> new RuntimeException("Recruiter not found"));

        if (!"RECRUITER".equals(recruiter.getRole())) {
            throw new RuntimeException("Only recruiters can create jobs");
        }

        Jobs job = new Jobs();
        job.setTitle(request.getTitle());
        job.setDescription(request.getDescription());
        job.setLocation(request.getLocation());
        job.setCompany(request.getCompany());
        job.setSalary(request.getSalary());
        job.setExperience(request.getExperience()); // Now saving experience
        job.setRecruiterId(recruiter.getId());

        return jobRepository.save(job);
    }

    public List<Jobs> getAllJobs() {
        return jobRepository.findAll();
    }

    public Jobs getJobById(String jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new RuntimeException("Job not found with id: " + jobId));
    }

    public List<Jobs> getMyJobs(String recruiterEmail) {
        User recruiter = userRepository.findByEmail(recruiterEmail)
                .orElseThrow(() -> new RuntimeException("Recruiter not found"));
        return jobRepository.findByRecruiterId(recruiter.getId());
    }
}