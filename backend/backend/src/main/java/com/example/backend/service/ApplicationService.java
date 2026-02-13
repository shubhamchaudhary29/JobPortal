package com.example.backend.service;

import com.example.backend.entity.Application;
import com.example.backend.entity.Jobs;
import com.example.backend.repository.ApplicationRepository;
import com.example.backend.repository.JobRepository;
import com.example.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.List;

@Service
public class ApplicationService {

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private UserRepository userRepository;

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
                .orElseThrow(() -> new RuntimeException("Job not found"));

        var currentUser = userRepository.findByEmail(currentUserEmail)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!job.getRecruiterId().equals(currentUser.getId())) {
            throw new RuntimeException("Unauthorized: You are not the recruiter.");
        }

        return applicationRepository.findByJobId(jobId);
    }

    public Application getApplicationById(String applicationId) {
        return applicationRepository.findById(applicationId)
                .orElseThrow(() -> new RuntimeException("Application not found with ID: " + applicationId));
    }
}