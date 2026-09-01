package com.example.backend.copilot.infrastructure;

import com.example.backend.copilot.domain.CopilotModels.JobSnapshot;
import com.example.backend.copilot.domain.CopilotModels.KeywordAnalysis;
import com.example.backend.copilot.domain.CopilotModels.ResumeContent;
import com.example.backend.copilot.domain.CopilotModels.TailoringAction;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Data
@Document(collection = "tailored_resume_versions")
public class TailoredResumeVersionDocument {
    @Id private String id;
    private String userId;
    private String jobId;
    private int versionNumber;
    private JobSnapshot jobSnapshot;
    private String title;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant baseProfileUpdatedAt;
    private String matchingVersion;
    private String tailoringVersion;
    private ResumeContent content;
    private List<TailoringAction> tailoringActions = new ArrayList<>();
    private KeywordAnalysis keywordAnalysis;
}
