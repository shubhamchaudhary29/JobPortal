package com.example.backend.integration.search;

import com.example.backend.integration.adzuna.AdzunaJobStore;
import com.example.backend.integration.adzuna.AdzunaPersistenceException;
import com.example.backend.integration.aggregation.ImportedJobLifecycleService;
import com.example.backend.integration.jobs.ExternalJob;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.shared.provider.ProviderCatalog;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared ingestion loop for keyword/location search APIs. Each plan group is fetched page by page; a failed page
 * ends its group. Every request attempt first reserves one unit of the provider's monthly budget, and an
 * exhausted budget stops the whole run as a partial result. Items go through the shared fingerprint/source-listing
 * store, and lifecycle missing state advances only after a complete, lease-valid run with no failures, rejected
 * items, or budget stop. One instance guards one provider against overlapping runs in this JVM.
 */
public class SearchProviderIngestionService {
    private static final Logger log = LoggerFactory.getLogger(SearchProviderIngestionService.class);
    private final AdzunaJobStore jobs;
    private final ImportedJobLifecycleService lifecycle;
    private final RequestBudget budget;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();

    public SearchProviderIngestionService(AdzunaJobStore jobs, ImportedJobLifecycleService lifecycle,
                                          RequestBudget budget, Clock clock) {
        this.jobs = jobs;
        this.lifecycle = lifecycle;
        this.budget = budget;
        this.clock = clock;
    }

    public SearchSyncResult sync(SearchProvider provider, BooleanSupplier leaseValid) {
        if (!running.compareAndSet(false, true)) {
            log.warn("event={}_sync_skipped reason=already_running", provider.name());
            provider.skipped();
            return SearchSyncResult.overlapRejected();
        }
        try {
            return execute(provider, leaseValid);
        } finally {
            running.set(false);
        }
    }

    private SearchSyncResult execute(SearchProvider provider, BooleanSupplier leaseValid) {
        String name = provider.name();
        long startedAt = System.nanoTime();
        int failedBatches = 0, failedItems = 0, inserted = 0, updated = 0, unchanged = 0, rejected = 0, attemptedBatches = 0;
        long lifecycleMatched = 0, lifecycleModified = 0;
        boolean budgetExhausted = false;
        AtomicInteger retries = new AtomicInteger();
        Set<String> seenIdentities = new HashSet<>();
        ingestionLoop:
        for (SearchQueryPlan.Group group : provider.plan().groups()) {
            if (!leaseValid.getAsBoolean()) break;
            for (int page = 1; page <= group.pages(); page++) {
                if (!leaseValid.getAsBoolean()) break;
                attemptedBatches++;
                try {
                    BatchResult batch = fetchBatch(provider, new SearchQuery(group.keyword(), group.location(), page),
                            leaseValid, seenIdentities, retries);
                    inserted += batch.inserted(); updated += batch.updated(); unchanged += batch.unchanged();
                    rejected += batch.rejected(); failedItems += batch.failed();
                } catch (CircuitOpen open) {
                    failedBatches++;
                    log.warn("event={}_batch_rejected provider={} reason=circuit_open", name, name);
                    break;
                } catch (SearchProviderException ex) {
                    failedBatches++;
                    provider.recordFailure();
                    log.warn("event={}_batch_failed provider={} retryable={} error={}", name, name, ex.retryable(), ex.getMessage());
                    break; // do not pretend a failed page is an empty page
                } catch (BudgetExhausted exhausted) {
                    budgetExhausted = true;
                    log.warn("event={}_budget_exhausted provider={} monthly_limit={}", name, name, provider.monthlyRequestBudget());
                    break ingestionLoop;
                } catch (LeaseLostDuringRun leaseLost) {
                    break ingestionLoop;
                }
            }
        }
        boolean leaseStillValid = leaseValid.getAsBoolean();
        if (leaseStillValid && !budgetExhausted && attemptedBatches > 0 && failedBatches == 0 && failedItems == 0
                && rejected == 0) {
            try {
                ImportedJobLifecycleService.Result lifecycleResult = lifecycle.completeSuccessfulRun(
                        name, null, seenIdentities, LocalDateTime.now(clock));
                if (lifecycleResult != null) {
                    lifecycleMatched += lifecycleResult.matchedJobs();
                    lifecycleModified += lifecycleResult.modifiedJobs();
                }
            } catch (RuntimeException lifecycleFailure) {
                failedBatches++;
                log.warn("event={}_lifecycle_failed provider={}", name, name);
            }
        }
        long elapsed = (System.nanoTime() - startedAt) / 1_000_000;
        SearchSyncResult.Outcome outcome = budgetExhausted ? SearchSyncResult.Outcome.PARTIAL_FAILURE
                : inserted + updated == 0 && (failedBatches > 0 || failedItems > 0) ? SearchSyncResult.Outcome.COMPLETE_FAILURE
                : (failedBatches > 0 || failedItems > 0 || rejected > 0 ? SearchSyncResult.Outcome.PARTIAL_FAILURE
                : SearchSyncResult.Outcome.FULL_SUCCESS);
        SearchSyncResult result = new SearchSyncResult(inserted, updated, unchanged, rejected, failedBatches, failedItems,
                retries.get(), lifecycleMatched, lifecycleModified, attemptedBatches, outcome, budgetExhausted);
        provider.completed(result, elapsed);
        log.info("event={}_sync_completed outcome={} attempted_batches={} inserted={} updated={} unchanged={} rejected={} "
                        + "failed_items={} failed_batches={} budget_exhausted={} latency_ms={}", name, outcome,
                attemptedBatches, inserted, updated, unchanged, rejected, failedItems, failedBatches, budgetExhausted, elapsed);
        return result;
    }

    private BatchResult fetchBatch(SearchProvider provider, SearchQuery query, BooleanSupplier leaseValid,
                                   Set<String> seenIdentities, AtomicInteger retries) {
        if (!leaseValid.getAsBoolean()) throw new LeaseLostDuringRun();
        if (!provider.allowRequest()) throw new CircuitOpen();
        List<ExternalJob> response = fetchWithRetry(provider, query, retries, leaseValid);
        int inserted = 0, updated = 0, unchanged = 0, rejected = 0, failed = 0;
        LocalDateTime now = LocalDateTime.now(clock);
        for (ExternalJob source : response) {
            if (!leaseValid.getAsBoolean()) break;
            if (source == null || source.externalId() == null || source.externalId().isBlank() || source.title() == null
                    || source.title().isBlank() || source.applicationUrl() == null || source.fingerprint() == null) {
                rejected++;
                continue;
            }
            JobDocument mapped = map(provider.name(), source);
            try {
                switch (jobs.upsert(mapped, now)) {
                    case INSERTED -> inserted++;
                    case UPDATED -> updated++;
                    case UNCHANGED -> unchanged++;
                }
                seenIdentities.add(mapped.getSource() + ":" + mapped.getExternalId());
            } catch (AdzunaPersistenceException ex) {
                failed++;
                log.warn("event={}_item_store_failed provider={} error={}", provider.name(), provider.name(), ex.getMessage());
            }
        }
        provider.recordSuccess();
        return new BatchResult(inserted, updated, unchanged, rejected, failed);
    }

    private List<ExternalJob> fetchWithRetry(SearchProvider provider, SearchQuery query, AtomicInteger retries,
                                             BooleanSupplier leaseValid) {
        SearchProviderException last = null;
        for (int attempt = 1; attempt <= provider.maxAttempts(); attempt++) {
            if (!leaseValid.getAsBoolean()) throw new LeaseLostDuringRun();
            if (!budget.tryConsume(provider.name(), provider.monthlyRequestBudget())) throw new BudgetExhausted();
            try {
                List<ExternalJob> response = provider.fetch(query);
                return response == null ? List.of() : response;
            } catch (SearchProviderException ex) {
                last = ex;
                if (!ex.retryable() || attempt == provider.maxAttempts()) throw ex;
                if (!leaseValid.getAsBoolean()) throw new LeaseLostDuringRun();
                retries.incrementAndGet();
                try {
                    long delay = provider.backoffMs(attempt);
                    if (ex.retryAfterMs() != null) delay = Math.max(delay, Math.min(ex.retryAfterMs(), provider.maxBackoffMs()));
                    provider.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SearchProviderException(displayName(provider) + " retry interrupted", false, interrupted);
                }
            }
        }
        throw last;
    }

    private static JobDocument map(String provider, ExternalJob source) {
        JobDocument mapped = new JobDocument();
        mapped.setSource(provider);
        mapped.setExternalId(source.externalId());
        mapped.setTitle(source.title());
        mapped.setDescription(source.description());
        mapped.setCompany(source.company());
        mapped.setLocation(source.location());
        mapped.setEmploymentType(source.employmentType());
        mapped.setSalaryMin(source.salaryMin());
        mapped.setSalaryMax(source.salaryMax());
        mapped.setSalary(source.salaryMin() == null ? 0 : source.salaryMin());
        mapped.setApplicationUrl(source.applicationUrl());
        mapped.setSourceUrl(source.applicationUrl());
        mapped.setPublishedAt(source.publishedAt());
        mapped.setExpiresAt(source.expiresAt());
        mapped.setFingerprint(source.fingerprint());
        return mapped;
    }

    private static String displayName(SearchProvider provider) {
        return ProviderCatalog.find(provider.name()).map(ProviderCatalog.Provider::displayName).orElse(provider.name());
    }

    private record BatchResult(int inserted, int updated, int unchanged, int rejected, int failed) { }
    private static final class LeaseLostDuringRun extends RuntimeException { }
    private static final class CircuitOpen extends RuntimeException { }
    private static final class BudgetExhausted extends RuntimeException { }
}
