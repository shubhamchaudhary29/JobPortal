package com.example.backend.messaging.api.dto;

import com.example.backend.user.domain.UserRole;
import java.time.LocalDateTime;

public record MessageResponse(String id, String conversationId, String senderEmail, String senderName,
                              UserRole senderRole, String content, LocalDateTime sentAt, boolean read) { }
