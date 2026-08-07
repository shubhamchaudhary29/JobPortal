package com.example.backend.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Data
@Document(collection = "applications")
@CompoundIndex(name = "candidate_job_unique", def = "{'userId': 1, 'jobId': 1}", unique = true)
public class Application {
    @Id
    private String id;
    @Indexed private String jobId;
    @Indexed private String userId;
    @JsonIgnore private String resumeUrl;
    private LocalDateTime appliedAt;
    private ApplicationStatus status = ApplicationStatus.APPLIED;

    public Application() {
        this.appliedAt = LocalDateTime.now();
    }

}
