package com.example.backend.integration.jooble;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Jooble REST API settings. The key is regional (an {@code in.jooble.org} key returns Indian listings) and comes
 * only from {@code JOOBLE_API_KEY}; without it the provider is disabled. The key is part of the request path and
 * must never be logged.
 */
@Component
public class JoobleProperties {
    private final String apiKey;
    private final String baseUrl;
    private final String[] keywords;
    private final String location;
    private final int pagesPerKeyword;
    private final int resultsPerPage;
    private final int monthlyRequestBudget;

    public JoobleProperties(@Value("${jooble.api-key:}") String apiKey,
                            @Value("${jooble.base-url:https://in.jooble.org/api}") String baseUrl,
                            @Value("${jooble.keywords:software engineer,data analyst,product manager}") String keywords,
                            @Value("${jooble.location:India}") String location,
                            @Value("${jooble.pages-per-keyword:1}") int pagesPerKeyword,
                            @Value("${jooble.results-per-page:20}") int resultsPerPage,
                            @Value("${jooble.monthly-request-budget:100}") int monthlyRequestBudget) {
        URI uri = URI.create(baseUrl);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null) {
            throw new IllegalArgumentException("invalid Jooble base URL");
        }
        if (pagesPerKeyword < 1 || pagesPerKeyword > 10 || resultsPerPage < 1 || resultsPerPage > 100
                || monthlyRequestBudget < 1 || monthlyRequestBudget > 1_000_000) {
            throw new IllegalArgumentException("Jooble request bounds are outside configured limits");
        }
        if (apiKey != null && !apiKey.isBlank() && !apiKey.trim().matches("[A-Za-z0-9-]{8,128}")) {
            throw new IllegalArgumentException("JOOBLE_API_KEY has an unexpected format");
        }
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.keywords = keywords.split(",");
        this.location = location == null || location.isBlank() ? "India" : location.trim();
        this.pagesPerKeyword = pagesPerKeyword;
        this.resultsPerPage = resultsPerPage;
        this.monthlyRequestBudget = monthlyRequestBudget;
    }

    public boolean enabled() { return !apiKey.isEmpty(); }
    String apiKey() { return apiKey; }
    public String baseUrl() { return baseUrl; }
    public String[] keywords() { return keywords.clone(); }
    public String location() { return location; }
    public int pagesPerKeyword() { return pagesPerKeyword; }
    public int resultsPerPage() { return resultsPerPage; }
    public int monthlyRequestBudget() { return monthlyRequestBudget; }

    /** Never prints the key. */
    @Override public String toString() { return "JoobleProperties[enabled=" + enabled() + ", baseUrl=" + baseUrl + "]"; }
}
