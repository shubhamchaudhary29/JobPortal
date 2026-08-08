package com.example.backend.integration.adzuna;

import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicLong;

/** Lightweight counters are intentionally dependency-free; emit them in sanitized completion logs. */
@Component
public class AdzunaSyncMetrics {
    private final AtomicLong successes = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong latencyMs = new AtomicLong();
    public void success(long elapsed) { successes.incrementAndGet(); latencyMs.addAndGet(elapsed); }
    public void failure() { failures.incrementAndGet(); }
    public Snapshot snapshot() { return new Snapshot(successes.get(), failures.get(), latencyMs.get()); }
    public record Snapshot(long successes, long failures, long latencyMs) { }
}
