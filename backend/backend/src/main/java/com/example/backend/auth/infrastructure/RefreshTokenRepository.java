package com.example.backend.auth.infrastructure;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends MongoRepository<RefreshTokenDocument, String> {
    Optional<RefreshTokenDocument> findByTokenHash(String tokenHash);
    List<RefreshTokenDocument> findByUserIdAndFamilyIdAndRevokedAtIsNull(String userId, String familyId);
}
