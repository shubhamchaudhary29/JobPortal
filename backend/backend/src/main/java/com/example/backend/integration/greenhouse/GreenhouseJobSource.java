package com.example.backend.integration.greenhouse;

import com.example.backend.integration.aggregation.ExternalJobNormalizer;
import com.example.backend.integration.jobs.ExternalJob;
import com.example.backend.integration.jobs.JobFetchRequest;
import com.example.backend.integration.jobs.JobSource;
import com.example.backend.integration.reliability.ProviderHttpClient;
import com.example.backend.integration.reliability.ProviderReliabilityProperties;
import com.example.backend.integration.reliability.ProviderRetryExecutor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component("greenhouseJobSource")
public class GreenhouseJobSource implements JobSource {
    private final ProviderHttpClient http;
    private final ProviderRetryExecutor retries;
    private final ProviderReliabilityProperties properties;
    public GreenhouseJobSource(ProviderHttpClient http, ProviderRetryExecutor retries,
                               ProviderReliabilityProperties properties) {
        this.http = http;
        this.retries = retries;
        this.properties = properties;
    }
    public String sourceName() { return "greenhouse"; }
    public List<ExternalJob> fetch(JobFetchRequest request) { return fetchWithMetadata(request).jobs(); }
    public FetchResult fetchWithMetadata(JobFetchRequest request) {
        String url = UriComponentsBuilder.fromUriString(properties.greenhouseBaseUrl())
                .pathSegment(request.boardId(), "jobs").queryParam("content", true).build().toUriString();
        ProviderRetryExecutor.Result<GreenhouseJobsResponse> result = retries.execute(
                () -> http.get(sourceName(), url, GreenhouseJobsResponse.class));
        GreenhouseJobsResponse response = result.value();
        if (response == null || response.jobs() == null) return new FetchResult(List.of(), result.retries());
        List<ExternalJob> output = new ArrayList<>();
        for (GreenhouseJobDto job : response.jobs()) {
            output.add(ExternalJobNormalizer.normalize(job.id() == null ? null : job.id().toString(),
                    job.title(), job.content(), request.company(), job.location() == null ? null : job.location().name(),
                    employmentType(job), job.absolute_url(), null));
        }
        return new FetchResult(output, result.retries());
    }
    private String employmentType(GreenhouseJobDto job) {
        if (job.metadata() == null) return null;
        for (GreenhouseMetadataDto metadata : job.metadata()) {
            if (metadata != null && metadata.name() != null
                    && metadata.name().toLowerCase(Locale.ROOT).contains("employment")) return metadata.value();
        }
        return null;
    }
}
