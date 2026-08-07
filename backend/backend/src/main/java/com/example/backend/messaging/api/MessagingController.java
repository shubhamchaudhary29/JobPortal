package com.example.backend.messaging.api;

import com.example.backend.messaging.api.dto.ConversationResponse;
import com.example.backend.messaging.api.dto.MessageResponse;
import com.example.backend.messaging.api.dto.UnreadCountResponse;
import com.example.backend.messaging.application.MessagingService;
import com.example.backend.shared.pagination.PageRequestFactory;
import com.example.backend.shared.pagination.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/conversations")
@Tag(name = "Messaging", description = "Conversation participants only")
@SecurityRequirement(name = "bearerAuth")
public class MessagingController {
    private final MessagingService messaging;

    public MessagingController(MessagingService messaging) { this.messaging = messaging; }

    @GetMapping
    @Operation(summary = "List the authenticated user's conversations")
    public PageResponse<ConversationResponse> mine(@RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size,
                                                   @RequestParam(defaultValue = "lastMessageAt,desc") String sort) {
        Pageable pageable = PageRequestFactory.create(page, size, sort,
                Set.of("lastMessageAt", "createdAt", "jobTitle"), "lastMessageAt");
        return messaging.mine(pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get conversation metadata")
    public ConversationResponse get(@PathVariable String id) { return messaging.get(id); }

    @GetMapping("/{id}/messages")
    @Operation(summary = "List conversation messages")
    public PageResponse<MessageResponse> messages(@PathVariable String id,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int size,
                                                  @RequestParam(defaultValue = "sentAt,asc") String sort) {
        Pageable pageable = PageRequestFactory.create(page, size, sort, Set.of("sentAt"), "sentAt");
        return messaging.messages(id, pageable);
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Get total unread message count")
    public UnreadCountResponse unread() { return new UnreadCountResponse(messaging.unreadCount()); }
}
