package com.example.backend.copilot.infrastructure;

import com.example.backend.copilot.domain.CopilotModels.JobSnapshot;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "cover_letter_versions")
public class CoverLetterVersionDocument {
    @Id private String id;
    private String userId;
    private String jobId;
    private int versionNumber;
    private JobSnapshot jobSnapshot;
    private String title;
    private String content;
    private Instant baseProfileUpdatedAt;
    private Instant createdAt;
    private Instant updatedAt;
    private String tailoringVersion;
}
