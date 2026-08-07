package com.example.backend.exception;

public class RateLimitException extends RuntimeException {
    private final long retryAfterSeconds;
    public RateLimitException(long retryAfterSeconds) {
        super("Too many authentication attempts");
        this.retryAfterSeconds = retryAfterSeconds;
    }
    public long getRetryAfterSeconds() { return retryAfterSeconds; }
}
