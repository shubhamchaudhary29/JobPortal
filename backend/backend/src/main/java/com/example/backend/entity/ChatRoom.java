package com.example.backend.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Document(collection = "chat_rooms")
public class ChatRoom {

    @Id
    private String id;

    private String applicationId;
    private String jobId;
    private String jobTitle;

    private String candidateId;       // user's MongoDB _id
    private String candidateEmail;    // for display / routing
    private String candidateName;     // for display

    private String recruiterId;       // recruiter's MongoDB _id
    private String recruiterEmail;    // for display / routing
    private String recruiterName;     // for display

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime lastMessageAt;
    private String lastMessagePreview;  // first 50 chars of last message

    private boolean active = true;
}
