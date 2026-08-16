package com.example.backend.integration.greenhouse;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component("greenhouseJobSource")
public class GreenhouseJobSource implements JobSource {
    private final ProviderHttpClient http;
    private final ProviderRetryExecutor retries;
    private final ProviderReliabilityProperties properties;
    private final ProviderCircuitBreaker circuit;
    private final ProviderRequestLimiter limiter;
    public GreenhouseJobSource(ProviderHttpClient http, ProviderRetryExecutor retries,
                               ProviderReliabilityProperties properties, ProviderCircuitBreaker circuit,
                               ProviderRequestLimiter limiter) {
        this.http = http;
        this.retries = retries;
        this.properties = properties;
        this.circuit = circuit;
        this.limiter = limiter;
    }
    public String sourceName() { return "greenhouse"; }
    public List<ExternalJob> fetch(JobFetchRequest request) { return fetchWithMetadata(request).jobs(); }
    public FetchResult fetchWithMetadata(JobFetchRequest request) {
        return fetchWithMetadata(request, () -> true);
    }
    @Override
    public FetchResult fetchWithMetadata(JobFetchRequest request, BooleanSupplier requestValid) {
        String url = UriComponentsBuilder.fromUriString(properties.greenhouseBaseUrl())
                .pathSegment(request.boardId(), "jobs").queryParam("content", true).build().toUriString();
        ProviderRetryExecutor.Result<GreenhouseJobsResponse> result = retries.execute(requestValid, () -> circuit.execute(
                sourceName(), request.boardId(), () -> {
                    limiter.acquire(sourceName(), request.boardId(), requestValid);
                    ensureValid(requestValid);
                    return http.get(sourceName(), url, GreenhouseJobsResponse.class);
                }));
        GreenhouseJobsResponse response = result.value();
        if (response == null || response.jobs() == null) throw malformedResponse();
        if (response.jobs().size() > properties.maxItems()) throw payloadLimit();
        List<ExternalJob> output = new ArrayList<>();
        int rejected = 0;
        for (GreenhouseJobDto job : response.jobs()) {
            try {
                if (job == null) throw new IllegalArgumentException("null provider item");
                output.add(ExternalJobNormalizer.normalize(job.id() == null ? null : job.id().toString(),
                        job.title(), job.content(), request.company(),
                        job.location() == null ? null : job.location().name(), employmentType(job),
                        job.absolute_url(), null));
            } catch (RuntimeException malformedItem) {
                rejected++;
            }
        }
        return new FetchResult(output, result.retries(), rejected);
    }
    private String employmentType(GreenhouseJobDto job) {
        if (job.metadata() == null) return null;
        for (GreenhouseMetadataDto metadata : job.metadata()) {
            if (metadata != null && metadata.name() != null
                    && metadata.name().toLowerCase(Locale.ROOT).contains("employment")) return metadata.value();
        }
        return null;
    }
    private ProviderFailureException payloadLimit() {
        return new ProviderFailureException(sourceName(), ProviderFailureException.Kind.PAYLOAD_LIMIT,
                false, null, null);
    }
    private ProviderFailureException malformedResponse() {
        return new ProviderFailureException(sourceName(), ProviderFailureException.Kind.MALFORMED_RESPONSE,
                false, null, null);
    }
    private void ensureValid(BooleanSupplier requestValid) {
        if (!requestValid.getAsBoolean()) {
            throw new ProviderFailureException(sourceName(), ProviderFailureException.Kind.CANCELLED,
                    false, null, null);
        }
    }
}
