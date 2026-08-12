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
        if (job.getFingerprint() != null && mongo.exists(Query.query(Criteria.where("fingerprint").is(job.getFingerprint()).and("source").ne(job.getSource())), JobDocument.class)) {
            mongo.updateMulti(Query.query(Criteria.where("fingerprint").is(job.getFingerprint())), new Update().set("lastSeenAt", now).set("active", true), JobDocument.class);
            return UpsertOutcome.UNCHANGED;
        }
        Query query = Query.query(Criteria.where("source").is(job.getSource()).and("externalId").is(job.getExternalId()));
        Update update = new Update().set("title", job.getTitle()).set("description", job.getDescription())
                .set("sourceUrl", job.getSourceUrl()).set("company", job.getCompany()).set("location", job.getLocation())
                .set("salary", job.getSalary()).set("experience", job.getExperience()).set("fetchedAt", now)
                .set("lastSeenAt", now).set("employmentType", job.getEmploymentType()).set("salaryMin", job.getSalaryMin())
                .set("salaryMax", job.getSalaryMax()).set("applicationUrl", job.getApplicationUrl()).set("publishedAt", job.getPublishedAt())
                .set("expiresAt", job.getExpiresAt()).set("active", true).set("fingerprint", job.getFingerprint())
                .setOnInsert("firstSeenAt", now).setOnInsert("source", job.getSource()).setOnInsert("externalId", job.getExternalId())
                .setOnInsert("recruiterId", null).setOnInsert("createdAt", now);
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
}
