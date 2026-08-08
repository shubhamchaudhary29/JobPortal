package com.example.backend.integration.adzuna;

import com.example.backend.job.infrastructure.JobDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
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
    private final AdzunaClient client;
    private final AdzunaJobStore jobs;
    private final AdzunaProperties properties;
    private final AdzunaCircuitBreaker circuit;
    private final AdzunaSyncMetrics metrics;
    private final Clock clock;
    private final Sleeper sleeper;
    private final LongUnaryOperator jitter;
    private final AtomicBoolean running = new AtomicBoolean();
    @Autowired
    public AdzunaService(AdzunaClient client, AdzunaJobStore jobs, AdzunaProperties properties,
                         AdzunaCircuitBreaker circuit, AdzunaSyncMetrics metrics) {
        this(client, jobs, properties, circuit, metrics, Clock.systemUTC(), Thread::sleep,
                bound -> ThreadLocalRandom.current().nextLong(Math.max(1, bound)));
    }
    AdzunaService(AdzunaClient client, AdzunaJobStore jobs, AdzunaProperties properties,
                  AdzunaCircuitBreaker circuit, AdzunaSyncMetrics metrics, Clock clock, Sleeper sleeper, LongUnaryOperator jitter) {
        this.client = client; this.jobs = jobs; this.properties = properties; this.circuit = circuit;
        this.metrics = metrics; this.clock = clock; this.sleeper = sleeper; this.jitter = jitter;
    }

    @Scheduled(cron = "${adzuna.schedule.cron:0 0 */6 * * *}")
    public void fetchAndSaveJobs() { sync(); }
    public SyncResult sync() {
        if (!running.compareAndSet(false, true)) {
            log.warn("event=adzuna_sync_skipped reason=already_running");
            return new SyncResult(0, 0, 0, true);
        }
        long startedAt = System.nanoTime();
        int failedBatches = 0, imported = 0, rejected = 0;
        try {
            for (String rawKeyword : properties.keywords()) {
                String keyword = rawKeyword.trim();
                if (keyword.isEmpty()) continue;
                for (int page = 1; page <= properties.pagesPerKeyword(); page++) {
                    try {
                        BatchResult batch = fetchKeyword(keyword, page);
                        imported += batch.imported(); rejected += batch.rejected();
                    } catch (AdzunaProviderException ex) {
                        failedBatches++; metrics.failure(); circuit.recordFailure();
                        log.warn("event=adzuna_batch_failed provider=adzuna retryable={} error={}", ex.retryable(), ex.getMessage());
                        break; // do not pretend a failed page is an empty page
                    }
                }
            }
        } finally { running.set(false); }
        long elapsed = (System.nanoTime() - startedAt) / 1_000_000;
        metrics.success(elapsed);
        AdzunaSyncMetrics.Snapshot snapshot = metrics.snapshot();
        log.info("event=adzuna_sync_completed imported={} rejected={} failed_batches={} latency_ms={} success_total={} failure_total={}",
                imported, rejected, failedBatches, elapsed, snapshot.successes(), snapshot.failures());
        return new SyncResult(imported, rejected, failedBatches, false);
    }
    private BatchResult fetchKeyword(String keyword, int page) {
        if (!circuit.allowRequest()) throw new AdzunaProviderException("Adzuna circuit is open", false, null);
        AdzunaResponse response = fetchWithRetry(keyword, page);
        int imported = 0, rejected = 0;
        LocalDateTime now = LocalDateTime.now(clock);
        if (response.results() == null) throw new AdzunaProviderException("Adzuna response is missing results", false, null);
        for (AdzunaResponse.AdzunaJob source : response.results()) {
            var mapped = AdzunaJobMapper.toDocument(source, now);
            if (mapped.isEmpty()) { rejected++; continue; }
            jobs.upsert(mapped.get(), now); imported++;
        }
        circuit.recordSuccess();
        return new BatchResult(imported, rejected);
    }
    private AdzunaResponse fetchWithRetry(String keyword, int page) {
        AdzunaProviderException last = null;
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            try { return client.fetchPage(keyword, page); }
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
    record BatchResult(int imported, int rejected) { }
    public record SyncResult(int imported, int rejected, int failedBatches, boolean skipped) { }
    @FunctionalInterface interface Sleeper { void sleep(long millis) throws InterruptedException; }
}
