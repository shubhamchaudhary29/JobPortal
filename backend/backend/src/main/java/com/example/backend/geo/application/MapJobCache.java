package com.example.backend.geo.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Small TTL cache for map job-list query results. Keys include the snapshot version, so any content change
 * starts a fresh key space. Concurrent misses for one key share a single computation; failures are not cached.
 */
@Component
public class MapJobCache {
    private final Map<Key, Entry> entries = new ConcurrentHashMap<>();
    private final Map<Key, CompletableFuture<Object>> inFlight = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final Clock clock;
    private final long ttlMs;
    private final int maxEntries;
    private final Counter hits;
    private final Counter misses;

    public MapJobCache(Clock clock, MeterRegistry registry,
                       @Value("${geo.map-jobs.cache-ttl-ms:120000}") long ttlMs,
                       @Value("${geo.map-jobs.cache-max-entries:2000}") int maxEntries) {
        if (ttlMs < 0 || maxEntries < 1) throw new IllegalArgumentException("invalid map job cache settings");
        this.clock = clock;
        this.ttlMs = ttlMs;
        this.maxEntries = maxEntries;
        this.hits = Counter.builder("jobportal.geo.mapjobs.cache").tag("result", "hit").register(registry);
        this.misses = Counter.builder("jobportal.geo.mapjobs.cache").tag("result", "miss").register(registry);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Key key, Supplier<T> compute) {
        T cached = (T) fresh(key);
        if (cached != null) { hits.increment(); return cached; }
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            hits.increment();
            return (T) await(running);
        }
        try {
            Object again = fresh(key);
            if (again != null) { hits.increment(); mine.complete(again); return (T) again; }
            misses.increment();
            T value = compute.get();
            put(key, value);
            mine.complete(value);
            return value;
        } catch (RuntimeException | Error failure) {
            mine.completeExceptionally(failure);
            throw failure;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    int size() { return entries.size(); }

    private Object fresh(Key key) {
        Entry entry = entries.get(key);
        if (entry == null) return null;
        if (entry.expiresAt() <= clock.millis()) { entries.remove(key, entry); return null; }
        return entry.value();
    }

    private void put(Key key, Object value) {
        if (value == null) return;
        if (entries.size() >= maxEntries) evict();
        entries.put(key, new Entry(value, clock.millis() + ttlMs, sequence.incrementAndGet()));
    }

    private synchronized void evict() {
        long now = clock.millis();
        entries.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        int excess = entries.size() - maxEntries + 1;
        if (excess <= 0) return;
        List<Key> oldest = entries.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().sequence()))
                .limit(excess).map(Map.Entry::getKey).toList();
        oldest.forEach(entries::remove);
    }

    private static Object await(CompletableFuture<Object> running) {
        try {
            return running.join();
        } catch (CompletionException wrapped) {
            if (wrapped.getCause() instanceof RuntimeException runtime) throw runtime;
            if (wrapped.getCause() instanceof Error error) throw error;
            throw wrapped;
        }
    }

    /** {@code scope} is the sorted place ids or the unplaced kind; {@code q} is already normalized. */
    public record Key(String endpoint, String snapshotVersion, String scope, String q, int page, int size) { }

    private record Entry(Object value, long expiresAt, long sequence) { }
}
