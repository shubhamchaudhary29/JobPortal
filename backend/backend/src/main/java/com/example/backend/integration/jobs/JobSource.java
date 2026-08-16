package com.example.backend.integration.jobs;
import java.util.List;
public interface JobSource {
    String sourceName();
    List<ExternalJob> fetch(JobFetchRequest request);
    default FetchResult fetchWithMetadata(JobFetchRequest request) { return new FetchResult(fetch(request), 0, 0); }
    record FetchResult(List<ExternalJob> jobs, int retries, int rejectedItems) {
        public FetchResult(List<ExternalJob> jobs, int retries) { this(jobs, retries, 0); }
    }
}
