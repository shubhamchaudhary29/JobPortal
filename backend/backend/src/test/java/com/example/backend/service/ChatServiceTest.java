package com.example.backend.service;

import com.example.backend.entity.ChatMessage;
import com.example.backend.entity.ChatRoom;
import com.example.backend.entity.User;
import com.example.backend.entity.UserRole;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.repository.ApplicationRepository;
import com.example.backend.repository.ChatMessageRepository;
import com.example.backend.repository.ChatRoomRepository;
import com.example.backend.repository.JobRepository;
import com.example.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceTest {
    private final ChatRoomRepository rooms = mock(ChatRoomRepository.class);
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private ChatService service;
    private User candidate;
    private ChatRoom room;

    @BeforeEach
    void setUp() {
        service = new ChatService();
        ReflectionTestUtils.setField(service, "chatRoomRepository", rooms);
        ReflectionTestUtils.setField(service, "chatMessageRepository", messages);
        ReflectionTestUtils.setField(service, "applicationRepository", mock(ApplicationRepository.class));
        ReflectionTestUtils.setField(service, "jobRepository", mock(JobRepository.class));
        ReflectionTestUtils.setField(service, "userRepository", users);

        candidate = new User();
        candidate.setId("candidate-id");
        candidate.setEmail("candidate@example.test");
        candidate.setFullName("Candidate");
        candidate.setRole(UserRole.USER);
        room = new ChatRoom();
        room.setId("room-id");
        room.setCandidateId(candidate.getId());
        room.setRecruiterId("recruiter-id");
    }

    @Test
    void participantCanReadAndSendMessages() {
        when(users.findByEmail(candidate.getEmail())).thenReturn(Optional.of(candidate));
        when(rooms.findById(room.getId())).thenReturn(Optional.of(room));
        when(messages.findByChatRoomIdAndReadFalseAndSenderIdNot(room.getId(), candidate.getId())).thenReturn(List.of());
        when(messages.findByChatRoomIdOrderBySentAtAsc(room.getId())).thenReturn(List.of());
        when(messages.save(any(ChatMessage.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals(List.of(), service.getChatMessages(room.getId(), candidate.getEmail()));
        ChatMessage saved = service.sendMessage(room.getId(), " hello ", candidate.getEmail());
        assertEquals("hello", saved.getContent());
        verify(rooms).save(room);
    }

    @Test
    void nonParticipantCannotReadOrSendMessages() {
        User unrelated = new User();
        unrelated.setId("unrelated-id");
        unrelated.setEmail("unrelated@example.test");
        when(users.findByEmail(unrelated.getEmail())).thenReturn(Optional.of(unrelated));
        when(rooms.findById(room.getId())).thenReturn(Optional.of(room));

        assertThrows(ResourceNotFoundException.class,
                () -> service.getChatMessages(room.getId(), unrelated.getEmail()));
        assertThrows(ResourceNotFoundException.class,
                () -> service.sendMessage(room.getId(), "blocked", unrelated.getEmail()));
    }
}
