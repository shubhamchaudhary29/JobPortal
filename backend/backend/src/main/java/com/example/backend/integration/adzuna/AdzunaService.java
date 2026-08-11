package com.example.backend.integration.adzuna;

import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.integration.jobs.ExternalJob;
import com.example.backend.integration.jobs.JobFetchRequest;
import com.example.backend.integration.jobs.JobSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongUnaryOperator;

@Service
public class AdzunaService {
    private static final Logger log = LoggerFactory.getLogger(AdzunaService.class);
    private final JobSource source;
    private final AdzunaJobStore jobs;
    private final AdzunaProperties properties;
    private final AdzunaCircuitBreaker circuit;
    private final AdzunaSyncMetrics metrics;
    private final Clock clock;
    private final Sleeper sleeper;
    private final LongUnaryOperator jitter;
    private final AtomicBoolean running = new AtomicBoolean();
    @Autowired
    public AdzunaService(JobSource source, AdzunaJobStore jobs, AdzunaProperties properties,
                         AdzunaCircuitBreaker circuit, AdzunaSyncMetrics metrics) {
        this(source, jobs, properties, circuit, metrics, Clock.systemUTC(), Thread::sleep,
                bound -> ThreadLocalRandom.current().nextLong(Math.max(1, bound)));
    }
    AdzunaService(JobSource source, AdzunaJobStore jobs, AdzunaProperties properties,
                  AdzunaCircuitBreaker circuit, AdzunaSyncMetrics metrics, Clock clock, Sleeper sleeper, LongUnaryOperator jitter) {
        this.source = source; this.jobs = jobs; this.properties = properties; this.circuit = circuit;
        this.metrics = metrics; this.clock = clock; this.sleeper = sleeper; this.jitter = jitter;
    }

    public SyncResult sync() {
        if (!running.compareAndSet(false, true)) {
            log.warn("event=adzuna_sync_skipped reason=already_running");
            metrics.record(AdzunaSyncMetrics.Outcome.REJECTED, 0);
            return new SyncResult(0, 0, 0, 0, 0, 0, Outcome.OVERLAP_REJECTED);
        }
        long startedAt = System.nanoTime();
        int failedBatches = 0, failedItems = 0, inserted = 0, updated = 0, unchanged = 0, rejected = 0, attemptedBatches = 0;
        try {
            for (String rawKeyword : properties.keywords()) {
                String keyword = rawKeyword.trim();
                if (keyword.isEmpty()) continue;
                for (int page = 1; page <= properties.pagesPerKeyword(); page++) {
                    attemptedBatches++;
                    try {
                        BatchResult batch = fetchKeyword(keyword, page);
                        inserted += batch.inserted(); updated += batch.updated(); unchanged += batch.unchanged(); rejected += batch.rejected(); failedItems += batch.failed();
                    } catch (AdzunaCircuitOpenException ex) {
                        failedBatches++;
                        log.warn("event=adzuna_batch_rejected provider=adzuna reason=circuit_open");
                        break;
                    } catch (AdzunaProviderException ex) {
                        failedBatches++; circuit.recordFailure();
                        log.warn("event=adzuna_batch_failed provider=adzuna retryable={} error={}", ex.retryable(), ex.getMessage());
                        break; // do not pretend a failed page is an empty page
                    }
                }
            }
        } finally { running.set(false); }
        long elapsed = (System.nanoTime() - startedAt) / 1_000_000;
        Outcome outcome = inserted + updated == 0 && (failedBatches > 0 || failedItems > 0) ? Outcome.COMPLETE_FAILURE
                : (failedBatches > 0 || failedItems > 0 || rejected > 0 ? Outcome.PARTIAL_FAILURE : Outcome.FULL_SUCCESS);
        metrics.record(outcome == Outcome.FULL_SUCCESS ? AdzunaSyncMetrics.Outcome.FULL_SUCCESS : outcome == Outcome.PARTIAL_FAILURE ? AdzunaSyncMetrics.Outcome.PARTIAL_FAILURE : AdzunaSyncMetrics.Outcome.COMPLETE_FAILURE, elapsed);
        AdzunaSyncMetrics.Snapshot snapshot = metrics.snapshot();
        log.info("event=adzuna_sync_completed outcome={} attempted_batches={} inserted={} updated={} unchanged={} rejected={} failed_items={} failed_batches={} latency_ms={}", outcome, attemptedBatches, inserted, updated, unchanged, rejected, failedItems, failedBatches, elapsed);
        return new SyncResult(inserted, updated, unchanged, rejected, failedBatches, failedItems, outcome);
    }
    private BatchResult fetchKeyword(String keyword, int page) {
        if (!circuit.allowRequest()) throw new AdzunaCircuitOpenException();
        java.util.List<ExternalJob> response = fetchWithRetry(keyword, page);
        int inserted = 0, updated = 0, unchanged = 0, rejected = 0, failed = 0;
        LocalDateTime now = LocalDateTime.now(clock);
        for (ExternalJob source : response) {
            if (source.externalId() == null || source.externalId().isBlank() || source.title() == null || source.title().isBlank()
                    || source.applicationUrl() == null || source.fingerprint() == null) { rejected++; continue; }
            JobDocument mapped = new JobDocument(); mapped.setSource(this.source.sourceName()); mapped.setExternalId(source.externalId());
            mapped.setTitle(source.title()); mapped.setDescription(source.description()); mapped.setCompany(source.company()); mapped.setLocation(source.location()); mapped.setEmploymentType(source.employmentType()); mapped.setSalaryMin(source.salaryMin()); mapped.setSalaryMax(source.salaryMax()); mapped.setSalary(source.salaryMin() == null ? 0 : source.salaryMin()); mapped.setApplicationUrl(source.applicationUrl()); mapped.setSourceUrl(source.applicationUrl()); mapped.setPublishedAt(source.publishedAt()); mapped.setExpiresAt(source.expiresAt()); mapped.setFingerprint(source.fingerprint());
            try { switch (jobs.upsert(mapped, now)) { case INSERTED -> inserted++; case UPDATED -> updated++; case UNCHANGED -> unchanged++; } }
            catch (AdzunaPersistenceException ex) { failed++; log.warn("event=adzuna_item_store_failed provider=adzuna error={}", ex.getMessage()); }
        }
        circuit.recordSuccess();
        return new BatchResult(inserted, updated, unchanged, rejected, failed);
    }
    private java.util.List<ExternalJob> fetchWithRetry(String keyword, int page) {
        AdzunaProviderException last = null;
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            try { return source.fetch(new JobFetchRequest(keyword, page)); }
            catch (AdzunaProviderException ex) {
                last = ex;
                if (!ex.retryable() || attempt == properties.maxAttempts()) throw ex;
                try { sleeper.sleep(backoff(attempt)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AdzunaProviderException("Adzuna retry interrupted", false, interrupted); }
            }
        }
        throw last;
    }
    private long backoff(int attempt) { long base = properties.initialBackoffMs() * (1L << (attempt - 1)); return base + jitter.applyAsLong(Math.max(1, base / 2)); }
    record BatchResult(int inserted, int updated, int unchanged, int rejected, int failed) { }
    public enum Outcome { FULL_SUCCESS, PARTIAL_FAILURE, COMPLETE_FAILURE, OVERLAP_REJECTED }
    public record SyncResult(int inserted, int updated, int unchanged, int rejected, int failedBatches, int failedItems, Outcome outcome) {
        public boolean skipped() { return outcome == Outcome.OVERLAP_REJECTED; }
    }
    @FunctionalInterface interface Sleeper { void sleep(long millis) throws InterruptedException; }
}
