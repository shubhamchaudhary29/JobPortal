package com.example.backend.entity;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataIntegrityIndexTest {
    @Test
    void uniquenessAndTtlIndexesAreDeclared() throws Exception {
        assertTrue(Application.class.getAnnotation(CompoundIndex.class).unique());
        assertTrue(indexed(User.class, "email").unique());
        assertTrue(indexed(RefreshToken.class, "tokenHash").unique());
        assertEquals("0s", indexed(RefreshToken.class, "expiresAt").expireAfter());
        assertTrue(indexed(ChatRoom.class, "applicationId").unique());
    }

    @Test
    void ownershipQueryFieldsAreIndexed() throws Exception {
        assertTrue(indexed(Application.class, "jobId") != null);
        assertTrue(indexed(Application.class, "userId") != null);
        assertTrue(indexed(Jobs.class, "recruiterId") != null);
        assertTrue(indexed(ChatRoom.class, "candidateId") != null);
        assertTrue(indexed(ChatRoom.class, "recruiterId") != null);
    }

    private Indexed indexed(Class<?> type, String field) throws Exception {
        return type.getDeclaredField(field).getAnnotation(Indexed.class);
    }
}
