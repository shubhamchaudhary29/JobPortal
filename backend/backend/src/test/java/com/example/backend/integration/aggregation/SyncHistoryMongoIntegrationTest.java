package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.job.infrastructure.ImportedSourceListing;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.shared.error.BadRequestException;
import com.example.backend.shared.error.ResourceNotFoundException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@SpringBootTest
class SyncHistoryMongoIntegrationTest {
    @Autowired SyncRunService runs;
    @Autowired IngestionAdminService admin;
    @Autowired MongoTemplate mongo;

    @BeforeEach
    void clear() {
        mongo.remove(new Query(), SyncRunDocument.class);
        mongo.remove(new Query(), JobDocument.class);
    }

    @Test
    void historyIsFilterableBoundedPaginatedAndStablySorted() {
        Instant older = Instant.parse("2026-07-01T00:00:00Z");
        Instant newer = Instant.parse("2026-07-02T00:00:00Z");
        mongo.insert(run("b", "lever", "board", SyncRunService.Outcome.FAILED,
                SyncRunService.Trigger.MANUAL, newer));
        mongo.insert(run("a", "lever", "board", SyncRunService.Outcome.FAILED,
                SyncRunService.Trigger.MANUAL, newer));
        mongo.insert(run("c", "lever", "other", SyncRunService.Outcome.COMPLETED,
                SyncRunService.Trigger.SCHEDULED, older));
        mongo.insert(run("d", "greenhouse", "board", SyncRunService.Outcome.PARTIAL,
                SyncRunService.Trigger.MANUAL, older));

        var first = runs.history("LEVER", "board", "failed", "manual", 0, 1);
        var second = runs.history("lever", "board", "FAILED", "MANUAL", 1, 1);

        assertAll(
                () -> assertEquals(List.of("a"), first.content().stream().map(SyncRunService.RunView::runId).toList()),
                () -> assertEquals(List.of("b"), second.content().stream().map(SyncRunService.RunView::runId).toList()),
                () -> assertEquals(2, first.totalElements()),
                () -> assertEquals(2, first.totalPages()),
                () -> assertEquals("a", runs.detail("a").runId()),
                () -> assertFalse(runs.detail("b").failureDetail().contains("provider.test")),
                () -> assertFalse(runs.detail("b").failureDetail().contains("secret-value")),
                () -> assertThrows(ResourceNotFoundException.class, () -> runs.detail("missing")),
                () -> assertThrows(BadRequestException.class, () -> runs.history(null, null, null, null, -1, 20)),
                () -> assertThrows(BadRequestException.class, () -> runs.history(null, null, null, null, 0, 101)),
                () -> assertThrows(BadRequestException.class, () -> runs.history("unknown", null, null, null, 0, 20)),
                () -> assertThrows(BadRequestException.class, () -> runs.history(null, "bad/board", null, null, 0, 20)),
                () -> assertThrows(BadRequestException.class, () -> runs.history(null, null, "unknown", null, 0, 20)),
                () -> assertThrows(BadRequestException.class, () -> runs.history(null, null, null, "unknown", 0, 20)));
    }

    @Test
    void latestStatusIsOnePerScopeAndResponsesExcludeRetentionMetadata() {
        Instant older = Instant.parse("2026-07-01T00:00:00Z");
        Instant newer = Instant.parse("2026-07-02T00:00:00Z");
        mongo.insert(run("old", "lever", "board", SyncRunService.Outcome.FAILED,
                SyncRunService.Trigger.MANUAL, older));
        mongo.insert(run("new", "lever", "board", SyncRunService.Outcome.COMPLETED,
                SyncRunService.Trigger.SCHEDULED, newer));
        mongo.insert(run("greenhouse", "greenhouse", "other", SyncRunService.Outcome.PARTIAL,
                SyncRunService.Trigger.MANUAL, newer));

        assertAll(
                () -> assertEquals(List.of("greenhouse", "new"), runs.latest(null, null).stream()
                        .map(SyncRunService.RunView::runId).toList()),
                () -> assertEquals(List.of("new"), runs.latest("lever", "board").stream()
                        .map(SyncRunService.RunView::runId).toList()),
                () -> assertFalse(java.util.Arrays.stream(SyncRunService.RunView.class.getRecordComponents())
                        .anyMatch(component -> "expiresAt".equals(component.getName()))));
    }

    @Test
    void providerCompanyCountsUseEachSourceListingAndIgnoreRecruiterJobs() {
        JobDocument imported = new JobDocument();
        imported.setCompany("Acme");
        imported.setSource("greenhouse");
        imported.setExternalId("board:one");
        imported.setSourceListings(List.of(listing("greenhouse", "board", true),
                listing("lever", "acme", false)));
        mongo.insert(imported);
        JobDocument recruiter = new JobDocument();
        recruiter.setRecruiterId("recruiter-1");
        recruiter.setCompany("Manual");
        recruiter.setSourceListings(List.of(listing("lever", "manual", true)));
        mongo.insert(recruiter);

        List<IngestionAdminService.ProviderCompanyCount> counts = admin.providerCompanyCounts();

        assertEquals(2, counts.size());
        assertAll(
                () -> assertEquals("greenhouse", counts.get(0).provider()),
                () -> assertEquals(1, counts.get(0).activeListings()),
                () -> assertEquals("lever", counts.get(1).provider()),
                () -> assertEquals(1, counts.get(1).inactiveListings()));
    }

    private SyncRunDocument run(String id, String provider, String employer, SyncRunService.Outcome outcome,
                                SyncRunService.Trigger trigger, Instant startedAt) {
        SyncRunDocument run = new SyncRunDocument();
        run.setId(id);
        run.setRunId(id);
        run.setProvider(provider);
        run.setEmployer(employer);
        run.setOutcome(outcome);
        run.setTrigger(trigger);
        run.setStartedAt(startedAt);
        run.setCompletedAt(startedAt.plusSeconds(10));
        run.setExpiresAt(startedAt.plusSeconds(86400));
        if (outcome == SyncRunService.Outcome.FAILED) {
            run.setFailureType("Unsafe Type With Spaces");
            run.setFailureDetail("token=secret-value https://provider.test/private");
        }
        return run;
    }

    private ImportedSourceListing listing(String provider, String employer, boolean active) {
        ImportedSourceListing listing = new ImportedSourceListing();
        listing.setIdentity(provider + ":" + employer + ":one");
        listing.setProvider(provider);
        listing.setEmployer(employer);
        listing.setExternalId(employer + ":one");
        listing.setActive(active);
        return listing;
    }
}
