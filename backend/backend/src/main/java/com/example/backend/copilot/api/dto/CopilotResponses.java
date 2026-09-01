package com.example.backend.copilot.api.dto;

import com.example.backend.application.domain.ApplicationStatus;
import com.example.backend.copilot.domain.CopilotModels.JobSnapshot;
import com.example.backend.copilot.domain.CopilotModels.KeywordAnalysis;
import com.example.backend.copilot.domain.CopilotModels.PersonalApplicationStage;
import com.example.backend.copilot.domain.CopilotModels.ReadinessResult;
import com.example.backend.copilot.domain.CopilotModels.ResumeContent;
import com.example.backend.copilot.domain.CopilotModels.ResumeStaleness;
import com.example.backend.copilot.domain.CopilotModels.TailoringAction;
import com.example.backend.copilot.domain.CopilotModels.TailoringPlan;
import com.example.backend.matching.domain.MatchLevel;

import java.time.Instant;
import java.util.List;

public final class CopilotResponses {
    private CopilotResponses() { }

    public record ReadinessResponse(JobSnapshot job, double matchScore, MatchLevel matchLevel,
                                    ReadinessResult readiness, KeywordAnalysis keywordAnalysis) { }

    public record TailoringPlanResponse(JobSnapshot job, TailoringPlan plan) { }

    public record ResumeVersionResponse(
            String id, String jobId, int versionNumber, JobSnapshot job, String title,
            Instant createdAt, Instant updatedAt, Instant baseProfileUpdatedAt,
            String matchingVersion, String tailoringVersion, ResumeContent content,
            List<TailoringAction> tailoringActions, KeywordAnalysis keywordAnalysis,
            ResumeStaleness staleness, String stalenessMessage, boolean active) { }

    public record CoverLetterResponse(
            String id, String jobId, int versionNumber, JobSnapshot job, String title, String content,
            Instant createdAt, Instant updatedAt, Instant baseProfileUpdatedAt, String tailoringVersion,
            ResumeStaleness staleness, String stalenessMessage, boolean active) { }

    public record WorkspaceResponse(
            String jobId, JobSnapshot job, boolean active, Double matchScore, String matchingVersion,
            ReadinessResult readiness, KeywordAnalysis keywordAnalysis, String resumeVersionId,
            String coverLetterVersionId, PersonalApplicationStage stage, ApplicationStatus recruiterStatus,
            boolean appliedExternally, Instant appliedAt, String notes, Instant followUpAt,
            String followUpStatus, Instant createdAt, Instant updatedAt) { }

    public record WorkspaceAnalyticsResponse(
            long saved, long preparing, long applied, long onlineAssessments, long interviews,
            long offers, long rejected, long withdrawn, Double responseRate,
            Double interviewRate, Double offerRate, String message) { }
}
