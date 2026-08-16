package com.example.backend.integration.reliability;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ProviderRequestLimiter {
    private static final int MAX_SCOPES = 1_000;
    private final long intervalMs;
    private final Clock clock;
    private final Sleeper sleeper;
    private final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();

    @Autowired
    public ProviderRequestLimiter(ProviderReliabilityProperties properties, Clock clock) {
        this(properties, clock, Thread::sleep);
    }

    ProviderRequestLimiter(ProviderReliabilityProperties properties, Clock clock, Sleeper sleeper) {
        this.intervalMs = Math.max(1, (long) Math.ceil(1_000.0 / properties.requestsPerSecond()));
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public void acquire(String provider, String employer) {
        String key = provider + ":" + employer;
        Slot slot = slots.get(key);
        if (slot == null) {
            if (slots.size() >= MAX_SCOPES) {
                throw new ProviderFailureException(provider, ProviderFailureException.Kind.RATE_LIMITED,
                        false, null, null);
            }
            slot = slots.computeIfAbsent(key, ignored -> new Slot());
        }
        long delay;
        synchronized (slot) {
            long now = clock.millis();
            delay = Math.max(0, slot.nextAllowed - now);
            slot.nextAllowed = Math.max(now, slot.nextAllowed) + intervalMs;
        }
        if (delay == 0) return;
        try {
            sleeper.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ProviderFailureException(provider, ProviderFailureException.Kind.INTERRUPTED,
                    false, null, interrupted);
        }
    }

    private static final class Slot { private long nextAllowed; }
    @FunctionalInterface interface Sleeper { void sleep(long millis) throws InterruptedException; }
}
