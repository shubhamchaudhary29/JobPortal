package com.example.backend.messaging.infrastructure;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Document(collection = "chat_rooms")
public class ConversationDocument {

    @Id
    private String id;

    @Indexed(unique = true) private String applicationId;
    private String jobId;
    private String jobTitle;

    @Indexed private String candidateId;       // user's MongoDB _id
    private String candidateEmail;    // for display / routing
    private String candidateName;     // for display

    @Indexed private String recruiterId;       // recruiter's MongoDB _id
    private String recruiterEmail;    // for display / routing
    private String recruiterName;     // for display

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime lastMessageAt;
    private String lastMessagePreview;  // first 50 chars of last message

    private boolean active = true;
}
