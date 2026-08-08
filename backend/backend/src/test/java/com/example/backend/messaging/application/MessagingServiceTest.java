package com.example.backend.messaging.application;

import com.example.backend.messaging.infrastructure.ConversationDocument;
import com.example.backend.messaging.infrastructure.ConversationRepository;
import com.example.backend.messaging.infrastructure.MessageDocument;
import com.example.backend.messaging.infrastructure.MessageRepository;
import com.example.backend.messaging.infrastructure.MessageBulkRepository;
import com.example.backend.shared.error.ResourceNotFoundException;
import com.example.backend.shared.security.CurrentUserProvider;
import com.example.backend.user.domain.UserRole;
import com.example.backend.user.infrastructure.UserDocument;
import com.example.backend.user.infrastructure.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MessagingServiceTest {
    private ConversationRepository conversations;
    private MessageRepository messages;
    private UserRepository users;
    private CurrentUserProvider currentUser;
    private MessageBulkRepository bulkMessages;
    private MessagingService service;
    private ConversationDocument room;

    @BeforeEach
    void setUp() {
        conversations = mock(ConversationRepository.class);
        messages = mock(MessageRepository.class);
        users = mock(UserRepository.class);
        currentUser = mock(CurrentUserProvider.class);
        bulkMessages = mock(MessageBulkRepository.class);
        service = new MessagingService(conversations, messages, users, currentUser, bulkMessages);
        room = new ConversationDocument(); room.setId("room1"); room.setCandidateId("candidate");
        room.setRecruiterId("recruiter"); room.setCandidateEmail("candidate@example.test");
        room.setRecruiterEmail("recruiter@example.test");
    }

    @Test
    void participantCanSendAndNonParticipantCannotSendOrSubscribe() {
        UserDocument candidate = user("candidate", "candidate@example.test");
        when(users.findByEmail(candidate.getEmail())).thenReturn(Optional.of(candidate));
        when(conversations.findById("room1")).thenReturn(Optional.of(room));
        when(messages.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var sent = service.sendAs("room1", " hello ", candidate.getEmail());
        assertEquals("hello", sent.content());
        assertTrue(service.isParticipant("room1", candidate.getEmail()));

        UserDocument unrelated = user("other", "other@example.test");
        when(users.findByEmail(unrelated.getEmail())).thenReturn(Optional.of(unrelated));
        assertThrows(ResourceNotFoundException.class,
                () -> service.sendAs("room1", "blocked", unrelated.getEmail()));
        assertFalse(service.isParticipant("room1", unrelated.getEmail()));
    }

    @Test
    void participantCanReadPaginatedMessagesAndUnreadMessagesAreMarkedRead() {
        UserDocument candidate = user("candidate", "candidate@example.test");
        when(currentUser.email()).thenReturn(candidate.getEmail());
        when(users.findByEmail(candidate.getEmail())).thenReturn(Optional.of(candidate));
        when(conversations.findById("room1")).thenReturn(Optional.of(room));
        var pageable = PageRequest.of(0, 20);
        when(messages.findByChatRoomId("room1", pageable)).thenReturn(new PageImpl<>(java.util.List.of(), pageable, 0));

        assertTrue(service.messages("room1", pageable).content().isEmpty());
        verify(bulkMessages).markUnreadFromOtherSenderRead("room1", "candidate");
    }

    @Test
    void nonParticipantCannotReadMessages() {
        UserDocument unrelated = user("other", "other@example.test");
        when(currentUser.email()).thenReturn(unrelated.getEmail());
        when(users.findByEmail(unrelated.getEmail())).thenReturn(Optional.of(unrelated));
        when(conversations.findById("room1")).thenReturn(Optional.of(room));
        assertThrows(ResourceNotFoundException.class,
                () -> service.messages("room1", PageRequest.of(0, 20)));
        verify(messages, never()).findByChatRoomId(anyString(), any());
    }

    private UserDocument user(String id, String email) {
        UserDocument user = new UserDocument(); user.setId(id); user.setEmail(email); user.setRole(UserRole.USER); return user;
    }
}
