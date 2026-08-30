package com.example.backend.candidate.infrastructure;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface CandidateProfileRepository extends MongoRepository<CandidateProfileDocument, String> {
    Optional<CandidateProfileDocument> findByUserId(String userId);
}
