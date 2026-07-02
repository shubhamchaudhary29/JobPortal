package com.example.backend.repository;

import com.example.backend.entity.ChatRoom;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ChatRoomRepository extends MongoRepository<ChatRoom, String> {

    List<ChatRoom> findByCandidateIdOrderByLastMessageAtDesc(String candidateId);

    List<ChatRoom> findByRecruiterIdOrderByLastMessageAtDesc(String recruiterId);

    Optional<ChatRoom> findByApplicationId(String applicationId);

    boolean existsByApplicationId(String applicationId);
}
