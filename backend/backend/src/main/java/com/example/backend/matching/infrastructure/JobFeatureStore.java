package com.example.backend.matching.infrastructure;

import com.example.backend.job.infrastructure.JobDocument;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Persists lazy extraction without replacing concurrently changing job lifecycle or listing state. */
@Repository
public class JobFeatureStore {
    private final MongoTemplate mongo;

    public JobFeatureStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public void persistIfCurrent(JobDocument job) {
        persistAllIfCurrent(List.of(job));
    }

    public void persistAllIfCurrent(List<JobDocument> jobs) {
        List<JobDocument> persistable = jobs == null ? List.of() : jobs.stream()
                .filter(job -> job != null && job.getId() != null && job.getMatchFeatures() != null)
                .toList();
        if (persistable.isEmpty()) return;

        BulkOperations updates = mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, JobDocument.class);
        for (JobDocument job : persistable) {
            updates.updateOne(currentCanonicalContent(job), new Update().set("matchFeatures", job.getMatchFeatures()));
        }
        updates.execute();
    }

    private Query currentCanonicalContent(JobDocument job) {
        return Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(job.getId()),
                Criteria.where("title").is(job.getTitle()),
                Criteria.where("description").is(job.getDescription()),
                Criteria.where("location").is(job.getLocation()),
                Criteria.where("employmentType").is(job.getEmploymentType()),
                Criteria.where("experience").is(job.getExperience())));
    }
}
