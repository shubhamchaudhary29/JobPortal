package com.example.backend.integration.jooble;

import com.example.backend.integration.aggregation.ExternalJobNormalizer;
import com.example.backend.integration.jobs.ExternalJob;
import com.example.backend.integration.reliability.ProviderCircuitBreaker;
import com.example.backend.integration.reliability.ProviderFailureException;
import com.example.backend.integration.reliability.ProviderHttpClient;
import com.example.backend.integration.reliability.ProviderReliabilityProperties;
import com.example.backend.integration.reliability.ProviderRequestLimiter;
import com.example.backend.integration.search.SearchProvider;
import com.example.backend.integration.search.SearchProviderException;
import com.example.backend.integration.search.SearchQuery;
import com.example.backend.integration.search.SearchQueryPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;

/**
 * Jooble keyword × location search on the shared HTTP client, circuit, rate limiter, and payload limits. Jooble's
 * own redirect {@code link} is the application URL. Postings without a company are skipped (not rejected): they
 * cannot be attributed or de-duplicated. Failures carry only the provider and failure kind, never the URL, so the
 * API key in the request path cannot reach logs or history.
 */
public class JoobleSearchProvider implements SearchProvider {
    static final String BOARD = "search";
    private final JoobleProperties properties;
    private final ProviderReliabilityProperties reliability;
    private final ProviderHttpClient http;
    private final ProviderCircuitBreaker circuit;
    private final ProviderRequestLimiter limiter;
    private final Sleeper sleeper;
    private final LongUnaryOperator jitter;

    public JoobleSearchProvider(JoobleProperties properties, ProviderReliabilityProperties reliability,
                                ProviderHttpClient http, ProviderCircuitBreaker circuit, ProviderRequestLimiter limiter) {
        this(properties, reliability, http, circuit, limiter, Thread::sleep,
                bound -> ThreadLocalRandom.current().nextLong(Math.max(1, bound)));
    }

    public JoobleSearchProvider(JoobleProperties properties, ProviderReliabilityProperties reliability, ProviderHttpClient http,
                         ProviderCircuitBreaker circuit, ProviderRequestLimiter limiter, Sleeper sleeper,
                         LongUnaryOperator jitter) {
        this.properties = properties;
        this.reliability = reliability;
        this.http = http;
        this.circuit = circuit;
        this.limiter = limiter;
        this.sleeper = sleeper;
        this.jitter = jitter;
    }

    @Override public String name() { return "jooble"; }
    @Override public SearchQueryPlan plan() {
        return SearchQueryPlan.keywords(properties.keywords(), properties.location(), properties.pagesPerKeyword());
    }

    @Override
    public List<ExternalJob> fetch(SearchQuery query) {
        if (!properties.enabled()) throw new SearchProviderException("Jooble is not configured", false, null);
        JoobleResponse response;
        try {
            response = circuit.execute(name(), BOARD, () -> {
                limiter.acquire(name(), BOARD);
                return http.post(name(), properties.baseUrl() + "/" + properties.apiKey(),
                        new JoobleRequest(query.keyword(), query.location(), String.valueOf(query.page()),
                                properties.resultsPerPage(), "false"), JoobleResponse.class);
            });
        } catch (ProviderFailureException failure) {
            throw new SearchProviderException(failure.getMessage(), failure.retryable(),
                    failure.retryAfter() == null ? null : failure.retryAfter().toMillis(), null);
        }
        if (response == null || response.jobs() == null) {
            throw new SearchProviderException("jooble provider request failed: malformed_response", false, null);
        }
        if (response.jobs().size() > reliability.maxItems()) {
            throw new SearchProviderException("jooble provider request failed: payload_limit", false, null);
        }
        List<ExternalJob> output = new ArrayList<>();
        for (JoobleJobDto job : response.jobs()) {
            if (job == null) {
                output.add(null);
                continue;
            }
            if (job.company() == null || job.company().isBlank()) continue;
            Double[] salary = JoobleSalaryParser.parse(job.salary());
            output.add(ExternalJobNormalizer.normalize(job.id(), job.title(), job.snippet(), job.company(),
                    job.location(), job.type(), job.link(), job.updated(), salary[0], salary[1]));
        }
        return output;
    }

    @Override public int maxAttempts() { return reliability.maxAttempts(); }
    @Override public long backoffMs(int attempt) {
        long base = Math.min(reliability.maxBackoffMs(), reliability.initialBackoffMs() * (1L << Math.min(20, attempt - 1)));
        return Math.min(reliability.maxBackoffMs(), base + jitter.applyAsLong(Math.max(1, base / 2)));
    }
    @Override public long maxBackoffMs() { return reliability.maxBackoffMs(); }
    @Override public void sleep(long millis) throws InterruptedException { sleeper.sleep(millis); }
    /** The shared circuit is checked inside {@link #fetch}; an open circuit fails the page without a request. */
    @Override public boolean allowRequest() { return true; }
    @Override public void recordSuccess() { }
    @Override public void recordFailure() { }
    @Override public int monthlyRequestBudget() { return properties.monthlyRequestBudget(); }

    @FunctionalInterface public interface Sleeper { void sleep(long millis) throws InterruptedException; }
}
