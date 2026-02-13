package com.example.backend.entity;

import lombok.Data;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data @Document(collection = "jobs")
public class Jobs {


    @Id
    private String id;

    private String title;
    private String description;
    private String location;
    private String company;
    private double salary;
    private double experience;

    private String recruiterId;

    private LocalDateTime createdAt = LocalDateTime.now();

}

