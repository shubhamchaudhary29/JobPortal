package com.example.backend.messaging.security;

import com.example.backend.messaging.application.MessagingService;
import com.example.backend.shared.security.JwtUtil;
import com.example.backend.user.domain.UserRole;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {
    private final JwtUtil jwt;
    private final MessagingService messaging;

    public WebSocketAuthInterceptor(JwtUtil jwt, MessagingService messaging) {
        this.jwt = jwt;
        this.messaging = messaging;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;
        if (StompCommand.CONNECT.equals(accessor.getCommand())) authenticate(accessor);
        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) authorizeSubscription(accessor);
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) throw new IllegalArgumentException("Authentication required");
        try {
            String token = header.substring(7);
            String email = jwt.extractEmail(token);
            String role = jwt.extractRole(token);
            UserRole.valueOf(role);
            accessor.setUser(new UsernamePasswordAuthenticationToken(email, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        } catch (RuntimeException ex) { throw new IllegalArgumentException("Invalid authentication token"); }
    }

    private void authorizeSubscription(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith("/topic/chat/")) return;
        if (accessor.getUser() == null) throw new IllegalArgumentException("Authentication required");
        String conversationId = destination.substring("/topic/chat/".length());
        if (!messaging.isParticipant(conversationId, accessor.getUser().getName()))
            throw new IllegalArgumentException("Conversation not found");
    }
}
