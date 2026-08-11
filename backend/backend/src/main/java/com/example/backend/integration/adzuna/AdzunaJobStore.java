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
    public void upsert(JobDocument job, LocalDateTime now) {
        Query query = Query.query(Criteria.where("source").is(job.getSource()).and("externalId").is(job.getExternalId()));
        Update update = new Update().set("title", job.getTitle()).set("description", job.getDescription())
                .set("sourceUrl", job.getSourceUrl()).set("company", job.getCompany()).set("location", job.getLocation())
                .set("salary", job.getSalary()).set("experience", job.getExperience()).set("fetchedAt", now)
                .set("lastSeenAt", now).set("employmentType", job.getEmploymentType()).set("salaryMin", job.getSalaryMin())
                .set("salaryMax", job.getSalaryMax()).set("applicationUrl", job.getApplicationUrl()).set("publishedAt", job.getPublishedAt())
                .set("expiresAt", job.getExpiresAt()).set("active", true).set("fingerprint", job.getFingerprint())
                .setOnInsert("firstSeenAt", now).setOnInsert("source", job.getSource()).setOnInsert("externalId", job.getExternalId())
                .setOnInsert("recruiterId", null).setOnInsert("createdAt", now);
        try { mongo.findAndModify(query, update, FindAndModifyOptions.options().upsert(true), JobDocument.class); }
        catch (DuplicateKeyException race) {
            try { mongo.findAndModify(query, update, FindAndModifyOptions.options().upsert(true), JobDocument.class); }
            catch (RuntimeException failure) { throw new AdzunaPersistenceException(failure); }
        } catch (RuntimeException failure) { throw new AdzunaPersistenceException(failure); }
    }
}
