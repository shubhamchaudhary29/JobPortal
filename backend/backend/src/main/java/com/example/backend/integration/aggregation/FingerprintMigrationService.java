package com.example.backend.integration.aggregation;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Recomputes {@link JobFingerprint} values for imported jobs. Dry-run never writes. Apply mode updates a stale
 * job only when its new fingerprint is unique and the stored value is unchanged; jobs that would collide stay
 * untouched and every colliding pair becomes a {@code FINGERPRINT_MIGRATION} conflict for ADMIN reconciliation.
 * Recruiter jobs, reconciliation-pending jobs, and jobs referenced by applications are never updated.
 */
@Service
public class FingerprintMigrationService {
    static final int SAMPLE_LIMIT = 20;
    private static final Logger log = LoggerFactory.getLogger(FingerprintMigrationService.class);
    private final MongoTemplate mongo;
    private final AggregationConflictService conflicts;
    private final Clock clock;

    public FingerprintMigrationService(MongoTemplate mongo, AggregationConflictService conflicts, Clock clock) {
        this.mongo = mongo;
        this.conflicts = conflicts;
        this.clock = clock;
    }

    public Report run(boolean apply) {
        Map<String, List<Candidate>> groups = new TreeMap<>();
        List<Candidate> stale = new ArrayList<>();
        Report report = new Report(apply);
        Query imported = Query.query(Criteria.where("recruiterId").is(null).and("reconciliationTargetId").is(null));
        imported.fields().include("_id", "company", "title", "location", "fingerprint", "fingerprintVersion");
        mongo.getCollection("jobs").find(imported.getQueryObject()).projection(imported.getFieldsObject())
                .forEach(document -> {
                    report.scanned++;
                    String id = String.valueOf(document.get("_id"));
                    String next = JobFingerprint.of(document.getString("company"), document.getString("title"),
                            document.getString("location"));
                    if (next == null) {
                        report.unfingerprintable++;
                        sample(report.unfingerprintableIds, id);
                        return;
                    }
                    Candidate candidate = new Candidate(id, document.getString("fingerprint"),
                            document.get("fingerprintVersion"), next);
                    groups.computeIfAbsent(next, ignored -> new ArrayList<>()).add(candidate);
                    if (candidate.current()) report.current++;
                    else stale.add(candidate);
                });
        Set<String> referenced = applicationReferenced(stale);
        Set<String> conflictGroups = new HashSet<>();
        LocalDateTime observedAt = LocalDateTime.now(clock);
        for (Candidate candidate : stale) {
            List<Candidate> group = groups.get(candidate.next());
            if (group.size() > 1) {
                report.collisions++;
                sample(report.collisionIds, candidate.id());
                if (conflictGroups.add(candidate.next())) {
                    report.collisionGroups++;
                    recordPairs(group, apply, observedAt, report);
                }
                continue;
            }
            if (referenced.contains(candidate.id())) {
                report.skippedApplicationReferenced++;
                sample(report.skippedApplicationIds, candidate.id());
                continue;
            }
            report.planned++;
            sample(report.plannedIds, candidate.id());
            if (!apply) continue;
            try {
                Criteria unchanged = Criteria.where("_id").is(candidate.id()).and("recruiterId").is(null)
                        .and("reconciliationTargetId").is(null).and("fingerprint").is(candidate.previous());
                long modified = mongo.updateFirst(Query.query(unchanged), new Update()
                        .set("fingerprint", candidate.next())
                        .set("fingerprintVersion", JobFingerprint.VERSION),
                        com.example.backend.job.infrastructure.JobDocument.class).getModifiedCount();
                if (modified == 1) report.updated++;
                else report.changedConcurrently++;
            } catch (DuplicateKeyException concurrentCollision) {
                report.changedConcurrently++;
            }
        }
        log.info("event=fingerprint_migration_report mode={} scanned={} current={} planned={} updated={} "
                        + "collisions={} collision_groups={} conflicts_recorded={} skipped_application_referenced={} "
                        + "unfingerprintable={} changed_concurrently={}", apply ? "apply" : "dry-run", report.scanned,
                report.current, report.planned, report.updated, report.collisions, report.collisionGroups,
                report.conflictsRecorded, report.skippedApplicationReferenced, report.unfingerprintable,
                report.changedConcurrently);
        return report;
    }

    private void recordPairs(List<Candidate> group, boolean apply, LocalDateTime observedAt, Report report) {
        List<String> ids = group.stream().map(Candidate::id).sorted().toList();
        for (int index = 1; index < ids.size(); index++) {
            report.conflictPairs++;
            if (!apply) continue;
            conflicts.recordFingerprintMigration(group.get(0).next(), ids.get(0), ids.get(index), observedAt);
            report.conflictsRecorded++;
        }
    }

    private Set<String> applicationReferenced(List<Candidate> stale) {
        Set<String> referenced = new HashSet<>();
        List<String> ids = stale.stream().map(Candidate::id).toList();
        for (int start = 0; start < ids.size(); start += 1_000) {
            List<String> batch = ids.subList(start, Math.min(ids.size(), start + 1_000));
            for (org.bson.BsonValue jobId : mongo.getCollection("applications").distinct("jobId",
                    new Document("jobId", new Document("$in", batch)), org.bson.BsonValue.class)) {
                if (jobId.isString()) referenced.add(jobId.asString().getValue());
                else if (jobId.isObjectId()) referenced.add(jobId.asObjectId().getValue().toHexString());
            }
        }
        return referenced;
    }

    private static void sample(List<String> target, String id) {
        if (target.size() < SAMPLE_LIMIT) target.add(id);
    }

    private record Candidate(String id, String previous, Object version, String next) {
        boolean current() {
            return Objects.equals(previous, next) && version instanceof Number number
                    && number.intValue() == JobFingerprint.VERSION;
        }
    }

    /** Counts plus at most {@value #SAMPLE_LIMIT} IDs per category. */
    public static final class Report {
        public final boolean apply;
        public long scanned, current, planned, updated, collisions, collisionGroups, conflictPairs,
                conflictsRecorded, skippedApplicationReferenced, unfingerprintable, changedConcurrently;
        public final List<String> plannedIds = new ArrayList<>();
        public final List<String> collisionIds = new ArrayList<>();
        public final List<String> skippedApplicationIds = new ArrayList<>();
        public final List<String> unfingerprintableIds = new ArrayList<>();

        Report(boolean apply) { this.apply = apply; }

        /** True when operators must review collisions, skipped jobs, or concurrent changes. */
        public boolean needsReview() {
            return collisions > 0 || skippedApplicationReferenced > 0 || unfingerprintable > 0 || changedConcurrently > 0;
        }

        public Map<String, Object> summary() {
            Map<String, Object> summary = new HashMap<>();
            summary.put("mode", apply ? "apply" : "dry-run");
            summary.put("scanned", scanned);
            summary.put("current", current);
            summary.put("planned", planned);
            summary.put("updated", updated);
            summary.put("collisions", collisions);
            summary.put("collisionGroups", collisionGroups);
            summary.put("conflictPairs", conflictPairs);
            summary.put("conflictsRecorded", conflictsRecorded);
            summary.put("skippedApplicationReferenced", skippedApplicationReferenced);
            summary.put("unfingerprintable", unfingerprintable);
            summary.put("changedConcurrently", changedConcurrently);
            return summary;
        }
    }
}
