package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class DistributedLeaseLockMongoIntegrationTest {
    @Autowired MongoTemplate mongo;
    private MutableClock clock;
    private DistributedLeaseLock locks;

    @BeforeEach
    void clear() {
        mongo.getCollection("ingestion_locks").deleteMany(new org.bson.Document());
        clock = new MutableClock(Instant.parse("2026-08-01T00:00:00Z"));
        locks = new DistributedLeaseLock(mongo, clock);
    }

    @Test
    void acquisitionRenewalContentionAndOwnershipSafeReleaseAreDeterministic() {
        String owner = locks.acquire("provider", 1_000);
        assertNotNull(owner);
        assertNull(locks.acquire("provider", 1_000));
        clock.advance(Duration.ofMillis(800));
        assertTrue(locks.renew("provider", owner, 1_000));
        clock.advance(Duration.ofMillis(800));
        assertNull(locks.acquire("provider", 1_000));

        locks.release("provider", "not-the-owner");
        assertNull(locks.acquire("provider", 1_000));
        locks.release("provider", owner);
        assertNotNull(locks.acquire("provider", 1_000));
    }

    @Test
    void expiredLeaseIsRecoveredAndFormerOwnerCannotRenewOrReleaseWinner() {
        String formerOwner = locks.acquire("provider", 1_000);
        clock.advance(Duration.ofMillis(1_001));
        String winner = locks.acquire("provider", 1_000);

        assertAll(
                () -> assertNotNull(winner),
                () -> assertNotEquals(formerOwner, winner),
                () -> assertFalse(locks.renew("provider", formerOwner, 1_000)));
        locks.release("provider", formerOwner);
        org.bson.Document persisted = mongo.getCollection("ingestion_locks")
                .find(new org.bson.Document("_id", "provider")).first();
        assertNotNull(persisted);
        assertEquals(winner, persisted.getString("owner"));
    }

    @Test
    void heartbeatRenewsLongRunAndOwnershipLossStopsGuardWithoutDeletingWinner() {
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        AtomicReference<Runnable> heartbeat = new AtomicReference<>();
        doAnswer(invocation -> {
            heartbeat.set(invocation.getArgument(0));
            return future;
        }).when(scheduler).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(),
                eq(TimeUnit.MILLISECONDS));
        String owner = locks.acquire("provider", 1_000);
        DistributedLeaseGuard guard = DistributedLeaseGuard.start(
                locks, "provider", owner, 1_000, 250, scheduler);

        clock.advance(Duration.ofMillis(800));
        heartbeat.get().run();
        clock.advance(Duration.ofMillis(800));
        assertNull(locks.acquire("provider", 1_000));

        mongo.getCollection("ingestion_locks").updateOne(new org.bson.Document("_id", "provider"),
                new org.bson.Document("$set", new org.bson.Document("owner", "winner")));
        heartbeat.get().run();
        assertTrue(guard.isLost());
        guard.close();
        org.bson.Document persisted = mongo.getCollection("ingestion_locks")
                .find(new org.bson.Document("_id", "provider")).first();
        assertNotNull(persisted);
        assertEquals("winner", persisted.getString("owner"));
        verify(future).cancel(true);
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        MutableClock(Instant initial) { now = new AtomicReference<>(initial); }
        void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
