package com.example.backend.integration.reliability;

import java.time.Duration;

public class ProviderFailureException extends RuntimeException {
    private final Kind kind;
    private final boolean retryable;
    private final Duration retryAfter;

    public ProviderFailureException(String provider, Kind kind, boolean retryable,
                                    Duration retryAfter, Throwable cause) {
        super(provider + " provider request failed: " + kind.name().toLowerCase(), cause);
        this.kind = kind;
        this.retryable = retryable;
        this.retryAfter = retryAfter;
    }

    public Kind kind() { return kind; }
    public boolean retryable() { return retryable; }
    public Duration retryAfter() { return retryAfter; }

    public enum Kind { TIMEOUT, RATE_LIMITED, SERVER_ERROR, CLIENT_ERROR, MALFORMED_RESPONSE, INTERRUPTED }
}
