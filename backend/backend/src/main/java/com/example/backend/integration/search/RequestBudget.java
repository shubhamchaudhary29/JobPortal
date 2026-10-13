package com.example.backend.integration.search;

/** Reserves provider requests against a monthly quota before they are sent. */
public interface RequestBudget {
    /** Atomically reserves one request; false when the provider's monthly budget is spent. */
    boolean tryConsume(String provider, int monthlyLimit);

    static RequestBudget unlimited() { return (provider, monthlyLimit) -> true; }
}
