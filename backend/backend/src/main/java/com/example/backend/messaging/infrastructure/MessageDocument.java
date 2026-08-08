package com.example.backend.messaging.infrastructure;

import com.example.backend.user.domain.UserRole;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;

import java.time.LocalDateTime;

@Data
@Document(collection = "chat_messages")
@CompoundIndex(name = "room_sent_at_idx", def = "{'chatRoomId': 1, 'sentAt': -1}")
@CompoundIndex(name = "room_unread_idx", def = "{'chatRoomId': 1, 'read': 1, 'senderId': 1}")
public class MessageDocument {

    @Id
    private String id;

    private String chatRoomId;

    private String senderId;      // MongoDB user _id
    private String senderEmail;   // for display
    private String senderName;    // for display
    private UserRole senderRole;

    private String content;

    private LocalDateTime sentAt = LocalDateTime.now();

    private boolean read = false;
}
