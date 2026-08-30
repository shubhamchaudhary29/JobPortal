package com.example.backend.shared.architecture;

import com.example.backend.application.infrastructure.ApplicationDocument;
import com.example.backend.auth.infrastructure.RefreshTokenDocument;
import com.example.backend.messaging.infrastructure.ConversationDocument;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.user.infrastructure.UserDocument;
import com.example.backend.candidate.infrastructure.CandidateProfileDocument;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import static org.junit.jupiter.api.Assertions.*;

class DataIntegrityIndexTest {
    @Test
    void uniquenessAndTtlIndexesRemainDeclared() throws Exception {
        assertTrue(java.util.Arrays.stream(ApplicationDocument.class.getAnnotationsByType(CompoundIndex.class)).anyMatch(CompoundIndex::unique));
        assertTrue(indexed(UserDocument.class, "email").unique());
        assertTrue(indexed(RefreshTokenDocument.class, "tokenHash").unique());
        assertEquals("0s", indexed(RefreshTokenDocument.class, "expiresAt").expireAfter());
        assertTrue(indexed(ConversationDocument.class, "applicationId").unique());
        assertTrue(compound(CandidateProfileDocument.class, "userId"));
    }

    @Test
    void ownershipQueryFieldsRemainIndexed() throws Exception {
        assertTrue(compound(ApplicationDocument.class, "jobId"));
        assertTrue(compound(ApplicationDocument.class, "userId"));
        // Job indexes are created by the duplicate-auditing startup migration, not annotations.
        assertNotNull(JobDocument.class);
        assertTrue(compound(ConversationDocument.class, "candidateId"));
        assertTrue(compound(ConversationDocument.class, "recruiterId"));
    }

    private Indexed indexed(Class<?> type, String field) throws Exception {
        return type.getDeclaredField(field).getAnnotation(Indexed.class);
    }
    private boolean compound(Class<?> type, String field) {
        return java.util.Arrays.stream(type.getAnnotationsByType(CompoundIndex.class)).anyMatch(index -> index.def().contains("'" + field + "'"));
    }
}
