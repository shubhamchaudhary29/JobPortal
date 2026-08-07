package com.example.backend.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Document(collection = "jobs")
public class Jobs {

    @Id
    private String id;

    private String title;
    private String description;
    private String location;
    private String company;
    private double salary;
    private double experience;

    @Indexed private String recruiterId;

    private LocalDateTime createdAt = LocalDateTime.now();

    private String sourceUrl;      // original posting URL; null for manually-created jobs
    private String source;         // "manual" or "adzuna"
    private String externalId;     // Adzuna's job id, used to avoid duplicate imports

}
