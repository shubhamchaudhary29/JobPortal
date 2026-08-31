package com.example.backend.matching.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "matching")
public class MatchingProperties {
    public static final String SCORING_VERSION = "job-match-1.2.0";
    public static final String FEATURE_VERSION = "job-features-1.2.0";

    @Min(0) @Max(100) private int skillsWeight = 40;
    @Min(0) @Max(100) private int experienceWeight = 20;
    @Min(0) @Max(100) private int titleWeight = 15;
    @Min(0) @Max(100) private int educationWeight = 10;
    @Min(0) @Max(100) private int locationWeight = 10;
    @Min(0) @Max(100) private int employmentTypeWeight = 5;
    @Min(50) @Max(2000) private int candidateWindow = 500;

    @AssertTrue(message = "matching weights must sum to 100")
    public boolean isWeightTotalValid() {
        return skillsWeight + experienceWeight + titleWeight + educationWeight
                + locationWeight + employmentTypeWeight == 100;
    }

    public int getSkillsWeight() { return skillsWeight; }
    public void setSkillsWeight(int value) { skillsWeight = value; }
    public int getExperienceWeight() { return experienceWeight; }
    public void setExperienceWeight(int value) { experienceWeight = value; }
    public int getTitleWeight() { return titleWeight; }
    public void setTitleWeight(int value) { titleWeight = value; }
    public int getEducationWeight() { return educationWeight; }
    public void setEducationWeight(int value) { educationWeight = value; }
    public int getLocationWeight() { return locationWeight; }
    public void setLocationWeight(int value) { locationWeight = value; }
    public int getEmploymentTypeWeight() { return employmentTypeWeight; }
    public void setEmploymentTypeWeight(int value) { employmentTypeWeight = value; }
    public int getCandidateWindow() { return candidateWindow; }
    public void setCandidateWindow(int value) { candidateWindow = value; }
}
