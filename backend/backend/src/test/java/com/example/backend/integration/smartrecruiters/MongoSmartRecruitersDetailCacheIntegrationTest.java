package com.example.backend.integration.smartrecruiters;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

@SpringBootTest
class MongoSmartRecruitersDetailCacheIntegrationTest {
    @Autowired MongoTemplate mongo;
    @Autowired SmartRecruitersProperties properties;

    @BeforeEach
    void clear() {
        mongo.remove(new org.springframework.data.mongodb.core.query.Query(), MongoSmartRecruitersDetailCache.COLLECTION);
    }

    @Test
    void detailsAreKeyedByBoardAndPostingAndValidOnlyForTheirReleaseUntilExpiry() {
        Instant now = Instant.parse("2026-10-13T00:00:00Z");
        MongoSmartRecruitersDetailCache cache = new MongoSmartRecruitersDetailCache(mongo,
                Clock.fixed(now, ZoneOffset.UTC), properties);
        SmartRecruitersDetailCache.Detail detail = new SmartRecruitersDetailCache.Detail("<p>Build</p>",
                "https://jobs.smartrecruiters.com/Acme/1-engineer", "https://jobs.smartrecruiters.com/Acme/1?oga=true");

        cache.save("Acme", "1", "2026-09-01T00:00:00.000Z", detail);

        Document stored = mongo.findById("acme:1", Document.class, MongoSmartRecruitersDetailCache.COLLECTION);
        assertAll(
                () -> assertEquals(detail, cache.find("acme", "1", "2026-09-01T00:00:00.000Z").orElseThrow()),
                () -> assertTrue(cache.find("Acme", "1", "2026-10-01T00:00:00.000Z").isEmpty()),
                () -> assertTrue(cache.find("Acme", "2", "2026-09-01T00:00:00.000Z").isEmpty()),
                () -> assertTrue(cache.find("Other", "1", "2026-09-01T00:00:00.000Z").isEmpty()),
                () -> assertEquals(now.plusSeconds(30L * 86_400).toEpochMilli(),
                        stored.getDate("expiresAt").getTime()));

        MongoSmartRecruitersDetailCache later = new MongoSmartRecruitersDetailCache(mongo,
                Clock.fixed(now.plusSeconds(31L * 86_400), ZoneOffset.UTC), properties);
        assertTrue(later.find("Acme", "1", "2026-09-01T00:00:00.000Z").isEmpty());

        cache.save("Acme", "1", null, detail);
        assertEquals(detail, cache.find("Acme", "1", null).orElseThrow());
        assertEquals(1, mongo.getCollection(MongoSmartRecruitersDetailCache.COLLECTION).countDocuments());
    }

    @Test
    void retentionTtlIndexIsCreatedAtStartup() {
        boolean ttl = mongo.indexOps(MongoSmartRecruitersDetailCache.COLLECTION).getIndexInfo().stream()
                .anyMatch(index -> "smartrecruiters_detail_retention_ttl".equals(index.getName())
                        && index.getExpireAfter().isPresent());
        assertTrue(ttl);
    }
}
