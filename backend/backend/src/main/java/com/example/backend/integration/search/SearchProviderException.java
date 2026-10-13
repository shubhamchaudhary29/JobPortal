package com.example.backend.integration.search;

/** A sanitized search-provider failure; the message never contains a URL, response body, or credential. */
public class SearchProviderException extends RuntimeException {
    private final boolean retryable;
    private final Long retryAfterMs;
    public SearchProviderException(String safeMessage, boolean retryable, Throwable cause) {
        this(safeMessage, retryable, null, cause);
    }
    /** {@code retryAfterMs} is the provider's requested wait (for example from {@code Retry-After}), if any. */
    public SearchProviderException(String safeMessage, boolean retryable, Long retryAfterMs, Throwable cause) {
        super(safeMessage, cause);
        this.retryable = retryable;
        this.retryAfterMs = retryAfterMs;
    }
    public boolean retryable() { return retryable; }
    public Long retryAfterMs() { return retryAfterMs; }
}
