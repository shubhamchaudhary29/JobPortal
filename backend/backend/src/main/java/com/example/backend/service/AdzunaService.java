package com.example.backend.service;

import com.example.backend.entity.Jobs;
import com.example.backend.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class AdzunaService {

    @Value("${adzuna.app.id}")
    private String appId;

    @Value("${adzuna.app.key}")
    private String appKey;

    private final RestTemplate restTemplate = new RestTemplate();

    @Autowired
    private JobRepository jobRepository;

    // List of keywords to fetch jobs for
    private static final List<String> KEYWORDS = List.of(
            "java developer", "python developer", "react developer",
            "data analyst", "backend engineer", "frontend developer",
            "full stack developer", "machine learning engineer"
    );

    @Scheduled(fixedRate = 6 * 60 * 60 * 1000) // every 6 hours
    public void fetchAndSaveJobs() {
        System.out.println("########## ADZUNA FETCH STARTED ##########");
        System.out.println("app_id loaded as: " + appId);
        System.out.println("app_key loaded as: " + (appKey != null ? appKey.substring(0, Math.min(4, appKey.length())) + "..." : "NULL"));

        for (String keyword : KEYWORDS) {
            try {
                fetchJobsByKeyword(keyword);
                Thread.sleep(1000); // avoid rate limiting
            } catch (Exception e) {
                System.out.println("########## ADZUNA ERROR for '" + keyword + "' ##########");
                e.printStackTrace();
            }
        }
        System.out.println("########## ADZUNA FETCH FINISHED ##########");
    }

    @SuppressWarnings("unchecked")
    private void fetchJobsByKeyword(String keyword) {
        String url = String.format(
            "https://api.adzuna.com/v1/api/jobs/in/search/1" +
                    "?app_id=%s&app_key=%s&results_per_page=20&what=%s&content-type=application/json",
            appId, appKey, keyword.replace(" ", "+")
        );

        System.out.println(">>> Calling URL: " + url.replace(appKey, "HIDDEN"));

        ResponseEntity<Map> response = restTemplate.getForEntity(java.net.URI.create(url), Map.class);
        System.out.println(">>> HTTP status: " + response.getStatusCode());

        Map body = response.getBody();
        System.out.println(">>> Raw body: " + body);

        if (body == null) return;

        List<Map> results = (List<Map>) body.get("results");
        System.out.println(">>> Results count: " + (results != null ? results.size() : "null"));

        if (results == null) return;

        for (Map result : results) {
            String externalId = String.valueOf(result.get("id"));

            // Skip if already saved
            if (jobRepository.existsByExternalId(externalId)) continue;

            Jobs job = new Jobs();
            job.setTitle((String) result.get("title"));
            job.setDescription((String) result.get("description"));
            job.setSourceUrl((String) result.get("redirect_url")); // Apply Now URL

            Map company = (Map) result.get("company");
            job.setCompany(company != null ? (String) company.get("display_name") : "Unknown");

            Map location = (Map) result.get("location");
            job.setLocation(location != null ? (String) location.get("display_name") : "India");

            // Jobs.java uses primitive double for salary, default to 0 when absent
            Object salaryMin = result.get("salary_min");
            job.setSalary(salaryMin instanceof Number ? ((Number) salaryMin).doubleValue() : 0.0);
            job.setExperience(0.0); // Adzuna doesn't provide this field

            job.setSource("adzuna");
            job.setExternalId(externalId);
            job.setRecruiterId(null); // no recruiter owns externally sourced jobs
            job.setCreatedAt(LocalDateTime.now());

            jobRepository.save(job);
        }
    }
}