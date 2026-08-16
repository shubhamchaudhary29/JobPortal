package com.example.backend.integration.adzuna;

import com.example.backend.job.infrastructure.JobDocument;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;

@Repository
public class AdzunaJobStore {
    private final MongoTemplate mongo;
    public AdzunaJobStore(MongoTemplate mongo) { this.mongo = mongo; }
    public UpsertOutcome upsert(JobDocument job, LocalDateTime now) {
        if (job.getFingerprint() == null || job.getFingerprint().isBlank()) job.setFingerprint(legacyFingerprint(job));
        String identity = job.getSource() + ":" + job.getExternalId();
        // A provider listing identity is permanent even when its title/location changes.
        // Legacy source/externalId is included until the documented backfill has completed.
        Query identityQuery = Query.query(new Criteria().andOperator(Criteria.where("recruiterId").is(null),
                new Criteria().orOperator(Criteria.where("sourceIdentities").is(identity),
                        Criteria.where("source").is(job.getSource()).and("externalId").is(job.getExternalId()))));
        JobDocument identityMatch = mongo.findOne(identityQuery, JobDocument.class);
        JobDocument fingerprintMatch = mongo.findOne(Query.query(Criteria.where("fingerprint").is(job.getFingerprint()).and("recruiterId").is(null)), JobDocument.class);
        if (identityMatch != null && fingerprintMatch != null && !identityMatch.getId().equals(fingerprintMatch.getId())) {
            mergeCanonicalRecords(identityMatch, fingerprintMatch, now);
            fingerprintMatch = identityMatch;
        }
        Query query = identityMatch != null ? Query.query(Criteria.where("_id").is(identityMatch.getId()))
                : fingerprintMatch != null ? Query.query(Criteria.where("_id").is(fingerprintMatch.getId()))
                : Query.query(new Criteria().andOperator(Criteria.where("recruiterId").is(null), new Criteria().orOperator(
                        Criteria.where("fingerprint").is(job.getFingerprint()),
                        Criteria.where("source").is(job.getSource()).and("externalId").is(job.getExternalId()))));
        Update update = new Update().set("title", job.getTitle()).set("description", job.getDescription())
                .set("sourceUrl", job.getSourceUrl()).set("company", job.getCompany()).set("location", job.getLocation())
                .set("salary", job.getSalary()).set("experience", job.getExperience()).set("fetchedAt", now)
                .set("lastSeenAt", now).set("employmentType", job.getEmploymentType()).set("salaryMin", job.getSalaryMin())
                .set("salaryMax", job.getSalaryMax()).set("applicationUrl", job.getApplicationUrl()).set("publishedAt", job.getPublishedAt())
                .set("expiresAt", job.getExpiresAt()).set("active", true).set("fingerprint", job.getFingerprint())
                .set("lastSuccessfulSyncAt", now).set("consecutiveMissingRuns", 0).unset("inactiveReason").unset("inactiveAt")
                .setOnInsert("firstSeenAt", now).setOnInsert("source", job.getSource()).setOnInsert("externalId", job.getExternalId())
                .setOnInsert("recruiterId", null).setOnInsert("createdAt", now).addToSet("sourceIdentities", identity);
        if (job.getApplicationUrl() != null) update.addToSet("applicationUrls", job.getApplicationUrl());
        try { return outcome(mongo.findAndModify(query, update, FindAndModifyOptions.options().upsert(true), JobDocument.class), job); }
        catch (DuplicateKeyException race) {
            try { return outcome(mongo.findAndModify(query, update, FindAndModifyOptions.options().upsert(true), JobDocument.class), job); }
            catch (RuntimeException failure) { throw new AdzunaPersistenceException(failure); }
        } catch (RuntimeException failure) { throw new AdzunaPersistenceException(failure); }
    }
    private UpsertOutcome outcome(JobDocument previous, JobDocument incoming) {
        if (previous == null) return UpsertOutcome.INSERTED;
        return same(previous, incoming) ? UpsertOutcome.UNCHANGED : UpsertOutcome.UPDATED;
    }
    private boolean same(JobDocument a, JobDocument b) {
        return java.util.Objects.equals(a.getTitle(), b.getTitle()) && java.util.Objects.equals(a.getDescription(), b.getDescription())
                && java.util.Objects.equals(a.getCompany(), b.getCompany()) && java.util.Objects.equals(a.getLocation(), b.getLocation())
                && java.util.Objects.equals(a.getApplicationUrl(), b.getApplicationUrl()) && java.util.Objects.equals(a.getFingerprint(), b.getFingerprint())
                && java.util.Objects.equals(a.getSalaryMin(), b.getSalaryMin()) && java.util.Objects.equals(a.getSalaryMax(), b.getSalaryMax())
                && java.util.Objects.equals(a.getEmploymentType(), b.getEmploymentType()) && java.util.Objects.equals(a.getSourceUrl(), b.getSourceUrl())
                && java.util.Objects.equals(a.getPublishedAt(), b.getPublishedAt()) && java.util.Objects.equals(a.getExpiresAt(), b.getExpiresAt())
                && java.util.Objects.equals(a.getActive(), b.getActive()) && Double.compare(a.getSalary(), b.getSalary()) == 0
                && Double.compare(a.getExperience(), b.getExperience()) == 0;
    }
    private String legacyFingerprint(JobDocument job) {
        String value = String.join("|", safe(job.getCompany()), safe(job.getTitle()), safe(job.getLocation())).toLowerCase(java.util.Locale.ROOT);
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private String safe(String value) { return value == null ? "" : value.trim().replaceAll("\\s+", " "); }
    private void mergeCanonicalRecords(JobDocument target, JobDocument duplicate, LocalDateTime now) {
        java.util.Set<String> identities = new java.util.LinkedHashSet<>(target.getSourceIdentities() == null ? java.util.Set.of() : target.getSourceIdentities());
        if (duplicate.getSourceIdentities() != null) identities.addAll(duplicate.getSourceIdentities());
        java.util.Set<String> urls = new java.util.LinkedHashSet<>(target.getApplicationUrls() == null ? java.util.Set.of() : target.getApplicationUrls());
        if (duplicate.getApplicationUrls() != null) urls.addAll(duplicate.getApplicationUrls());
        mongo.updateFirst(Query.query(Criteria.where("_id").is(target.getId())), new Update().set("sourceIdentities", identities).set("applicationUrls", urls).set("lastSeenAt", now), JobDocument.class);
        mongo.remove(Query.query(Criteria.where("_id").is(duplicate.getId()).and("recruiterId").is(null)), JobDocument.class);
    }
}
