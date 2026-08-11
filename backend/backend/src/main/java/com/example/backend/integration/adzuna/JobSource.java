package com.example.backend.integration.adzuna;

/** Provider boundary used by ingestion; future sources can implement the same contract. */
public interface JobSource {
    String sourceName();
    AdzunaResponse fetch(String keyword, int page);
}
