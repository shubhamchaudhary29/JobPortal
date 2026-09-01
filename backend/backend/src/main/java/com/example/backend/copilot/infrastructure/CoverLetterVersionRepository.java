package com.example.backend.copilot.infrastructure;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface CoverLetterVersionRepository extends MongoRepository<CoverLetterVersionDocument, String> {
    Optional<CoverLetterVersionDocument> findByIdAndUserId(String id, String userId);
    Page<CoverLetterVersionDocument> findByUserIdAndJobId(String userId, String jobId, Pageable pageable);
    long countByUserIdAndJobId(String userId, String jobId);
}
