package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@SpringBootTest
class CoordinatorConcurrencyMongoIntegrationTest {
    @Autowired DistributedLeaseLock locks;
    @Autowired SyncRunService runs;
    @Autowired MongoTemplate mongo;

    @BeforeEach
    void clear() {
        mongo.getCollection("ingestion_locks").deleteMany(new org.bson.Document());
        mongo.remove(new Query(), SyncRunDocument.class);
    }

    @Test
    void twoInstancesCannotOverlapAndBothOutcomesAreDurable() throws Exception {
        EmployerIngestionService ingestion = mock(EmployerIngestionService.class);
        ScheduledExecutorService heartbeatScheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
        doReturn(heartbeat).when(heartbeatScheduler)
                .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(ingestion.sync(eq(EmployerRegistryProperties.Source.LEVER), any())).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return new EmployerIngestionService.Result(1, 0, 0, 0, 0);
        });
        IngestionCoordinator first = new IngestionCoordinator(
                ingestion, locks, 5_000, 1_000, heartbeatScheduler, runs);
        IngestionCoordinator second = new IngestionCoordinator(
                ingestion, locks, 5_000, 1_000, heartbeatScheduler, runs);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var firstFuture = executor.submit(() -> first.run(EmployerRegistryProperties.Source.LEVER,
                    SyncRunService.Trigger.SCHEDULED));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            IngestionCoordinator.Result overlap = second.run(
                    EmployerRegistryProperties.Source.LEVER, SyncRunService.Trigger.MANUAL);
            assertTrue(overlap.locked());
            release.countDown();
            assertFalse(firstFuture.get(5, TimeUnit.SECONDS).locked());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        verify(ingestion, times(1)).sync(eq(EmployerRegistryProperties.Source.LEVER), any());
        assertEquals(java.util.Set.of(SyncRunService.Outcome.COMPLETED, SyncRunService.Outcome.LOCKED),
                mongo.findAll(SyncRunDocument.class).stream().map(SyncRunDocument::getOutcome)
                        .collect(java.util.stream.Collectors.toSet()));
    }
}
