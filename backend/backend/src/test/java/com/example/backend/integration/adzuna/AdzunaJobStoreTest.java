package com.example.backend.integration.adzuna;

import com.example.backend.job.infrastructure.ImportedSourceListing;
import com.example.backend.job.infrastructure.JobDocument;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdzunaJobStoreTest {
    @Test
    void usesOneAtomicSourceAndExternalIdUpsertForReplayedProviderJobs() {
        MongoTemplate mongo = mock(MongoTemplate.class); AdzunaJobStore store = new AdzunaJobStore(mongo); JobDocument job = new JobDocument();
        job.setSource("adzuna"); job.setExternalId("provider-1"); job.setTitle("Engineer"); job.setDescription("x"); job.setCompany("c"); job.setLocation("l");
        store.upsert(job, LocalDateTime.of(2026, 1, 1, 0, 0)); store.upsert(job, LocalDateTime.of(2026, 1, 1, 0, 1));
        verify(mongo, times(2)).findAndModify(argThat(q -> q.getQueryObject().toJson().contains("provider-1") && q.getQueryObject().toJson().contains("adzuna")), any(Update.class), argThat(options -> options.isUpsert()), eq(JobDocument.class));
    }

    @Test
    void duplicateKeyRetryTargetsWinnerAndPreservesItsListingState() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AdzunaJobStore store = new AdzunaJobStore(mongo);
        LocalDateTime originalFirstSeen = LocalDateTime.of(2025, 12, 1, 9, 0);
        LocalDateTime duplicateFirstSeen = originalFirstSeen.plusDays(1);
        LocalDateTime retryTime = LocalDateTime.of(2026, 1, 1, 10, 0);
        String identity = "adzuna:provider-1";

        ImportedSourceListing unrelated = listing("lever:other", originalFirstSeen.minusDays(1));
        ImportedSourceListing original = listing(identity, originalFirstSeen);
        ImportedSourceListing duplicate = listing(identity, duplicateFirstSeen);
        JobDocument winner = job("provider-1", "winner description");
        winner.setId("winner-id");
        winner.setSourceListings(new ArrayList<>(List.of(unrelated, original, duplicate)));
        JobDocument incoming = job("provider-1", "incoming description");

        when(mongo.findOne(any(Query.class), eq(JobDocument.class)))
                .thenReturn(null, null, winner);
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(), eq(JobDocument.class)))
                .thenThrow(new DuplicateKeyException("forced race"))
                .thenReturn(winner);
        doAnswer(invocation -> {
            Update listingUpdate = invocation.getArgument(1);
            Document updateDocument = listingUpdate.getUpdateObject();
            if (updateDocument.containsKey("$pull")) {
                winner.getSourceListings().removeIf(item -> identity.equals(item.getIdentity()));
            }
            if (updateDocument.containsKey("$addToSet")) {
                Document additions = (Document) updateDocument.get("$addToSet");
                winner.getSourceListings().add((ImportedSourceListing) additions.get("sourceListings"));
            }
            return null;
        }).when(mongo).updateFirst(any(Query.class), any(Update.class), eq(JobDocument.class));

        assertEquals(UpsertOutcome.UPDATED, store.upsert(incoming, retryTime));

        ArgumentCaptor<Query> retryQueries = ArgumentCaptor.forClass(Query.class);
        verify(mongo, times(2)).findAndModify(retryQueries.capture(), any(Update.class), any(), eq(JobDocument.class));
        Query retryQuery = retryQueries.getAllValues().get(1);
        assertEquals(new Document("_id", "winner-id"), retryQuery.getQueryObject());
        assertEquals(1, winner.getSourceListings().stream().filter(item -> identity.equals(item.getIdentity())).count());
        assertTrue(winner.getSourceListings().stream().anyMatch(item -> "lever:other".equals(item.getIdentity())));
        ImportedSourceListing persisted = winner.getSourceListings().stream()
                .filter(item -> identity.equals(item.getIdentity())).findFirst().orElseThrow();
        assertAll(
                () -> assertEquals(originalFirstSeen, persisted.getFirstSeenAt()),
                () -> assertEquals(retryTime, persisted.getLastSeenAt()),
                () -> assertTrue(persisted.isActive()),
                () -> assertEquals(0, persisted.getConsecutiveMissingRuns()));
        verify(mongo, times(2)).updateFirst(argThat(q -> new Document("_id", "winner-id").equals(q.getQueryObject())),
                any(Update.class), eq(JobDocument.class));
    }

    private static ImportedSourceListing listing(String identity, LocalDateTime firstSeenAt) {
        ImportedSourceListing listing = new ImportedSourceListing();
        listing.setIdentity(identity);
        listing.setProvider(identity.substring(0, identity.indexOf(':')));
        listing.setExternalId(identity.substring(identity.indexOf(':') + 1));
        listing.setFirstSeenAt(firstSeenAt);
        listing.setLastSeenAt(firstSeenAt.plusHours(1));
        listing.setActive(false);
        listing.setConsecutiveMissingRuns(3);
        return listing;
    }

    private static JobDocument job(String id, String description) {
        JobDocument job = new JobDocument();
        job.setSource("adzuna");
        job.setExternalId(id);
        job.setTitle("Engineer");
        job.setDescription(description);
        job.setCompany("Co");
        job.setLocation("Pune");
        job.setApplicationUrl("https://example.test/" + id);
        job.setSourceUrl(job.getApplicationUrl());
        job.setFingerprint("fingerprint");
        job.setActive(true);
        return job;
    }
}
