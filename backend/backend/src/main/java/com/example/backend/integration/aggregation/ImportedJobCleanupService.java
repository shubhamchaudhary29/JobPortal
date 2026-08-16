package com.example.backend.integration.aggregation;

import com.example.backend.application.infrastructure.ApplicationRepository;
import com.example.backend.job.infrastructure.JobDocument;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

@Service
public class ImportedJobCleanupService {
    private final MongoTemplate mongo;
    private final ApplicationRepository applications;
    private final int retentionDays;
    private final int batchSize;

    public ImportedJobCleanupService(MongoTemplate mongo, ApplicationRepository applications,
            @Value("${job-aggregation.cleanup.retention-days:90}") int retentionDays,
            @Value("${job-aggregation.cleanup.batch-size:100}") int batchSize) {
        if (retentionDays < 1) throw new IllegalArgumentException("cleanup retention must be positive");
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("cleanup batch size must be between 1 and 1000");
        }
        this.mongo = mongo;
        this.applications = applications;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
    }

    public Result cleanup(LocalDateTime now) {
        LocalDateTime cutoff = now.minusDays(retentionDays);
        Criteria eligible = eligible(cutoff);
        Query candidates = Query.query(eligible).with(Sort.by(
                Sort.Order.asc("inactiveAt"), Sort.Order.asc("id"))).limit(batchSize);
        candidates.fields().include("id");
        List<JobDocument> jobs = mongo.find(candidates, JobDocument.class);
        int deleted = 0;
        int protectedReferences = 0;
        for (JobDocument job : jobs) {
            if (applications.existsByJobId(job.getId())) {
                protectedReferences++;
                continue;
            }
            Query stillEligible = Query.query(new Criteria().andOperator(
                    Criteria.where("_id").is(job.getId()), eligible(cutoff)));
            if (mongo.remove(stillEligible, JobDocument.class).getDeletedCount() == 1) deleted++;
        }
        return new Result(jobs.size(), deleted, protectedReferences);
    }

    private Criteria eligible(LocalDateTime cutoff) {
        return new Criteria().andOperator(
                Criteria.where("recruiterId").is(null),
                Criteria.where("active").is(false),
                Criteria.where("inactiveAt").lte(cutoff));
    }

    public record Result(int scanned, int deleted, int protectedReferences) { }
}
