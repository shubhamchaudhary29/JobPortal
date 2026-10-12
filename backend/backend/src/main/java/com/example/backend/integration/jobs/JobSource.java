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
    /**
     * @param complete false when the source deliberately returned only part of the board (page cap, a failed
     *                 later page, or deferred detail requests); lifecycle missing state must not advance
     */
    record FetchResult(List<ExternalJob> jobs, int retries, int rejectedItems, boolean complete) {
        public FetchResult(List<ExternalJob> jobs, int retries, int rejectedItems) { this(jobs, retries, rejectedItems, true); }
        public FetchResult(List<ExternalJob> jobs, int retries) { this(jobs, retries, 0, true); }
    }
}
