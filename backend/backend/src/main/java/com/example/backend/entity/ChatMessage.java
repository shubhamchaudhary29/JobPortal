package com.example.backend.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Document(collection = "chat_messages")
public class ChatMessage {

    @Id
    private String id;

    private String chatRoomId;

    private String senderId;      // MongoDB user _id
    private String senderEmail;   // for display
    private String senderName;    // for display
    private String senderRole;    // "USER" or "RECRUITER"

    private String content;

    private LocalDateTime sentAt = LocalDateTime.now();

    private boolean read = false;
}
