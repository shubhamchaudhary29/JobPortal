package com.example.backend.job.infrastructure;

import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface JobRepository extends MongoRepository<JobDocument, String> {

    Page<JobDocument> findByRecruiterId(String recruiterId, Pageable pageable);

    boolean existsBySourceAndExternalId(String source, String externalId);
}
