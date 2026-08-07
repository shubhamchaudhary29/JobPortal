package com.example.backend.messaging.api;

import com.example.backend.messaging.api.dto.SendMessageRequest;
import com.example.backend.messaging.application.MessagingService;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller
public class ChatWebSocketController {
    private final MessagingService messaging;
    private final SimpMessagingTemplate broker;

    public ChatWebSocketController(MessagingService messaging, SimpMessagingTemplate broker) {
        this.messaging = messaging;
        this.broker = broker;
    }

    @MessageMapping("/chat.send")
    public void send(@Payload SendMessageRequest request, Principal principal) {
        var saved = messaging.sendAs(request.conversationId(), request.content(), principal.getName());
        broker.convertAndSend("/topic/chat/" + request.conversationId(), saved);
        broker.convertAndSendToUser(messaging.otherParticipantEmail(request.conversationId(), principal.getName()),
                "/queue/notifications", saved);
    }
}
