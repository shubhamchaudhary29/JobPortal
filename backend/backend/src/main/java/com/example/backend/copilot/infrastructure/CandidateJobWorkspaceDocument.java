package com.example.backend.copilot.infrastructure;

import com.example.backend.application.domain.ApplicationStatus;
import com.example.backend.copilot.domain.CopilotModels.JobSnapshot;
import com.example.backend.copilot.domain.CopilotModels.KeywordAnalysis;
import com.example.backend.copilot.domain.CopilotModels.PersonalApplicationStage;
import com.example.backend.copilot.domain.CopilotModels.ReadinessResult;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "candidate_job_workspaces")
public class CandidateJobWorkspaceDocument {
    @Id private String id;
    private String userId;
    private String jobId;
    private JobSnapshot jobSnapshot;
    private Double matchScore;
    private String matchingVersion;
    private ReadinessResult readiness;
    private KeywordAnalysis keywordAnalysis;
    private String resumeVersionId;
    private String coverLetterVersionId;
    private PersonalApplicationStage stage = PersonalApplicationStage.SAVED;
    private String notes;
    private Instant followUpAt;
    private Instant appliedAt;
    private boolean appliedExternally;
    private Instant createdAt;
    private Instant updatedAt;
    /** Response-time enrichment only; never accepted from candidate payloads. */
    private transient ApplicationStatus recruiterStatus;
}
