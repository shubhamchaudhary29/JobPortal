package com.example.backend.messaging.infrastructure;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface MessageRepository extends MongoRepository<MessageDocument, String> {

    Page<MessageDocument> findByChatRoomId(String chatRoomId, Pageable pageable);

    int countByChatRoomIdAndReadFalseAndSenderIdNot(String chatRoomId, String senderId);

    List<MessageDocument> findByChatRoomIdAndReadFalseAndSenderIdNot(String chatRoomId, String senderId);
}
