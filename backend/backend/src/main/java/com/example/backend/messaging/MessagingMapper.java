package com.example.backend.messaging;

import com.example.backend.messaging.api.dto.ConversationResponse;
import com.example.backend.messaging.api.dto.MessageResponse;
import com.example.backend.messaging.infrastructure.ConversationDocument;
import com.example.backend.messaging.infrastructure.MessageDocument;

public final class MessagingMapper {
    private MessagingMapper() { }

    public static ConversationResponse toResponse(ConversationDocument document) {
        return new ConversationResponse(document.getId(), document.getApplicationId(), document.getJobId(),
                document.getJobTitle(), document.getCandidateEmail(), document.getCandidateName(),
                document.getRecruiterEmail(), document.getRecruiterName(), document.getCreatedAt(),
                document.getLastMessageAt(), document.getLastMessagePreview(), document.isActive());
    }

    public static MessageResponse toResponse(MessageDocument document) {
        return new MessageResponse(document.getId(), document.getChatRoomId(), document.getSenderEmail(),
                document.getSenderName(), document.getSenderRole(), document.getContent(), document.getSentAt(),
                document.isRead());
    }
}
