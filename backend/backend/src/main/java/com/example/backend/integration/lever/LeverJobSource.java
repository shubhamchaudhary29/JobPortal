package com.example.backend.integration.lever;

import com.example.backend.integration.aggregation.ExternalJobNormalizer;
import com.example.backend.integration.jobs.ExternalJob;
import com.example.backend.integration.jobs.JobFetchRequest;
import com.example.backend.integration.jobs.JobSource;
import com.example.backend.integration.reliability.ProviderHttpClient;
import com.example.backend.integration.reliability.ProviderCircuitBreaker;
import com.example.backend.integration.reliability.ProviderFailureException;
import com.example.backend.integration.reliability.ProviderReliabilityProperties;
import com.example.backend.integration.reliability.ProviderRequestLimiter;
import com.example.backend.integration.reliability.ProviderRetryExecutor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component("leverJobSource")
public class LeverJobSource implements JobSource {
    private static final ParameterizedTypeReference<List<LeverJobDto>> RESPONSE = new ParameterizedTypeReference<>() { };
    private final ProviderHttpClient http;
    private final ProviderRetryExecutor retries;
    private final ProviderReliabilityProperties properties;
    private final ProviderCircuitBreaker circuit;
    private final ProviderRequestLimiter limiter;
    public LeverJobSource(ProviderHttpClient http, ProviderRetryExecutor retries,
                          ProviderReliabilityProperties properties, ProviderCircuitBreaker circuit,
                          ProviderRequestLimiter limiter) {
        this.http = http;
        this.retries = retries;
        this.properties = properties;
        this.circuit = circuit;
        this.limiter = limiter;
    }
    public String sourceName() { return "lever"; }
    public List<ExternalJob> fetch(JobFetchRequest request) { return fetchWithMetadata(request).jobs(); }
    public FetchResult fetchWithMetadata(JobFetchRequest request) {
        String url = UriComponentsBuilder.fromUriString(properties.leverBaseUrl())
                .pathSegment(request.boardId()).queryParam("mode", "json").build().toUriString();
        ProviderRetryExecutor.Result<List<LeverJobDto>> result = retries.execute(() -> circuit.execute(
                sourceName(), request.boardId(), () -> {
                    limiter.acquire(sourceName(), request.boardId());
                    return http.get(sourceName(), url, RESPONSE);
                }));
        if (result.value() == null) return new FetchResult(List.of(), result.retries(), 0);
        if (result.value().size() > properties.maxItems()) throw payloadLimit();
        List<ExternalJob> output = new ArrayList<>();
        int rejected = 0;
        for (LeverJobDto job : result.value()) {
            try {
                if (job == null) throw new IllegalArgumentException("null provider item");
                output.add(ExternalJobNormalizer.normalize(job.id(), job.text(),
                        job.descriptionPlain() != null ? job.descriptionPlain() : job.description(), request.company(),
                        job.categories() == null ? null : job.categories().location(),
                        job.categories() == null ? null : job.categories().commitment(), job.hostedUrl(),
                        job.createdAt() == null ? null : Instant.ofEpochMilli(job.createdAt()).toString()));
            } catch (RuntimeException malformedItem) {
                rejected++;
            }
        }
        return new FetchResult(output, result.retries(), rejected);
    }
    private ProviderFailureException payloadLimit() {
        return new ProviderFailureException(sourceName(), ProviderFailureException.Kind.PAYLOAD_LIMIT,
                false, null, null);
    }
}
