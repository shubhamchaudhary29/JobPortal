package com.example.backend.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDateTime;

@Data
@Document(collection = "applications")
public class Application {
    @Id
    private String id;
    private String jobId;
    private String userId;
    private String resumeUrl;
    private LocalDateTime appliedAt;

    public Application() {
        this.appliedAt = LocalDateTime.now();
    }

}