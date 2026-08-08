package com.example.backend.integration.adzuna;

import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicLong;

/** Lightweight counters are intentionally dependency-free; emit them in sanitized completion logs. */
@Component
public class AdzunaSyncMetrics {
    private final AtomicLong fullSuccesses = new AtomicLong();
    private final AtomicLong partialFailures = new AtomicLong();
    private final AtomicLong completeFailures = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong latencyMs = new AtomicLong();
    public void record(Outcome outcome, long elapsed) { switch (outcome) { case FULL_SUCCESS -> fullSuccesses.incrementAndGet(); case PARTIAL_FAILURE -> partialFailures.incrementAndGet(); case COMPLETE_FAILURE -> completeFailures.incrementAndGet(); case REJECTED -> rejected.incrementAndGet(); } latencyMs.addAndGet(elapsed); }
    public Snapshot snapshot() { return new Snapshot(fullSuccesses.get(), partialFailures.get(), completeFailures.get(), rejected.get(), latencyMs.get()); }
    public enum Outcome { FULL_SUCCESS, PARTIAL_FAILURE, COMPLETE_FAILURE, REJECTED }
    public record Snapshot(long fullSuccesses, long partialFailures, long completeFailures, long rejected, long latencyMs) { }
}
