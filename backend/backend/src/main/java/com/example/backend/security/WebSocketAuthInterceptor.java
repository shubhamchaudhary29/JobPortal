package com.example.backend.security;

import com.example.backend.entity.ChatRoom;
import com.example.backend.entity.User;
import com.example.backend.repository.ChatRoomRepository;
import com.example.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
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
import com.example.backend.entity.UserRole;

/**
 * Intercepts every inbound STOMP frame on the WebSocket channel.
 * 1. Enforces JWT authentication on CONNECT frames.
 * 2. Enforces room participant authorization on SUBSCRIBE frames for topic destinations.
 */
@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private UserRepository userRepository;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null) {
            if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                String authHeader = accessor.getFirstNativeHeader("Authorization");
                if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                    throw new IllegalArgumentException("Missing or invalid Authorization header in WebSocket CONNECT");
                }
                String token = authHeader.substring(7);
                try {
                    String email = jwtUtil.extractEmail(token);
                    String role  = jwtUtil.extractRole(token);
                    UserRole.valueOf(role);

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    email,
                                    null,
                                    List.of(new SimpleGrantedAuthority("ROLE_" + role))
                            );
                    accessor.setUser(authentication);
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid authentication token");
                }
            } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                String destination = accessor.getDestination();
                if (destination != null) {
                    java.security.Principal principal = accessor.getUser();
                    if (principal == null) {
                        throw new IllegalArgumentException("Unauthorized subscription: Session not authenticated");
                    }
                    String email = principal.getName();

                    // Restrict chat room broadcast subscriptions: /topic/chat/{roomId}
                    if (destination.startsWith("/topic/chat/")) {
                        String roomId = destination.substring("/topic/chat/".length());

                        ChatRoom room = chatRoomRepository.findById(roomId)
                                .orElseThrow(() -> new IllegalArgumentException("Chat room not found"));

                        User user = userRepository.findByEmail(email)
                                .orElseThrow(() -> new IllegalArgumentException("User not found"));

                        if (!room.getCandidateId().equals(user.getId()) && !room.getRecruiterId().equals(user.getId())) {
                            throw new IllegalArgumentException("Unauthorized subscription to this chat room");
                        }
                    }
                }
            }
        }
        return message;
    }
}
