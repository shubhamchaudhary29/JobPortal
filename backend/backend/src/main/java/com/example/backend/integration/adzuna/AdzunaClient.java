package com.example.backend.integration.adzuna;

public interface AdzunaClient {
    AdzunaResponse fetchPage(String keyword, int page);
}
