package com.example.backend.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "refresh_tokens")
@CompoundIndex(name = "user_family_idx", def = "{'userId': 1, 'familyId': 1}")
public class RefreshToken {
    @Id private String id;
    @Version private Long version;
    @Indexed(unique = true) private String tokenHash;
    @Indexed private String userId;
    @Indexed private String familyId;
    private Instant createdAt;
    @Indexed(expireAfter = "0s") private Instant expiresAt;
    private Instant revokedAt;
    private String replacedByHash;
}
