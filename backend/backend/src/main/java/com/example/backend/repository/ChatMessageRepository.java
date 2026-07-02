package com.example.backend.repository;

import com.example.backend.entity.ChatMessage;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ChatMessageRepository extends MongoRepository<ChatMessage, String> {

    List<ChatMessage> findByChatRoomIdOrderBySentAtAsc(String chatRoomId);

    int countByChatRoomIdAndReadFalseAndSenderIdNot(String chatRoomId, String senderId);

    List<ChatMessage> findByChatRoomIdAndReadFalseAndSenderIdNot(String chatRoomId, String senderId);
}
