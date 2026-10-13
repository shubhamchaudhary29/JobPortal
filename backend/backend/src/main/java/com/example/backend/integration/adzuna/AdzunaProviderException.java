package com.example.backend.integration.adzuna;

/** Deliberately contains no provider response body, URI, or credentials. */
public class AdzunaProviderException extends com.example.backend.integration.search.SearchProviderException {
    public AdzunaProviderException(String safeMessage, boolean retryable, Throwable cause) {
        super(safeMessage, retryable, cause);
    }
}
