package com.example.backend.controller;

import com.example.backend.dto.ChatMessageDTO;
import com.example.backend.entity.ChatMessage;
import com.example.backend.entity.ChatRoom;
import com.example.backend.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;

/**
 * Handles STOMP messages arriving at /app/chat.send.
 *
 * After saving the message it broadcasts to:
 *   /topic/chat/{roomId}           — all subscribers in that room (both parties see it)
 *   /user/{otherEmail}/queue/notifications — personal notification to the other party
 */
@Controller
public class ChatWebSocketController {

    @Autowired
    private ChatService chatService;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    @MessageMapping("/chat.send")
    public void sendMessage(@Payload ChatMessageDTO dto, Principal principal) {
        String userEmail = principal.getName();

        // Persist the message via the service (validates auth + content)
        ChatMessage saved = chatService.sendMessage(dto.getChatRoomId(), dto.getContent(), userEmail);

        // Broadcast to everyone subscribed to this room
        messagingTemplate.convertAndSend(
                "/topic/chat/" + dto.getChatRoomId(),
                saved
        );

        // Send a personal notification to the other party
        ChatRoom room = chatService.getChatRoomById(dto.getChatRoomId());
        String otherEmail = userEmail.equals(room.getCandidateEmail())
                ? room.getRecruiterEmail()
                : room.getCandidateEmail();

        messagingTemplate.convertAndSendToUser(
                otherEmail,
                "/queue/notifications",
                saved
        );
    }
}
