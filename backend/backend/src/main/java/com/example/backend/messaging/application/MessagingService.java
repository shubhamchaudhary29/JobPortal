package com.example.backend.messaging.application;

import com.example.backend.messaging.MessagingMapper;
import com.example.backend.messaging.api.dto.ConversationResponse;
import com.example.backend.messaging.api.dto.MessageResponse;
import com.example.backend.messaging.infrastructure.ConversationDocument;
import com.example.backend.messaging.infrastructure.ConversationRepository;
import com.example.backend.messaging.infrastructure.MessageDocument;
import com.example.backend.messaging.infrastructure.MessageRepository;
import com.example.backend.messaging.infrastructure.MessageBulkRepository;
import com.example.backend.shared.error.BadRequestException;
import com.example.backend.shared.error.ResourceNotFoundException;
import com.example.backend.shared.pagination.PageResponse;
import com.example.backend.shared.security.CurrentUserProvider;
import com.example.backend.user.infrastructure.UserDocument;
import com.example.backend.user.infrastructure.UserRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

@Service
public class MessagingService {
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final UserRepository users;
    private final CurrentUserProvider currentUser;
    private final MessageBulkRepository bulkMessages;

    @Autowired
    public MessagingService(ConversationRepository conversations, MessageRepository messages, UserRepository users,
                            CurrentUserProvider currentUser, MessageBulkRepository bulkMessages) {
        this.conversations = conversations;
        this.messages = messages;
        this.users = users;
        this.currentUser = currentUser;
        this.bulkMessages = bulkMessages;
    }
    MessagingService(ConversationRepository conversations, MessageRepository messages, UserRepository users,
                     CurrentUserProvider currentUser) {
        this(conversations, messages, users, currentUser, (roomId, userId) -> 0);
    }

    public ConversationDocument createConversation(CreateConversationCommand command) {
        return conversations.findByApplicationId(command.applicationId()).orElseGet(() -> {
            ConversationDocument document = new ConversationDocument();
            document.setApplicationId(command.applicationId());
            document.setJobId(command.jobId());
            document.setJobTitle(command.jobTitle());
            document.setCandidateId(command.candidateId());
            document.setCandidateEmail(command.candidateEmail());
            document.setCandidateName(command.candidateName());
            document.setRecruiterId(command.recruiterId());
            document.setRecruiterEmail(command.recruiterEmail());
            document.setRecruiterName(command.recruiterName());
            return conversations.save(document);
        });
    }

    public PageResponse<ConversationResponse> mine(Pageable pageable) {
        UserDocument user = requireUser(currentUser.email());
        var page = user.getRole().name().equals("RECRUITER")
                ? conversations.findByRecruiterId(user.getId(), pageable)
                : conversations.findByCandidateId(user.getId(), pageable);
        return PageResponse.from(page.map(MessagingMapper::toResponse));
    }

    public ConversationResponse get(String id) {
        return MessagingMapper.toResponse(participant(id, requireUser(currentUser.email())));
    }

    public PageResponse<MessageResponse> messages(String id, Pageable pageable) {
        UserDocument user = requireUser(currentUser.email());
        participant(id, user);
        bulkMessages.markUnreadFromOtherSenderRead(id, user.getId());
        return PageResponse.from(messages.findByChatRoomId(id, pageable).map(MessagingMapper::toResponse));
    }

    public MessageResponse sendAs(String conversationId, String content, String email) {
        UserDocument user = requireUser(email);
        ConversationDocument conversation = participant(conversationId, user);
        if (content == null || content.isBlank()) throw new BadRequestException("Message content must not be empty");
        if (content.length() > 2000) throw new BadRequestException("Message content must not exceed 2000 characters");
        MessageDocument message = new MessageDocument();
        message.setChatRoomId(conversationId);
        message.setSenderId(user.getId());
        message.setSenderEmail(user.getEmail());
        message.setSenderName(user.getFullName());
        message.setSenderRole(user.getRole());
        message.setContent(content.trim());
        MessageDocument saved = messages.save(message);
        conversation.setLastMessageAt(LocalDateTime.now());
        conversation.setLastMessagePreview(saved.getContent().substring(0, Math.min(50, saved.getContent().length())));
        conversations.save(conversation);
        return MessagingMapper.toResponse(saved);
    }

    public String otherParticipantEmail(String conversationId, String email) {
        ConversationDocument conversation = participant(conversationId, requireUser(email));
        return email.equals(conversation.getCandidateEmail())
                ? conversation.getRecruiterEmail() : conversation.getCandidateEmail();
    }

    public int unreadCount() {
        UserDocument user = requireUser(currentUser.email());
        // Count is intentionally bounded; the inbox itself is paginated and avoids a fetch-all query.
        var rooms = user.getRole().name().equals("RECRUITER")
                ? conversations.findByRecruiterId(user.getId(), org.springframework.data.domain.PageRequest.of(0, 100))
                : conversations.findByCandidateId(user.getId(), org.springframework.data.domain.PageRequest.of(0, 100));
        return rooms.stream().mapToInt(room -> messages.countByChatRoomIdAndReadFalseAndSenderIdNot(room.getId(), user.getId())).sum();
    }

    public boolean isParticipant(String conversationId, String email) {
        UserDocument user = requireUser(email);
        ConversationDocument room = conversations.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));
        return room.getCandidateId().equals(user.getId()) || room.getRecruiterId().equals(user.getId());
    }

    private ConversationDocument participant(String id, UserDocument user) {
        ConversationDocument room = conversations.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Conversation not found"));
        if (!room.getCandidateId().equals(user.getId()) && !room.getRecruiterId().equals(user.getId()))
            throw new ResourceNotFoundException("Conversation not found");
        return room;
    }

    private UserDocument requireUser(String email) {
        return users.findByEmail(email).orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
