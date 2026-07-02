package com.example.backend.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payload sent by the client over WebSocket when publishing a chat message.
 * Destination: /app/chat.send
 */
@Data
@NoArgsConstructor
public class ChatMessageDTO {
    private String chatRoomId;
    private String content;
}
