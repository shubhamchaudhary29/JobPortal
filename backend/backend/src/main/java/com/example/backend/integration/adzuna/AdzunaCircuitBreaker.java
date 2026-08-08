package com.example.backend.integration.adzuna;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Clock;

@Component
public class AdzunaCircuitBreaker {
    enum State { CLOSED, OPEN, HALF_OPEN }
    private final Clock clock;
    private final AdzunaProperties properties;
    private State state = State.CLOSED;
    private int failures;
    private long openedAt;
    private boolean halfOpenProbeInFlight;
    @Autowired
    public AdzunaCircuitBreaker(AdzunaProperties properties) { this(properties, Clock.systemUTC()); }
    AdzunaCircuitBreaker(AdzunaProperties properties, Clock clock) { this.properties = properties; this.clock = clock; }
    public synchronized boolean allowRequest() {
        if (state == State.OPEN && clock.millis() - openedAt >= properties.circuitOpenMs()) state = State.HALF_OPEN;
        if (state == State.HALF_OPEN) {
            if (halfOpenProbeInFlight) return false;
            halfOpenProbeInFlight = true;
            return true;
        }
        return state != State.OPEN;
    }
    public synchronized void recordSuccess() { failures = 0; halfOpenProbeInFlight = false; state = State.CLOSED; }
    public synchronized void recordFailure() {
        if (state == State.HALF_OPEN || ++failures >= properties.circuitFailureThreshold()) { halfOpenProbeInFlight = false; state = State.OPEN; openedAt = clock.millis(); }
    }
    synchronized State state() { return state; }
}
