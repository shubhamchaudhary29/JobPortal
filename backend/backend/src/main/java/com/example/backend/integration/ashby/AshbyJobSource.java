package com.example.backend.integration.ashby;

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

/**
 * Ashby public job-posting API. Unlisted postings (direct-link only) are skipped. Compensation is mapped only
 * for an annual salary in INR, because jobs carry no currency and salaries are displayed in rupees.
 */
@Component("ashbyJobSource")
public class AshbyJobSource implements JobSource {
    private final ProviderHttpClient http;
    private final ProviderRetryExecutor retries;
    private final ProviderReliabilityProperties properties;
    private final ProviderCircuitBreaker circuit;
    private final ProviderRequestLimiter limiter;
    public AshbyJobSource(ProviderHttpClient http, ProviderRetryExecutor retries,
                          ProviderReliabilityProperties properties, ProviderCircuitBreaker circuit,
                          ProviderRequestLimiter limiter) {
        this.http = http;
        this.retries = retries;
        this.properties = properties;
        this.circuit = circuit;
        this.limiter = limiter;
    }
    public String sourceName() { return "ashby"; }
    public List<ExternalJob> fetch(JobFetchRequest request) { return fetchWithMetadata(request).jobs(); }
    public FetchResult fetchWithMetadata(JobFetchRequest request) {
        return fetchWithMetadata(request, () -> true);
    }
    @Override
    public FetchResult fetchWithMetadata(JobFetchRequest request, BooleanSupplier requestValid) {
        String url = UriComponentsBuilder.fromUriString(properties.baseUrl(sourceName()))
                .pathSegment(request.boardId()).queryParam("includeCompensation", true).build().toUriString();
        ProviderRetryExecutor.Result<AshbyJobBoardResponse> result = retries.execute(requestValid, () -> circuit.execute(
                sourceName(), request.boardId(), () -> {
                    limiter.acquire(sourceName(), request.boardId(), requestValid);
                    ensureValid(requestValid);
                    return http.get(sourceName(), url, AshbyJobBoardResponse.class);
                }));
        AshbyJobBoardResponse response = result.value();
        if (response == null || response.jobs() == null) throw malformedResponse();
        if (response.jobs().size() > properties.maxItems()) throw payloadLimit();
        List<ExternalJob> output = new ArrayList<>();
        int rejected = 0;
        for (AshbyJobDto job : response.jobs()) {
            try {
                if (job == null) throw new IllegalArgumentException("null provider item");
                if (Boolean.FALSE.equals(job.isListed())) continue;
                Double[] salary = annualInrSalary(job.compensation());
                output.add(ExternalJobNormalizer.normalize(job.id(), job.title(),
                        job.descriptionHtml() != null ? job.descriptionHtml() : job.descriptionPlain(),
                        request.company(), job.location(), employmentType(job.employmentType()),
                        job.jobUrl() != null ? job.jobUrl() : job.applyUrl(), job.publishedAt(),
                        salary[0], salary[1]));
            } catch (RuntimeException malformedItem) {
                rejected++;
            }
        }
        return new FetchResult(output, result.retries(), rejected);
    }
    static String employmentType(String value) {
        if (value == null) return null;
        return switch (value) {
            case "FullTime" -> "Full-time";
            case "PartTime" -> "Part-time";
            case "Intern" -> "Internship";
            default -> value;
        };
    }
    private static Double[] annualInrSalary(AshbyCompensationDto compensation) {
        if (compensation == null || compensation.summaryComponents() == null) return new Double[2];
        for (AshbyCompensationComponentDto component : compensation.summaryComponents()) {
            if (component != null && "Salary".equals(component.compensationType())
                    && "1 YEAR".equals(component.interval()) && "INR".equals(component.currencyCode())
                    && (component.minValue() != null || component.maxValue() != null)) {
                return new Double[] {component.minValue(), component.maxValue()};
            }
        }
        return new Double[2];
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
