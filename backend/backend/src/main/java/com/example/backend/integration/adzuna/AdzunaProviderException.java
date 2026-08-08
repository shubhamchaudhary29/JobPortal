package com.example.backend.integration.adzuna;

/** Deliberately contains no provider response body, URI, or credentials. */
public class AdzunaProviderException extends RuntimeException {
    private final boolean retryable;
    public AdzunaProviderException(String safeMessage, boolean retryable, Throwable cause) {
        super(safeMessage, cause);
        this.retryable = retryable;
    }
    public boolean retryable() { return retryable; }
}
