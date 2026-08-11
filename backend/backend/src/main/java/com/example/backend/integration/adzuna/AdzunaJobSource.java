package com.example.backend.integration.adzuna;

import org.springframework.stereotype.Component;

@Component
public class AdzunaJobSource implements JobSource {
    private final AdzunaClient client;
    public AdzunaJobSource(AdzunaClient client) { this.client = client; }
    @Override public String sourceName() { return "adzuna"; }
    @Override public AdzunaResponse fetch(String keyword, int page) { return client.fetchPage(keyword, page); }
}
