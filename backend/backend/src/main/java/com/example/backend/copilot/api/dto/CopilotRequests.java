package com.example.backend.copilot.api.dto;

import com.example.backend.copilot.domain.CopilotModels.PersonalApplicationStage;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class CopilotRequests {
    private CopilotRequests() { }

    public record CreateVersionRequest(@Size(max = 160) String title) { }

    public record UpdateResumeVersionRequest(
            @Size(max = 160) String title,
            @Size(max = 3000) String summary,
            @Size(max = 100) List<@Size(max = 80) String> skillOrder,
            @Size(max = 50) List<@Size(max = 6000) String> experienceDescriptions,
            @Size(max = 50) List<@Size(max = 6000) String> projectDescriptions,
            @Size(max = 10) List<@Size(max = 40) String> sectionOrder) { }

    public record UpdateCoverLetterRequest(
            @Size(max = 160) String title,
            @Size(min = 1, max = 12000) String content) { }

    public record UpdateWorkspaceRequest(
            PersonalApplicationStage stage,
            @Size(max = 5000) String notes,
            Instant followUpAt,
            Boolean appliedExternally) { }
}
