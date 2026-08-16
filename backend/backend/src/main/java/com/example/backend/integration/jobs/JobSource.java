package com.example.backend.integration.jobs;
import java.util.List;
import java.util.function.BooleanSupplier;
public interface JobSource {
    String sourceName();
    List<ExternalJob> fetch(JobFetchRequest request);
    default FetchResult fetchWithMetadata(JobFetchRequest request) { return new FetchResult(fetch(request), 0, 0); }
    default FetchResult fetchWithMetadata(JobFetchRequest request, BooleanSupplier requestValid) {
        if (!requestValid.getAsBoolean()) throw new IllegalStateException("Provider request was cancelled");
        return fetchWithMetadata(request);
    }
    record FetchResult(List<ExternalJob> jobs, int retries, int rejectedItems) {
        public FetchResult(List<ExternalJob> jobs, int retries) { this(jobs, retries, 0); }
    }
}
