package com.example.backend.integration.aggregation;

import com.example.backend.application.infrastructure.ApplicationRepository;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.job.infrastructure.JobApplicationReferenceCoordinator;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final JobApplicationReferenceCoordinator jobReferences;
    private final CleanupClaimHook claimHook;

    @Autowired
    public ImportedJobCleanupService(MongoTemplate mongo, ApplicationRepository applications,
            JobApplicationReferenceCoordinator jobReferences,
            @Value("${job-aggregation.cleanup.retention-days:90}") int retentionDays,
            @Value("${job-aggregation.cleanup.batch-size:100}") int batchSize) {
        this(mongo, applications, jobReferences, retentionDays, batchSize, ignored -> { });
    }

    ImportedJobCleanupService(MongoTemplate mongo, ApplicationRepository applications,
            JobApplicationReferenceCoordinator jobReferences, int retentionDays, int batchSize,
            CleanupClaimHook claimHook) {
        if (retentionDays < 1) throw new IllegalArgumentException("cleanup retention must be positive");
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("cleanup batch size must be between 1 and 1000");
        }
        this.mongo = mongo;
        this.applications = applications;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
        this.jobReferences = jobReferences;
        this.claimHook = claimHook;
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
            String claimId = jobReferences.claimForCleanup(job.getId(), cutoff, now);
            if (claimId == null) continue;
            try {
                claimHook.afterClaim(job.getId());
                if (applications.existsByJobId(job.getId())) {
                    protectedReferences++;
                    jobReferences.releaseCleanupClaim(job.getId(), claimId);
                    continue;
                }
                if (jobReferences.deleteClaimed(job.getId(), claimId, cutoff)) deleted++;
                else jobReferences.releaseCleanupClaim(job.getId(), claimId);
            } catch (RuntimeException failure) {
                jobReferences.releaseCleanupClaim(job.getId(), claimId);
                throw failure;
            }
        }
        return new Result(jobs.size(), deleted, protectedReferences);
    }

    private Criteria eligible(LocalDateTime cutoff) {
        return new Criteria().andOperator(
                Criteria.where("recruiterId").is(null),
                Criteria.where("active").is(false),
                Criteria.where("inactiveAt").lte(cutoff),
                Criteria.where("reconciliationTargetId").is(null),
                Criteria.where("reconciliationConflictId").is(null));
    }

    public record Result(int scanned, int deleted, int protectedReferences) { }
    @FunctionalInterface interface CleanupClaimHook { void afterClaim(String jobId); }
}
