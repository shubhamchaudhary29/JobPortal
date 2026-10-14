package com.example.backend.geo.application;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MapJobCacheTest {
    private MutableClock clock;
    private SimpleMeterRegistry registry;
    private MapJobCache cache;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        registry = new SimpleMeterRegistry();
        cache = new MapJobCache(clock, registry, 120_000, 3);
    }

    @Test
    void hitsAndMissesAreCountedAndValuesReused() {
        AtomicInteger computations = new AtomicInteger();
        assertEquals("v", cache.get(key("a"), () -> { computations.incrementAndGet(); return "v"; }));
        assertEquals("v", cache.get(key("a"), () -> { computations.incrementAndGet(); return "other"; }));
        assertEquals(1, computations.get());
        assertEquals(1.0, registry.counter("jobportal.geo.mapjobs.cache", "result", "hit").count());
        assertEquals(1.0, registry.counter("jobportal.geo.mapjobs.cache", "result", "miss").count());
    }

    @Test
    void entriesExpireAfterTheTtl() {
        cache.get(key("a"), () -> "first");
        clock.millis += 119_999;
        assertEquals("first", cache.get(key("a"), () -> "second"));
        clock.millis += 1;
        assertEquals("second", cache.get(key("a"), () -> "second"));
    }

    @Test
    void oldestEntriesAreEvictedAtCapacityAfterExpiredOnes() {
        cache.get(key("a"), () -> "a");
        cache.get(key("b"), () -> "b");
        cache.get(key("c"), () -> "c");
        cache.get(key("d"), () -> "d");
        assertEquals(3, cache.size());
        assertEquals("recomputed", cache.get(key("a"), () -> "recomputed"), "oldest entry was evicted");
        assertEquals("d", cache.get(key("d"), () -> "x"));
    }

    @Test
    void concurrentMissesShareOneComputation() throws Exception {
        AtomicInteger computations = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(() -> cache.get(key("a"), () -> {
                computations.incrementAndGet();
                started.countDown();
                await(release);
                return "shared";
            }));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Future<String> second = pool.submit(() -> cache.get(key("a"), () -> {
                computations.incrementAndGet();
                return "duplicate";
            }));
            Thread.sleep(100);
            release.countDown();
            assertEquals("shared", first.get(5, TimeUnit.SECONDS));
            assertEquals("shared", second.get(5, TimeUnit.SECONDS));
            assertEquals(1, computations.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failedComputationsAreNotCached() {
        assertThrows(IllegalStateException.class, () -> cache.get(key("a"), () -> { throw new IllegalStateException("down"); }));
        assertEquals("ok", cache.get(key("a"), () -> "ok"));
    }

    @Test
    void aNewSnapshotVersionIsAMiss() {
        cache.get(new MapJobCache.Key("place", "v1", "123", "", 0, 20), () -> "old");
        assertEquals("new", cache.get(new MapJobCache.Key("place", "v2", "123", "", 0, 20), () -> "new"));
    }

    private static MapJobCache.Key key(String scope) { return new MapJobCache.Key("place", "v1", scope, "", 0, 20); }

    private static void await(CountDownLatch latch) {
        try { latch.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }

    private static final class MutableClock extends Clock {
        long millis = 1_000_000;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }
}
