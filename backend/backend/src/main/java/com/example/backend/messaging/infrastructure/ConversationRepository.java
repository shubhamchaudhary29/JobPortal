package com.example.backend.messaging.infrastructure;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface ConversationRepository extends MongoRepository<ConversationDocument, String> {

    Page<ConversationDocument> findByCandidateId(String candidateId, Pageable pageable);

    Page<ConversationDocument> findByRecruiterId(String recruiterId, Pageable pageable);

    Optional<ConversationDocument> findByApplicationId(String applicationId);

    boolean existsByApplicationId(String applicationId);
}
