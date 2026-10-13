package com.example.backend.integration.adzuna;

public interface AdzunaClient {
    AdzunaResponse fetchPage(String keyword, int page);
    /** {@code location} is sent as Adzuna's {@code where}; null means country-wide. */
    default AdzunaResponse fetchPage(String keyword, String location, int page) {
        if (location != null) throw new UnsupportedOperationException("location search is not supported");
        return fetchPage(keyword, page);
    }
}
