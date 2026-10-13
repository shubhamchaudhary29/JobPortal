package com.example.backend.integration.workable;

import com.example.backend.integration.aggregation.ExternalJobNormalizer;
import com.example.backend.integration.jobs.ExternalJob;
import com.example.backend.integration.jobs.JobFetchRequest;
import com.example.backend.integration.jobs.JobSource;
import com.example.backend.integration.reliability.ProviderCircuitBreaker;
import com.example.backend.integration.reliability.ProviderFailureException;
import com.example.backend.integration.reliability.ProviderHttpClient;
import com.example.backend.integration.reliability.ProviderReliabilityProperties;
import com.example.backend.integration.reliability.ProviderRequestLimiter;
import com.example.backend.integration.reliability.ProviderRetryExecutor;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/** Workable public jobs-widget endpoint for one account; see {@code docs/provider-terms.md} before enabling boards. */
@Component("workableJobSource")
public class WorkableJobSource implements JobSource {
    private final ProviderHttpClient http;
    private final ProviderRetryExecutor retries;
    private final ProviderReliabilityProperties properties;
    private final ProviderCircuitBreaker circuit;
    private final ProviderRequestLimiter limiter;
    public WorkableJobSource(ProviderHttpClient http, ProviderRetryExecutor retries,
                             ProviderReliabilityProperties properties, ProviderCircuitBreaker circuit,
                             ProviderRequestLimiter limiter) {
        this.http = http;
        this.retries = retries;
        this.properties = properties;
        this.circuit = circuit;
        this.limiter = limiter;
    }
    public String sourceName() { return "workable"; }
    public List<ExternalJob> fetch(JobFetchRequest request) { return fetchWithMetadata(request).jobs(); }
    public FetchResult fetchWithMetadata(JobFetchRequest request) {
        return fetchWithMetadata(request, () -> true);
    }
    @Override
    public FetchResult fetchWithMetadata(JobFetchRequest request, BooleanSupplier requestValid) {
        String url = UriComponentsBuilder.fromUriString(properties.baseUrl(sourceName()))
                .pathSegment(request.boardId()).queryParam("details", true).build().toUriString();
        ProviderRetryExecutor.Result<WorkableAccountResponse> result = retries.execute(requestValid, () -> circuit.execute(
                sourceName(), request.boardId(), () -> {
                    limiter.acquire(sourceName(), request.boardId(), requestValid);
                    ensureValid(requestValid);
                    return http.get(sourceName(), url, WorkableAccountResponse.class);
                }));
        WorkableAccountResponse response = result.value();
        if (response == null || response.jobs() == null) throw malformedResponse();
        if (response.jobs().size() > properties.maxItems()) throw payloadLimit();
        List<ExternalJob> output = new ArrayList<>();
        int rejected = 0;
        for (WorkableJobDto job : response.jobs()) {
            try {
                if (job == null) throw new IllegalArgumentException("null provider item");
                output.add(ExternalJobNormalizer.normalize(job.shortcode(), job.title(), job.description(),
                        request.company(), location(job), job.employmentType(),
                        job.url() != null ? job.url() : job.applicationUrl(), job.publishedOn()));
            } catch (RuntimeException malformedItem) {
                rejected++;
            }
        }
        return new FetchResult(output, result.retries(), rejected);
    }
    static String location(WorkableJobDto job) {
        if (Boolean.TRUE.equals(job.telecommuting())) return "Remote";
        List<String> parts = new ArrayList<>();
        for (String part : new String[] {job.city(), job.state(), job.country()}) {
            if (part != null && !part.isBlank() && !parts.contains(part.trim())) parts.add(part.trim());
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
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
