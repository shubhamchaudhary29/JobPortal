package com.example.backend.job.infrastructure;

import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/** Atomic job-level claims prevent application insertion from racing imported-job cleanup. */
@Component
public class JobApplicationReferenceCoordinator {
    private static final long STALE_CLAIM_MINUTES = 15;
    private final MongoTemplate mongo;

    public JobApplicationReferenceCoordinator(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public boolean acquireApplicationReference(String jobId) {
        Criteria recruiterJob = Criteria.where("recruiterId").ne(null);
        Criteria noLiveCleanupClaim = new Criteria().orOperator(
                Criteria.where("cleanupClaimId").is(null),
                Criteria.where("cleanupClaimedAt").lt(LocalDateTime.now().minusMinutes(STALE_CLAIM_MINUTES)));
        Criteria eligibleImport = new Criteria().andOperator(
                Criteria.where("recruiterId").is(null),
                Criteria.where("active").ne(false),
                Criteria.where("reconciliationTargetId").is(null),
                Criteria.where("reconciliationConflictId").is(null),
                noLiveCleanupClaim);
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(jobId), new Criteria().orOperator(recruiterJob, eligibleImport)));
        return mongo.updateFirst(query, new Update().inc("applicationReferenceCount", 1)
                        .unset("cleanupClaimId").unset("cleanupClaimedAt"),
                JobDocument.class).getModifiedCount() == 1;
    }

    public void releaseApplicationReference(String jobId) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(jobId)
                        .and("applicationReferenceCount").gt(0)),
                new Update().inc("applicationReferenceCount", -1), JobDocument.class);
    }

    public String claimForCleanup(String jobId, LocalDateTime cutoff, LocalDateTime now) {
        String claimId = UUID.randomUUID().toString();
        Criteria noApplicationClaim = new Criteria().orOperator(
                Criteria.where("applicationReferenceCount").exists(false),
                Criteria.where("applicationReferenceCount").lte(0));
        Criteria claimAvailable = new Criteria().orOperator(
                Criteria.where("cleanupClaimId").is(null),
                Criteria.where("cleanupClaimedAt").lt(now.minusMinutes(STALE_CLAIM_MINUTES)));
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(jobId), cleanupEligible(cutoff),
                noApplicationClaim, claimAvailable));
        JobDocument claimed = mongo.findAndModify(query,
                new Update().set("cleanupClaimId", claimId).set("cleanupClaimedAt", now),
                FindAndModifyOptions.options().returnNew(true), JobDocument.class);
        return claimed == null ? null : claimId;
    }

    public void releaseCleanupClaim(String jobId, String claimId) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(jobId)
                        .and("cleanupClaimId").is(claimId)),
                new Update().unset("cleanupClaimId").unset("cleanupClaimedAt"), JobDocument.class);
    }

    public boolean deleteClaimed(String jobId, String claimId, LocalDateTime cutoff) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("_id").is(jobId), Criteria.where("cleanupClaimId").is(claimId),
                cleanupEligible(cutoff)));
        return mongo.remove(query, JobDocument.class).getDeletedCount() == 1;
    }

    private Criteria cleanupEligible(LocalDateTime cutoff) {
        return new Criteria().andOperator(
                Criteria.where("recruiterId").is(null),
                Criteria.where("active").is(false),
                Criteria.where("inactiveAt").lte(cutoff),
                Criteria.where("reconciliationTargetId").is(null),
                Criteria.where("reconciliationConflictId").is(null));
    }
}
