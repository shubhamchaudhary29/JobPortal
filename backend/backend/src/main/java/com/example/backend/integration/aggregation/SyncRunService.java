package com.example.backend.integration.aggregation;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
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
}
