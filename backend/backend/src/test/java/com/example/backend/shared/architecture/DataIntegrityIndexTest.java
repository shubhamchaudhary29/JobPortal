package com.example.backend.shared.architecture;

import com.example.backend.application.infrastructure.ApplicationDocument;
import com.example.backend.auth.infrastructure.RefreshTokenDocument;
import com.example.backend.messaging.infrastructure.ConversationDocument;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.user.infrastructure.UserDocument;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import static org.junit.jupiter.api.Assertions.*;

class DataIntegrityIndexTest {
    @Test
    void uniquenessAndTtlIndexesRemainDeclared() throws Exception {
        assertTrue(ApplicationDocument.class.getAnnotation(CompoundIndex.class).unique());
        assertTrue(indexed(UserDocument.class, "email").unique());
        assertTrue(indexed(RefreshTokenDocument.class, "tokenHash").unique());
        assertEquals("0s", indexed(RefreshTokenDocument.class, "expiresAt").expireAfter());
        assertTrue(indexed(ConversationDocument.class, "applicationId").unique());
    }

    @Test
    void ownershipQueryFieldsRemainIndexed() throws Exception {
        assertNotNull(indexed(ApplicationDocument.class, "jobId"));
        assertNotNull(indexed(ApplicationDocument.class, "userId"));
        assertNotNull(indexed(JobDocument.class, "recruiterId"));
        assertNotNull(indexed(ConversationDocument.class, "candidateId"));
        assertNotNull(indexed(ConversationDocument.class, "recruiterId"));
    }

    private Indexed indexed(Class<?> type, String field) throws Exception {
        return type.getDeclaredField(field).getAnnotation(Indexed.class);
    }
}
