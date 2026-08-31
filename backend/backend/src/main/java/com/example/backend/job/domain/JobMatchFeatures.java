package com.example.backend.job.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobMatchFeatures {
    private List<String> normalizedSkills = new ArrayList<>();
    private List<String> requiredSkills = new ArrayList<>();
    private List<String> preferredSkills = new ArrayList<>();
    private Integer minimumExperienceMonths;
    private Integer maximumExperienceMonths;
    private Seniority seniority = Seniority.UNKNOWN;
    private List<String> educationRequirements = new ArrayList<>();
    private WorkMode workMode = WorkMode.UNKNOWN;
    private String employmentType;
    private RoleFamily roleFamily = RoleFamily.UNKNOWN;
    private String featureExtractionVersion;
    private String sourceHash;
    private Instant extractedAt;
}
