package com.example.backend.application.infrastructure;

import com.example.backend.application.domain.ApplicationStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ApplicationRepository extends MongoRepository<ApplicationDocument, String> {
    Page<ApplicationDocument> findByJobId(String jobId, Pageable pageable);

    boolean existsByUserIdAndJobId(String userId, String jobId);

    boolean existsByJobId(String jobId);

    Page<ApplicationDocument> findByUserId(String userId, Pageable pageable);

    int countByUserId(String userId);

    int countByUserIdAndStatus(String userId, ApplicationStatus status);
}
