package com.example.backend.integration.adzuna;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AdzunaProperties {
    private final String appId;
    private final String appKey;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final int circuitFailureThreshold;
    private final long circuitOpenMs;
    private final int pagesPerKeyword;
    private final int resultsPerPage;
    private final String[] keywords;

    public AdzunaProperties(@Value("${adzuna.app.id}") String appId,
                            @Value("${adzuna.app.key}") String appKey,
                            @Value("${adzuna.connect-timeout-ms:3000}") int connectTimeoutMs,
                            @Value("${adzuna.read-timeout-ms:5000}") int readTimeoutMs,
                            @Value("${adzuna.retry.max-attempts:3}") int maxAttempts,
                            @Value("${adzuna.retry.initial-backoff-ms:200}") long initialBackoffMs,
                            @Value("${adzuna.circuit.failure-threshold:3}") int circuitFailureThreshold,
                            @Value("${adzuna.circuit.open-ms:60000}") long circuitOpenMs,
                            @Value("${adzuna.pages-per-keyword:2}") int pagesPerKeyword,
                            @Value("${adzuna.results-per-page:20}") int resultsPerPage,
                            @Value("${adzuna.keywords:java developer,python developer,react developer,data analyst,backend engineer,frontend developer,full stack developer,machine learning engineer}") String keywords) {
        this.appId = required(appId, "ADZUNA_APP_ID");
        this.appKey = required(appKey, "ADZUNA_APP_KEY");
        this.connectTimeoutMs = positive(connectTimeoutMs, "connect timeout");
        this.readTimeoutMs = positive(readTimeoutMs, "read timeout");
        this.maxAttempts = bounded(maxAttempts, 1, 5, "max attempts");
        this.initialBackoffMs = positive(initialBackoffMs, "initial backoff");
        this.circuitFailureThreshold = bounded(circuitFailureThreshold, 1, 20, "circuit threshold");
        this.circuitOpenMs = positive(circuitOpenMs, "circuit open duration");
        this.pagesPerKeyword = bounded(pagesPerKeyword, 1, 10, "pages per keyword");
        this.resultsPerPage = bounded(resultsPerPage, 1, 50, "results per page");
        this.keywords = keywords.split(",");
    }
    public String appId() { return appId; }
    public String appKey() { return appKey; }
    public int connectTimeoutMs() { return connectTimeoutMs; }
    public int readTimeoutMs() { return readTimeoutMs; }
    public int maxAttempts() { return maxAttempts; }
    public long initialBackoffMs() { return initialBackoffMs; }
    public int circuitFailureThreshold() { return circuitFailureThreshold; }
    public long circuitOpenMs() { return circuitOpenMs; }
    public int pagesPerKeyword() { return pagesPerKeyword; }
    public int resultsPerPage() { return resultsPerPage; }
    public String[] keywords() { return keywords.clone(); }
    private static String required(String value, String name) { if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must be configured"); return value; }
    private static int positive(int value, String name) { if (value < 1) throw new IllegalArgumentException(name + " must be positive"); return value; }
    private static long positive(long value, String name) { if (value < 1) throw new IllegalArgumentException(name + " must be positive"); return value; }
    private static int bounded(int value, int min, int max, String name) { if (value < min || value > max) throw new IllegalArgumentException(name + " must be between " + min + " and " + max); return value; }
}
