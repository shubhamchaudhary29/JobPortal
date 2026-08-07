package com.example.backend.service;

import com.example.backend.dto.CreateJobRequest;
import com.example.backend.entity.Jobs;
import com.example.backend.entity.User;
import com.example.backend.entity.UserRole;
import com.example.backend.exception.ForbiddenException;
import com.example.backend.exception.ResourceNotFoundException;
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
                .orElseThrow(() -> new ResourceNotFoundException("Recruiter not found"));

        if (recruiter.getRole() != UserRole.RECRUITER) {
            throw new ForbiddenException("Only recruiters can create jobs");
        }

        Jobs job = new Jobs();
        apply(request, job);
        job.setRecruiterId(recruiter.getId());
        job.setSource("manual");

        return jobRepository.save(job);
    }

    public List<Jobs> getAllJobs() {
        return jobRepository.findAll();
    }

    public Jobs getJobById(String jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found with id: " + jobId));
    }

    public List<Jobs> getMyJobs(String recruiterEmail) {
        User recruiter = userRepository.findByEmail(recruiterEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Recruiter not found"));
        return jobRepository.findByRecruiterId(recruiter.getId());
    }

    public Jobs updateJob(String jobId, CreateJobRequest request, String recruiterEmail) {
        Jobs job = ownedJob(jobId, recruiterEmail);
        apply(request, job);
        return jobRepository.save(job);
    }

    public void deleteJob(String jobId, String recruiterEmail) {
        jobRepository.delete(ownedJob(jobId, recruiterEmail));
    }

    private Jobs ownedJob(String jobId, String email) {
        User recruiter = userRepository.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Jobs job = jobRepository.findById(jobId).orElseThrow(() -> new ResourceNotFoundException("Job not found"));
        if (recruiter.getRole() != UserRole.RECRUITER || !recruiter.getId().equals(job.getRecruiterId()))
            throw new ForbiddenException("Not authorized to manage this job");
        return job;
    }

    private void apply(CreateJobRequest request, Jobs job) {
        job.setTitle(request.title().trim());
        job.setDescription(request.description().trim());
        job.setLocation(request.location().trim());
        job.setCompany(request.company().trim());
        job.setSalary(request.salary());
        job.setExperience(request.experience());
    }
}
