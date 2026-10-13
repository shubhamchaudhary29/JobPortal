package com.example.backend.integration.aggregation;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * The live registry: enabled {@code employer_registry} entries in stable source/board order, at most
 * {@code job-aggregation.registry.max-employers-per-run} of them. While the collection is still empty (before the
 * first import) the properties seed is used. Read failures propagate so a run fails visibly instead of silently
 * using stale data.
 */
@Primary
@Component
public class MongoEmployerRegistry implements EmployerRegistry {
    private final MongoTemplate mongo;
    private final EmployerRegistryProperties seed;
    private final int maxEmployersPerRun;

    public MongoEmployerRegistry(MongoTemplate mongo, EmployerRegistryProperties seed,
            @Value("${job-aggregation.registry.max-employers-per-run:2000}") int maxEmployersPerRun) {
        if (maxEmployersPerRun < 1 || maxEmployersPerRun > 100_000) {
            throw new IllegalArgumentException("job-aggregation.registry.max-employers-per-run must be between 1 and 100000");
        }
        this.mongo = mongo;
        this.seed = seed;
        this.maxEmployersPerRun = maxEmployersPerRun;
    }

    @Override
    public List<EmployerRegistryProperties.Employer> employers() {
        if (mongo.estimatedCount(EmployerRegistryDocument.class) == 0) return seed.employers();
        Query enabled = Query.query(Criteria.where("enabled").is(true))
                .with(Sort.by(Sort.Order.asc("source"), Sort.Order.asc("boardKey"))).limit(maxEmployersPerRun);
        return mongo.find(enabled, EmployerRegistryDocument.class).stream()
                .map(EmployerRegistryDocument::toEmployer).toList();
    }
}
