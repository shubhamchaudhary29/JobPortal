package com.example.backend.integration.reliability;

import java.net.URI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ProviderReliabilityProperties {
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final String greenhouseBaseUrl;
    private final String leverBaseUrl;
    private final int maxResponseBytes;
    private final int maxItems;
    private final int requestsPerSecond;
    private final int circuitFailureThreshold;
    private final long circuitOpenMs;

    @Autowired
    public ProviderReliabilityProperties(
            @Value("${job-aggregation.providers.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${job-aggregation.providers.read-timeout-ms:5000}") int readTimeoutMs,
            @Value("${job-aggregation.providers.retry.max-attempts:3}") int maxAttempts,
            @Value("${job-aggregation.providers.retry.initial-backoff-ms:200}") long initialBackoffMs,
            @Value("${job-aggregation.providers.retry.max-backoff-ms:5000}") long maxBackoffMs,
            @Value("${job-aggregation.providers.greenhouse-base-url:https://boards-api.greenhouse.io/v1/boards}") String greenhouseBaseUrl,
            @Value("${job-aggregation.providers.lever-base-url:https://api.lever.co/v0/postings}") String leverBaseUrl,
            @Value("${job-aggregation.providers.max-response-bytes:2097152}") int maxResponseBytes,
            @Value("${job-aggregation.providers.max-items:2000}") int maxItems,
            @Value("${job-aggregation.providers.requests-per-second:5}") int requestsPerSecond,
            @Value("${job-aggregation.providers.circuit.failure-threshold:3}") int circuitFailureThreshold,
            @Value("${job-aggregation.providers.circuit.open-ms:60000}") long circuitOpenMs) {
        if (connectTimeoutMs < 1 || connectTimeoutMs > 60_000 || readTimeoutMs < 1 || readTimeoutMs > 120_000) {
            throw new IllegalArgumentException("provider timeouts are outside configured bounds");
        }
        if (maxAttempts < 1 || maxAttempts > 5 || initialBackoffMs < 0 || maxBackoffMs < initialBackoffMs
                || maxBackoffMs > 60_000) {
            throw new IllegalArgumentException("provider retry configuration is outside configured bounds");
        }
        if (maxResponseBytes < 1_024 || maxResponseBytes > 10_485_760 || maxItems < 1 || maxItems > 10_000) {
            throw new IllegalArgumentException("provider payload configuration is outside configured bounds");
        }
        if (requestsPerSecond < 1 || requestsPerSecond > 100 || circuitFailureThreshold < 1
                || circuitFailureThreshold > 20 || circuitOpenMs < 100 || circuitOpenMs > 600_000) {
            throw new IllegalArgumentException("provider protection configuration is outside configured bounds");
        }
        validateBaseUrl(greenhouseBaseUrl);
        validateBaseUrl(leverBaseUrl);
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
        this.greenhouseBaseUrl = stripTrailingSlash(greenhouseBaseUrl);
        this.leverBaseUrl = stripTrailingSlash(leverBaseUrl);
        this.maxResponseBytes = maxResponseBytes;
        this.maxItems = maxItems;
        this.requestsPerSecond = requestsPerSecond;
        this.circuitFailureThreshold = circuitFailureThreshold;
        this.circuitOpenMs = circuitOpenMs;
    }

    public ProviderReliabilityProperties(int connectTimeoutMs, int readTimeoutMs, int maxAttempts,
            long initialBackoffMs, long maxBackoffMs, String greenhouseBaseUrl, String leverBaseUrl) {
        this(connectTimeoutMs, readTimeoutMs, maxAttempts, initialBackoffMs, maxBackoffMs,
                greenhouseBaseUrl, leverBaseUrl, 2_097_152, 2_000, 5, 3, 60_000);
    }

    private void validateBaseUrl(String value) {
        URI uri = URI.create(value);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null) {
            throw new IllegalArgumentException("invalid provider base URL");
        }
    }
    private String stripTrailingSlash(String value) { return value.endsWith("/") ? value.substring(0, value.length() - 1) : value; }
    public int connectTimeoutMs() { return connectTimeoutMs; }
    public int readTimeoutMs() { return readTimeoutMs; }
    public int maxAttempts() { return maxAttempts; }
    public long initialBackoffMs() { return initialBackoffMs; }
    public long maxBackoffMs() { return maxBackoffMs; }
    public String greenhouseBaseUrl() { return greenhouseBaseUrl; }
    public String leverBaseUrl() { return leverBaseUrl; }
    public int maxResponseBytes() { return maxResponseBytes; }
    public int maxItems() { return maxItems; }
    public int requestsPerSecond() { return requestsPerSecond; }
    public int circuitFailureThreshold() { return circuitFailureThreshold; }
    public long circuitOpenMs() { return circuitOpenMs; }
}
