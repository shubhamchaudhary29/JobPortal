package com.example.backend.application.infrastructure;

import com.example.backend.application.domain.ApplicationStatus;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Data
@Document(collection = "applications")
@CompoundIndex(name = "candidate_job_unique", def = "{'userId': 1, 'jobId': 1}", unique = true)
@CompoundIndex(name = "job_applied_at_idx", def = "{'jobId': 1, 'appliedAt': -1}")
@CompoundIndex(name = "candidate_applied_at_idx", def = "{'userId': 1, 'appliedAt': -1}")
public class ApplicationDocument {
    @Id
    private String id;
    private String jobId;
    private String userId;
    @JsonIgnore private String resumeUrl;
    private LocalDateTime appliedAt;
    private ApplicationStatus status = ApplicationStatus.APPLIED;

    public ApplicationDocument() {
        this.appliedAt = LocalDateTime.now();
    }

}
