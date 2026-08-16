package com.example.backend.integration.reliability;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class ProviderCircuitBreaker {
    private static final int MAX_SCOPES = 1_000;
    private final ProviderReliabilityProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    public ProviderCircuitBreaker(ProviderReliabilityProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public <T> T execute(String provider, String employer, Supplier<T> operation) {
        State state = state(provider, employer);
        synchronized (state) {
            long now = clock.millis();
            if (state.openUntil > now || state.probeInProgress) throw open(provider);
            if (state.openUntil != 0) state.probeInProgress = true;
        }
        try {
            T value = operation.get();
            synchronized (state) {
                state.failures = 0;
                state.openUntil = 0;
                state.probeInProgress = false;
            }
            return value;
        } catch (ProviderFailureException failure) {
            synchronized (state) {
                state.probeInProgress = false;
                if (failure.retryable()) {
                    state.failures++;
                    if (state.failures >= properties.circuitFailureThreshold()) {
                        state.openUntil = clock.millis() + properties.circuitOpenMs();
                    }
                } else {
                    state.failures = 0;
                    state.openUntil = 0;
                }
            }
            throw failure;
        } catch (RuntimeException failure) {
            synchronized (state) { state.probeInProgress = false; }
            throw failure;
        }
    }

    private State state(String provider, String employer) {
        String key = provider + ":" + employer;
        State existing = states.get(key);
        if (existing != null) return existing;
        if (states.size() >= MAX_SCOPES) throw open(provider);
        return states.computeIfAbsent(key, ignored -> new State());
    }

    private ProviderFailureException open(String provider) {
        return new ProviderFailureException(provider, ProviderFailureException.Kind.CIRCUIT_OPEN,
                false, null, null);
    }

    private static final class State {
        private int failures;
        private long openUntil;
        private boolean probeInProgress;
    }
}
