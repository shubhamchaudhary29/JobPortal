package com.example.backend.messaging.api.dto;

import java.time.LocalDateTime;

public record ConversationResponse(String id, String applicationId, String jobId, String jobTitle,
                                   String candidateEmail, String candidateName,
                                   String recruiterEmail, String recruiterName,
                                   LocalDateTime createdAt, LocalDateTime lastMessageAt,
                                   String lastMessagePreview, boolean active) { }
