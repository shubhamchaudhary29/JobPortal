package com.example.backend.integration.adzuna;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.junit.jupiter.api.Assertions.*;

class AdzunaCircuitBreakerTest {
    @Test
    void opensAfterThresholdAndClosesAfterSuccessfulHalfOpenProbe() {
        var props = AdzunaServiceTest.properties(1, "java");
        var clock = new MutableClock(Instant.EPOCH); var breaker = new AdzunaCircuitBreaker(props, clock);
        breaker.recordFailure(); breaker.recordFailure(); assertFalse(breaker.allowRequest()); assertEquals(AdzunaCircuitBreaker.State.OPEN, breaker.state());
        clock.now = Instant.ofEpochMilli(100); assertTrue(breaker.allowRequest()); assertFalse(breaker.allowRequest()); assertEquals(AdzunaCircuitBreaker.State.HALF_OPEN, breaker.state());
        breaker.recordSuccess(); assertEquals(AdzunaCircuitBreaker.State.CLOSED, breaker.state());
    }
    private static final class MutableClock extends Clock { private Instant now; MutableClock(Instant now) { this.now = now; } public ZoneOffset getZone() { return ZoneOffset.UTC; } public Clock withZone(java.time.ZoneId zone) { return this; } public Instant instant() { return now; } }
}
