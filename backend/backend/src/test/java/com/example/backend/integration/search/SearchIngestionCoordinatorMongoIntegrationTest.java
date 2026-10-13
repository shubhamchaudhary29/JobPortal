package com.example.backend.integration.search;

import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.integration.aggregation.DistributedLeaseLock;
import com.example.backend.integration.aggregation.SyncRunDocument;
import com.example.backend.integration.aggregation.SyncRunService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@SpringBootTest
class SearchIngestionCoordinatorMongoIntegrationTest {
    @Autowired DistributedLeaseLock locks;
    @Autowired SyncRunService runs;
    @Autowired MongoTemplate mongo;
    @Autowired SearchIngestionCoordinators registry;
    @Autowired @Qualifier("ingestionLeaseScheduler") ScheduledExecutorService scheduler;

    @BeforeEach
    void clear() {
        mongo.remove(new Query(), SyncRunDocument.class);
        mongo.remove(new Query(), "ingestion_locks");
    }

    @Test
    void budgetStoppedRunsArePartialWithAReasonAndOtherRunsHaveNone() {
        SearchIngestionCoordinator budgetStopped = coordinator(leaseValid -> new SearchSyncResult(
                3, 0, 0, 0, 0, 0, 0, 0, 0, 4, SearchSyncResult.Outcome.PARTIAL_FAILURE, true));
        SearchIngestionCoordinator.Result result = budgetStopped.run(SyncRunService.Trigger.SCHEDULED);
        SearchIngestionCoordinator complete = coordinator(leaseValid -> new SearchSyncResult(
                1, 0, 0, 0, 0, 0, 0, 0, 0, 1, SearchSyncResult.Outcome.FULL_SUCCESS, false));
        SearchIngestionCoordinator.Result completed = complete.run(SyncRunService.Trigger.MANUAL);

        SyncRunService.RunView stopped = runs.detail(result.runId());
        SyncRunService.RunView normal = runs.detail(completed.runId());
        assertAll(
                () -> assertEquals(SyncRunService.Outcome.PARTIAL, result.outcome()),
                () -> assertEquals(SyncRunService.Outcome.PARTIAL, stopped.outcome()),
                () -> assertEquals("BUDGET_EXHAUSTED", stopped.reason()),
                () -> assertEquals("adzuna", stopped.provider()),
                () -> assertEquals(3, stopped.inserted()),
                () -> assertEquals(4, stopped.attemptedBatches()),
                () -> assertEquals(SyncRunService.Outcome.COMPLETED, normal.outcome()),
                () -> assertNull(normal.reason()),
                () -> assertNull(mongo.findById(completed.runId(), SyncRunDocument.class).getReason()));
    }

    @Test
    void twoInstancesShareTheProviderLeaseSoOnlyOneRuns() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SearchIngestionCoordinator first = coordinator(leaseValid -> {
            inside.countDown();
            try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
            return new SearchSyncResult(0, 0, 0, 0, 0, 0, 0, 0, 0, 1, SearchSyncResult.Outcome.FULL_SUCCESS, false);
        });
        SearchIngestionCoordinator second = coordinator(leaseValid -> { throw new AssertionError("must not run"); });
        var pool = Executors.newSingleThreadExecutor();
        Future<SearchIngestionCoordinator.Result> running = pool.submit(() -> first.run(SyncRunService.Trigger.SCHEDULED));
        assertTrue(inside.await(5, TimeUnit.SECONDS));

        SearchIngestionCoordinator.Result locked = second.run(SyncRunService.Trigger.MANUAL);
        release.countDown();

        assertTrue(locked.locked());
        assertEquals(SyncRunService.Outcome.LOCKED, runs.detail(locked.runId()).outcome());
        assertEquals(SyncRunService.Outcome.COMPLETED, running.get(5, TimeUnit.SECONDS).outcome());
        pool.shutdown();
    }

    @Test
    void registryExposesAdzunaThroughItsCoordinator() {
        assertEquals("adzuna", registry.find("ADZUNA").orElseThrow().provider());
        assertTrue(registry.find("greenhouse").isEmpty());
        assertTrue(registry.find("unknown").isEmpty());
    }

    private SearchIngestionCoordinator coordinator(java.util.function.Function<java.util.function.BooleanSupplier, SearchSyncResult> ingestion) {
        return new SearchIngestionCoordinator("adzuna", ingestion, locks, 5_000, 1_000, scheduler, runs);
    }
}
