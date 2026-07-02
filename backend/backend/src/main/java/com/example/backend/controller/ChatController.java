package com.example.backend.controller;

import com.example.backend.entity.ChatMessage;
import com.example.backend.entity.ChatRoom;
import com.example.backend.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST endpoints for the chat feature.
 * All endpoints require authentication (covered by SecurityConfig's anyRequest().authenticated()).
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    @Autowired
    private ChatService chatService;

    /**
     * GET /chat/rooms
     * Returns all chat rooms for the currently authenticated user, newest activity first.
     */
    @GetMapping("/rooms")
    public ResponseEntity<List<ChatRoom>> getMyChatRooms(Authentication authentication) {
        String email = authentication.getName();
        return ResponseEntity.ok(chatService.getMyChatRooms(email));
    }

    /**
     * GET /chat/rooms/{roomId}
     * Returns a single chat room's metadata.
     */
    @GetMapping("/rooms/{roomId}")
    public ResponseEntity<ChatRoom> getChatRoom(
            @PathVariable String roomId,
            Authentication authentication) {
        String email = authentication.getName();
        ChatRoom room = chatService.getChatRoom(roomId, email);
        return ResponseEntity.ok(room);
    }

    /**
     * GET /chat/rooms/{roomId}/messages
     * Returns all messages in the room ordered by sentAt ASC.
     * Also marks unread messages (from the other party) as read.
     */
    @GetMapping("/rooms/{roomId}/messages")
    public ResponseEntity<List<ChatMessage>> getMessages(
            @PathVariable String roomId,
            Authentication authentication) {
        String email = authentication.getName();
        return ResponseEntity.ok(chatService.getChatMessages(roomId, email));
    }

    /**
     * GET /chat/unread
     * Returns the total number of unread messages across all chat rooms for this user.
     */
    @GetMapping("/unread")
    public ResponseEntity<Integer> getUnreadCount(Authentication authentication) {
        String email = authentication.getName();
        return ResponseEntity.ok(chatService.getUnreadCount(email));
    }
}
