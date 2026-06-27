package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ApplicationWithJobDTO {
    private String applicationId;
    private String status;
    private LocalDateTime appliedAt;
    private String jobId;
    private String jobTitle;
    private String jobCompany;
    private String jobLocation;
    private double jobSalary;
    private String sourceUrl; // for external jobs
}
