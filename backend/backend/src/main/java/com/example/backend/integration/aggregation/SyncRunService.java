package com.example.backend.integration.aggregation;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import com.example.backend.shared.error.BadRequestException;
import com.example.backend.shared.error.ResourceNotFoundException;
import com.example.backend.shared.pagination.PageResponse;

@Service
public class SyncRunService {
    private static final Logger log = LoggerFactory.getLogger(SyncRunService.class);
    private static final int MAX_FAILURE_DETAIL = 240;
    private static final Pattern URL = Pattern.compile("(?i)https?://\\S+");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(app[_-]?key|api[_-]?key|token|secret|password|authorization)(\\s*[:=]\\s*)[^\\s,;]+");
    private final MongoTemplate mongo;
    private final Clock clock;
    private final int retentionDays;

    public SyncRunService(MongoTemplate mongo, Clock clock,
            @Value("${job-aggregation.sync-history.retention-days:30}") int retentionDays) {
        if (retentionDays < 1 || retentionDays > 3650) {
            throw new IllegalArgumentException("sync history retention must be between 1 and 3650 days");
        }
        this.mongo = mongo;
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    public Handle begin(String provider, String employer, Trigger trigger) {
        Instant startedAt = clock.instant();
        String runId = UUID.randomUUID().toString();
        SyncRunDocument run = new SyncRunDocument();
        run.setId(runId);
        run.setRunId(runId);
        run.setProvider(provider.toLowerCase(Locale.ROOT));
        run.setEmployer(blankToNull(employer));
        run.setTrigger(trigger);
        run.setStartedAt(startedAt);
        run.setOutcome(Outcome.RUNNING);
        run.setExpiresAt(startedAt.plus(retentionDays, ChronoUnit.DAYS));
        mongo.insert(run);
        log.info("event=aggregation_sync_started run_id={} provider={} trigger={}",
                runId, run.getProvider(), trigger);
        return new Handle(runId, startedAt);
    }

    public void finish(Handle handle, Outcome outcome, Counts counts, Throwable failure) {
        Instant completedAt = clock.instant();
        Failure safeFailure = sanitize(failure);
        Update update = new Update()
                .set("completedAt", completedAt)
                .set("outcome", outcome)
                .set("inserted", counts.inserted())
                .set("updated", counts.updated())
                .set("unchanged", counts.unchanged())
                .set("rejected", counts.rejected())
                .set("failedItems", counts.failedItems())
                .set("failedBatches", counts.failedBatches())
                .set("failedEmployers", counts.failedEmployers())
                .set("attemptedBatches", counts.attemptedBatches())
                .set("attemptedEmployers", counts.attemptedEmployers())
                .set("lifecycleMatched", counts.lifecycleMatched())
                .set("lifecycleModified", counts.lifecycleModified())
                .set("retries", counts.retries());
        if (safeFailure == null) {
            update.unset("failureType").unset("failureDetail");
        } else {
            update.set("failureType", safeFailure.type()).set("failureDetail", safeFailure.detail());
        }
        long modified = mongo.updateFirst(Query.query(new Criteria().andOperator(
                        Criteria.where("_id").is(handle.runId()), Criteria.where("outcome").is(Outcome.RUNNING))),
                update, SyncRunDocument.class).getModifiedCount();
        if (modified != 1) throw new IllegalStateException("Sync run is missing or already completed");
        log.info("event=aggregation_sync_completed run_id={} outcome={} duration_ms={}",
                handle.runId(), outcome, Math.max(0, completedAt.toEpochMilli() - handle.startedAt().toEpochMilli()));
    }

    public PageResponse<RunView> history(String provider, String employer, String outcome, String trigger,
                                         int page, int size) {
        validatePage(page, size);
        Criteria criteria = filters(provider, employer, outcome, trigger);
        Sort sort = Sort.by(Sort.Order.desc("startedAt"), Sort.Order.asc("id"));
        Query contentQuery = Query.query(criteria).with(PageRequest.of(page, size, sort));
        List<RunView> content = mongo.find(contentQuery, SyncRunDocument.class).stream().map(this::view).toList();
        long total = mongo.count(Query.query(criteria), SyncRunDocument.class);
        return PageResponse.from(new PageImpl<>(content, PageRequest.of(page, size, sort), total));
    }

    public RunView detail(String runId) {
        if (runId == null || runId.isBlank() || runId.length() > 80) throw new BadRequestException("Invalid run ID");
        SyncRunDocument run = mongo.findById(runId, SyncRunDocument.class);
        if (run == null) throw new ResourceNotFoundException("Sync run not found");
        return view(run);
    }

    public List<RunView> latest(String provider, String employer) {
        Criteria criteria = filters(provider, employer, null, null);
        Query query = Query.query(criteria).with(Sort.by(Sort.Order.desc("startedAt"), Sort.Order.asc("id")))
                .limit(200);
        Map<String, RunView> latestByScope = new LinkedHashMap<>();
        for (SyncRunDocument run : mongo.find(query, SyncRunDocument.class)) {
            String key = run.getProvider() + "\u0000" + (run.getEmployer() == null ? "" : run.getEmployer());
            latestByScope.putIfAbsent(key, view(run));
        }
        return List.copyOf(latestByScope.values());
    }

    private Criteria filters(String provider, String employer, String outcome, String trigger) {
        java.util.ArrayList<Criteria> filters = new java.util.ArrayList<>();
        String normalizedProvider = normalizedProvider(provider);
        if (normalizedProvider != null) filters.add(Criteria.where("provider").is(normalizedProvider));
        String normalizedEmployer = normalizedEmployer(employer);
        if (normalizedEmployer != null) filters.add(Criteria.where("employer").is(normalizedEmployer));
        if (outcome != null && !outcome.isBlank()) {
            filters.add(Criteria.where("outcome").is(parseEnum(Outcome.class, outcome, "outcome")));
        }
        if (trigger != null && !trigger.isBlank()) {
            filters.add(Criteria.where("trigger").is(parseEnum(Trigger.class, trigger, "trigger")));
        }
        return filters.isEmpty() ? new Criteria() : new Criteria().andOperator(filters);
    }

    private String normalizedProvider(String provider) {
        if (provider == null || provider.isBlank()) return null;
        String normalized = provider.trim().toLowerCase(Locale.ROOT);
        if (!List.of("adzuna", "greenhouse", "lever").contains(normalized)) {
            throw new BadRequestException("Unsupported provider");
        }
        return normalized;
    }

    private String normalizedEmployer(String employer) {
        if (employer == null || employer.isBlank()) return null;
        String normalized = employer.trim();
        if (normalized.length() > 100 || !normalized.matches("[A-Za-z0-9._-]+")) {
            throw new BadRequestException("Invalid employer");
        }
        return normalized;
    }

    private <T extends Enum<T>> T parseEnum(Class<T> type, String raw, String label) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new BadRequestException("Invalid " + label);
        }
    }

    private void validatePage(int page, int size) {
        if (page < 0) throw new BadRequestException("Page must not be negative");
        if (size < 1 || size > 100) throw new BadRequestException("Size must be between 1 and 100");
    }

    private RunView view(SyncRunDocument run) {
        String failureType = run.getFailureType();
        if (failureType != null && !failureType.matches("[A-Za-z0-9_$]{1,80}")) failureType = "ProviderFailure";
        String failureDetail = run.getFailureDetail();
        if (failureDetail != null) {
            failureDetail = URL.matcher(failureDetail).replaceAll("[redacted-url]");
            failureDetail = SECRET.matcher(failureDetail).replaceAll("$1$2[redacted]")
                    .replace('\n', ' ').replace('\r', ' ').trim();
            if (failureDetail.length() > MAX_FAILURE_DETAIL) {
                failureDetail = failureDetail.substring(0, MAX_FAILURE_DETAIL);
            }
        }
        return new RunView(run.getRunId(), run.getProvider(), run.getEmployer(), run.getTrigger(),
                run.getStartedAt(), run.getCompletedAt(), run.getOutcome(), run.getInserted(), run.getUpdated(),
                run.getUnchanged(), run.getRejected(), run.getFailedItems(), run.getFailedBatches(),
                run.getFailedEmployers(), run.getAttemptedBatches(), run.getAttemptedEmployers(),
                run.getLifecycleMatched(), run.getLifecycleModified(), run.getRetries(), failureType, failureDetail);
    }

    Failure sanitize(Throwable failure) {
        if (failure == null) return null;
        String detail = failure.getMessage();
        if (detail == null || detail.isBlank()) detail = "Provider operation failed";
        detail = URL.matcher(detail).replaceAll("[redacted-url]");
        detail = SECRET.matcher(detail).replaceAll("$1$2[redacted]");
        detail = detail.replace('\n', ' ').replace('\r', ' ').trim();
        if (detail.length() > MAX_FAILURE_DETAIL) detail = detail.substring(0, MAX_FAILURE_DETAIL);
        return new Failure(failure.getClass().getSimpleName(), detail);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public enum Trigger { SCHEDULED, MANUAL }
    public enum Outcome { RUNNING, COMPLETED, PARTIAL, FAILED, LOCKED, LEASE_LOST }
    public record Handle(String runId, Instant startedAt) { }
    public record Failure(String type, String detail) { }
    public record Counts(int inserted, int updated, int unchanged, int rejected, int failedItems,
                         int failedBatches, int failedEmployers, long lifecycleMatched,
                         long lifecycleModified, int retries, int attemptedBatches,
                         int attemptedEmployers) {
        public static Counts empty() { return new Counts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0); }
    }
    public record RunView(String runId, String provider, String employer, Trigger trigger,
                          Instant startedAt, Instant completedAt, Outcome outcome,
                          int inserted, int updated, int unchanged, int rejected,
                          int failedItems, int failedBatches, int failedEmployers,
                          int attemptedBatches, int attemptedEmployers,
                          long lifecycleMatched, long lifecycleModified, int retries,
                          String failureType, String failureDetail) { }
}
