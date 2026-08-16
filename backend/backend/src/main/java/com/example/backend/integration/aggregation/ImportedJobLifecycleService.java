package com.example.backend.integration.aggregation;

import com.example.backend.job.infrastructure.JobDocument;
import com.mongodb.client.result.UpdateResult;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

@Service
public class ImportedJobLifecycleService {
    private final MongoTemplate mongo;
    private final int missingThreshold;

    public ImportedJobLifecycleService(MongoTemplate mongo,
            @Value("${job-aggregation.lifecycle.missing-threshold:3}") int missingThreshold) {
        if (missingThreshold < 1) throw new IllegalArgumentException("missing threshold must be positive");
        this.mongo = mongo;
        this.missingThreshold = missingThreshold;
    }

    public Result completeSuccessfulRun(String provider, String employer, Set<String> seenIdentities,
                                        LocalDateTime completedAt) {
        List<String> seen = List.copyOf(seenIdentities);
        Criteria listingCriteria = Criteria.where("provider").is(provider).and("employer").is(employer);
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("recruiterId").is(null),
                Criteria.where("sourceListings").elemMatch(listingCriteria)));

        Document targetListing = new Document("$and", List.of(
                new Document("$eq", List.of("$$listing.provider", provider)),
                new Document("$eq", java.util.Arrays.asList(
                        new Document("$ifNull", java.util.Arrays.asList("$$listing.employer", null)), employer))));
        Document wasSeen = new Document("$in", List.of("$$listing.identity", seen));
        Document currentMissing = new Document("$ifNull", List.of("$$listing.consecutiveMissingRuns", 0));
        Document nextMissing = new Document("$add", List.of(currentMissing, 1));
        Object completedDate = Date.from(completedAt.atZone(ZoneId.systemDefault()).toInstant());
        Document seenState = new Document("$mergeObjects", List.of("$$listing",
                new Document("lastSeenAt", completedDate).append("active", true)
                        .append("consecutiveMissingRuns", 0)));
        Document missingState = new Document("$mergeObjects", List.of("$$listing",
                new Document("active", new Document("$lt", List.of(nextMissing, missingThreshold)))
                        .append("consecutiveMissingRuns", nextMissing)));
        Document nextListings = new Document("$map", new Document("input", "$sourceListings")
                .append("as", "listing")
                .append("in", new Document("$cond", List.of(targetListing,
                        new Document("$cond", List.of(wasSeen, seenState, missingState)), "$$listing"))));
        Document activeListings = filter("$sourceListings", "listing",
                new Document("$eq", List.of("$$listing.active", true)));
        Document hasActiveListings = new Document("$gt", List.of(new Document("$size", activeListings), 0));
        Document canonicalCandidates = new Document("$cond", List.of(hasActiveListings, activeListings, "$sourceListings"));

        AggregationUpdate update = AggregationUpdate.from(List.of(
                Aggregation.stage(new Document("$set", new Document("sourceListings", nextListings)
                        .append("lastSuccessfulSyncAt", completedDate))),
                Aggregation.stage(new Document("$set", new Document("_activeSourceListings", activeListings))),
                Aggregation.stage(new Document("$set", new Document("_hasActiveSourceListings", hasActiveListings)
                        .append("_canonicalSourceListing",
                                new Document("$arrayElemAt", List.of(canonicalCandidates, 0))))),
                Aggregation.stage(new Document("$set", new Document("active", "$_hasActiveSourceListings")
                        .append("source", "$_canonicalSourceListing.provider")
                        .append("externalId", "$_canonicalSourceListing.externalId")
                        .append("applicationUrl", "$_canonicalSourceListing.applicationUrl")
                        .append("sourceUrl", "$_canonicalSourceListing.applicationUrl")
                        .append("consecutiveMissingRuns", new Document("$cond", List.of(
                                "$_hasActiveSourceListings", 0,
                                new Document("$max", "$sourceListings.consecutiveMissingRuns"))))
                        .append("inactiveReason", new Document("$cond", List.of(
                                "$_hasActiveSourceListings", "$$REMOVE", "all_source_listings_missing")))
                        .append("inactiveAt", new Document("$cond", List.of(
                                "$_hasActiveSourceListings", "$$REMOVE",
                                new Document("$ifNull", List.of("$inactiveAt", completedDate))))))),
                Aggregation.stage(new Document("$unset", List.of(
                        "_activeSourceListings", "_hasActiveSourceListings", "_canonicalSourceListing")))));
        UpdateResult result = mongo.updateMulti(query, update, JobDocument.class);
        return new Result(result.getMatchedCount(), result.getModifiedCount());
    }

    private Document filter(Object input, String alias, Document condition) {
        return new Document("$filter", new Document("input", input).append("as", alias).append("cond", condition));
    }

    public record Result(long matchedJobs, long modifiedJobs) { }
}
