package com.example.backend.messaging.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload sent by the client over WebSocket when publishing a chat message.
 * Destination: /app/chat.send
 */
public record SendMessageRequest(@NotBlank String conversationId,
                                 @NotBlank @Size(max = 2000) String content) { }
