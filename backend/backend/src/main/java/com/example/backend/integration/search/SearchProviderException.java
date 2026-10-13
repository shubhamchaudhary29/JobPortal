package com.example.backend.integration.search;

/** A sanitized search-provider failure; the message never contains a URL, response body, or credential. */
public class SearchProviderException extends RuntimeException {
    private final boolean retryable;
    public SearchProviderException(String safeMessage, boolean retryable, Throwable cause) {
        super(safeMessage, cause);
        this.retryable = retryable;
    }
    public boolean retryable() { return retryable; }
}
