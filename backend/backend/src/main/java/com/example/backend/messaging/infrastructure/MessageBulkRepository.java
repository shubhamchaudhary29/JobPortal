package com.example.backend.messaging.infrastructure;

public interface MessageBulkRepository {
    long markUnreadFromOtherSenderRead(String chatRoomId, String recipientId);
}
