package com.example.backend.integration.aggregation;

import com.example.backend.job.infrastructure.JobDocument;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

@Service
public class IngestionAdminService {
    private final MongoTemplate mongo;
    public IngestionAdminService(MongoTemplate mongo) { this.mongo = mongo; }
    public Counts counts() {
        return new Counts(
                mongo.count(Query.query(Criteria.where("recruiterId").is(null).and("active").is(true)), JobDocument.class),
                mongo.count(Query.query(Criteria.where("recruiterId").is(null).and("active").is(false)), JobDocument.class));
    }
    public record Counts(long active, long inactive) { }
}
