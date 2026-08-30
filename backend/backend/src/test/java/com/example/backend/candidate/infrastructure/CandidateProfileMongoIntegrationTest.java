package com.example.backend.candidate.infrastructure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class CandidateProfileMongoIntegrationTest {
    @Autowired CandidateProfileRepository profiles;
    @Autowired MongoTemplate mongo;

    @AfterEach
    void clean() { profiles.deleteAll(); }

    @Test
    void persistsStructuredProfileAndEnforcesOneCanonicalProfilePerUser() {
        CandidateProfileDocument first = new CandidateProfileDocument();
        first.setUserId("candidate-one"); first.setLocation("Bengaluru, India");
        first.setSkills(java.util.List.of(new CandidateProfileDocument.Skill("Java", null, "Languages", null, "MANUAL")));
        profiles.save(first);
        CandidateProfileDocument loaded = profiles.findByUserId("candidate-one").orElseThrow();
        assertEquals("Bengaluru, India", loaded.getLocation());
        assertEquals("Java", loaded.getSkills().get(0).getName());

        CandidateProfileDocument duplicate = new CandidateProfileDocument(); duplicate.setUserId("candidate-one");
        assertThrows(DuplicateKeyException.class, () -> profiles.save(duplicate));
        assertEquals(1, profiles.count());
    }

    @Test
    void uniqueOwnershipIndexIsPresent() {
        assertTrue(mongo.indexOps(CandidateProfileDocument.class).getIndexInfo().stream()
                .anyMatch(index -> index.isUnique() && index.getIndexFields().stream()
                        .anyMatch(field -> field.getKey().equals("userId"))));
    }
}
