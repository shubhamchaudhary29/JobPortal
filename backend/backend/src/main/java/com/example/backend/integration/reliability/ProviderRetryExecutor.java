package com.example.backend.integration.reliability;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class ProviderRetryExecutor {
    private final ProviderReliabilityProperties properties;
    private final Sleeper sleeper;
    private final LongUnaryOperator jitter;
    @Autowired
    public ProviderRetryExecutor(ProviderReliabilityProperties properties) {
        this(properties, Thread::sleep, bound -> ThreadLocalRandom.current().nextLong(Math.max(1, bound)));
    }
    ProviderRetryExecutor(ProviderReliabilityProperties properties, Sleeper sleeper, LongUnaryOperator jitter) {
        this.properties = properties;
        this.sleeper = sleeper;
        this.jitter = jitter;
    }
    public <T> Result<T> execute(Supplier<T> operation) {
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            try { return new Result<>(operation.get(), attempt - 1); }
            catch (ProviderFailureException failure) {
                if (!failure.retryable() || attempt == properties.maxAttempts()) throw failure;
                long exponential = Math.min(properties.maxBackoffMs(),
                        properties.initialBackoffMs() * (1L << (attempt - 1)));
                long delay = Math.min(properties.maxBackoffMs(), exponential
                        + jitter.applyAsLong(Math.max(1, exponential / 2)));
                if (failure.retryAfter() != null) {
                    delay = Math.min(properties.maxBackoffMs(), Math.max(delay, failure.retryAfter().toMillis()));
                }
                try { sleeper.sleep(delay); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ProviderFailureException("external", ProviderFailureException.Kind.INTERRUPTED,
                            false, null, interrupted);
                }
            }
        }
        throw new IllegalStateException("unreachable retry state");
    }
    public record Result<T>(T value, int retries) { }
    @FunctionalInterface interface Sleeper { void sleep(long millis) throws InterruptedException; }
}
