package com.example.backend.integration.reliability;

import com.example.backend.shared.provider.ProviderCatalog;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ProviderReliabilityProperties {
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int maxAttempts;
    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final Map<String, String> baseUrls;
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
            Environment environment,
            @Value("${job-aggregation.providers.max-response-bytes:2097152}") int maxResponseBytes,
            @Value("${job-aggregation.providers.max-items:2000}") int maxItems,
            @Value("${job-aggregation.providers.requests-per-second:5}") int requestsPerSecond,
            @Value("${job-aggregation.providers.circuit.failure-threshold:3}") int circuitFailureThreshold,
            @Value("${job-aggregation.providers.circuit.open-ms:60000}") long circuitOpenMs) {
        this(connectTimeoutMs, readTimeoutMs, maxAttempts, initialBackoffMs, maxBackoffMs,
                configuredBaseUrls(environment), maxResponseBytes, maxItems, requestsPerSecond,
                circuitFailureThreshold, circuitOpenMs);
    }

    /** Base URLs are keyed by catalog provider name; every ATS provider must have one. */
    public ProviderReliabilityProperties(int connectTimeoutMs, int readTimeoutMs, int maxAttempts,
            long initialBackoffMs, long maxBackoffMs, Map<String, String> baseUrls, int maxResponseBytes,
            int maxItems, int requestsPerSecond, int circuitFailureThreshold, long circuitOpenMs) {
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
        Map<String, String> validatedBaseUrls = new LinkedHashMap<>();
        for (ProviderCatalog.Provider provider : ProviderCatalog.ofKind(ProviderCatalog.Kind.ATS_BOARD)) {
            String value = baseUrls.get(provider.name());
            if (value == null) throw new IllegalArgumentException("missing provider base URL");
            validateBaseUrl(value);
            validatedBaseUrls.put(provider.name(), stripTrailingSlash(value));
        }
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.maxAttempts = maxAttempts;
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
        this.baseUrls = Map.copyOf(validatedBaseUrls);
        this.maxResponseBytes = maxResponseBytes;
        this.maxItems = maxItems;
        this.requestsPerSecond = requestsPerSecond;
        this.circuitFailureThreshold = circuitFailureThreshold;
        this.circuitOpenMs = circuitOpenMs;
    }

    /** Test convenience: every ATS provider shares one base URL, then Greenhouse/Lever are overridden. */
    public ProviderReliabilityProperties(int connectTimeoutMs, int readTimeoutMs, int maxAttempts,
            long initialBackoffMs, long maxBackoffMs, String greenhouseBaseUrl, String leverBaseUrl,
            int maxResponseBytes, int maxItems, int requestsPerSecond, int circuitFailureThreshold,
            long circuitOpenMs) {
        this(connectTimeoutMs, readTimeoutMs, maxAttempts, initialBackoffMs, maxBackoffMs,
                legacyBaseUrls(greenhouseBaseUrl, leverBaseUrl), maxResponseBytes, maxItems, requestsPerSecond,
                circuitFailureThreshold, circuitOpenMs);
    }

    public ProviderReliabilityProperties(int connectTimeoutMs, int readTimeoutMs, int maxAttempts,
            long initialBackoffMs, long maxBackoffMs, String greenhouseBaseUrl, String leverBaseUrl) {
        this(connectTimeoutMs, readTimeoutMs, maxAttempts, initialBackoffMs, maxBackoffMs,
                greenhouseBaseUrl, leverBaseUrl, 2_097_152, 2_000, 5, 3, 60_000);
    }

    private static Map<String, String> configuredBaseUrls(Environment environment) {
        Map<String, String> configured = new LinkedHashMap<>();
        for (ProviderCatalog.Provider provider : ProviderCatalog.ofKind(ProviderCatalog.Kind.ATS_BOARD)) {
            configured.put(provider.name(),
                    environment.getProperty(provider.baseUrlProperty(), provider.defaultBaseUrl()));
        }
        return configured;
    }

    private static Map<String, String> legacyBaseUrls(String greenhouseBaseUrl, String leverBaseUrl) {
        Map<String, String> urls = new LinkedHashMap<>();
        for (ProviderCatalog.Provider provider : ProviderCatalog.ofKind(ProviderCatalog.Kind.ATS_BOARD)) {
            urls.put(provider.name(), greenhouseBaseUrl);
        }
        urls.put("greenhouse", greenhouseBaseUrl);
        urls.put("lever", leverBaseUrl);
        return urls;
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
    /** The configured API base URL of a catalog ATS provider, without a trailing slash. */
    public String baseUrl(String provider) {
        String value = baseUrls.get(provider);
        if (value == null) throw new IllegalArgumentException("unknown provider base URL");
        return value;
    }
    public int maxResponseBytes() { return maxResponseBytes; }
    public int maxItems() { return maxItems; }
    public int requestsPerSecond() { return requestsPerSecond; }
    public int circuitFailureThreshold() { return circuitFailureThreshold; }
    public long circuitOpenMs() { return circuitOpenMs; }
}
