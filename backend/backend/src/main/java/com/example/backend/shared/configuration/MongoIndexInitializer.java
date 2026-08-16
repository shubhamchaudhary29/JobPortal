package com.example.backend.shared.configuration;

import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.index.TextIndexDefinition;
import org.springframework.stereotype.Component;
import java.util.List;

/** Fails deployment before imported identity indexes are attempted against legacy duplicates. */
@Component
@ConditionalOnProperty(name = "app.mongo.indexes.verify-on-startup", havingValue = "true", matchIfMissing = true)
public class MongoIndexInitializer implements ApplicationRunner {
    private final MongoTemplate mongo;
    public MongoIndexInitializer(MongoTemplate mongo) { this.mongo = mongo; }
    @Override public void run(ApplicationArguments args) {
        List<Document> duplicates = mongo.getCollection("jobs").aggregate(List.of(
                new Document("$match", new Document("source", new Document("$type", "string")).append("externalId", new Document("$type", "string"))),
                new Document("$group", new Document("_id", new Document("source", "$source").append("externalId", "$externalId")).append("count", new Document("$sum", 1))),
                new Document("$match", new Document("count", new Document("$gt", 1))), new Document("$limit", 10))).into(new java.util.ArrayList<>());
        List<Document> fingerprints = mongo.getCollection("jobs").aggregate(List.of(
                new Document("$match", new Document("fingerprint", new Document("$type", "string")).append("recruiterId", null)),
                new Document("$group", new Document("_id", "$fingerprint").append("count", new Document("$sum", 1))),
                new Document("$match", new Document("count", new Document("$gt", 1))), new Document("$limit", 10))).into(new java.util.ArrayList<>());
        List<Document> identities = mongo.getCollection("jobs").aggregate(List.of(
                new Document("$match", new Document("sourceIdentities", new Document("$type", "string")).append("recruiterId", null)),
                new Document("$unwind", "$sourceIdentities"), new Document("$group", new Document("_id", "$sourceIdentities").append("count", new Document("$sum", 1))),
                new Document("$match", new Document("count", new Document("$gt", 1))), new Document("$limit", 10))).into(new java.util.ArrayList<>());
        if (!duplicates.isEmpty() || !fingerprints.isEmpty() || !identities.isEmpty()) throw new IllegalStateException("Cannot create imported-job unique indexes: audit reported duplicate source, fingerprint, or source identity records. Run backend/scripts/audit-mongo-indexes.js first.");
        IndexOperations jobs = mongo.indexOps("jobs");
        jobs.ensureIndex(new Index().on("source", Sort.Direction.ASC).on("externalId", Sort.Direction.ASC)
                .unique().named("source_external_id_unique")
                .partial(PartialIndexFilter.of(org.springframework.data.mongodb.core.query.Criteria.where("source").type(2)
                        .and("externalId").type(2))));
        jobs.ensureIndex(new Index().on("fingerprint", Sort.Direction.ASC).unique().named("imported_fingerprint_unique")
                .partial(PartialIndexFilter.of(org.springframework.data.mongodb.core.query.Criteria.where("fingerprint").type(2).and("recruiterId").is(null))));
        jobs.ensureIndex(new Index().on("sourceIdentities", Sort.Direction.ASC).unique().named("imported_source_identity_unique")
                .partial(PartialIndexFilter.of(org.springframework.data.mongodb.core.query.Criteria.where("sourceIdentities").type(2).and("recruiterId").is(null))));
        jobs.ensureIndex(new Index().on("createdAt", Sort.Direction.DESC).named("jobs_created_at_idx"));
        jobs.ensureIndex(new Index().on("source", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC).named("jobs_source_created_at_idx"));
        jobs.ensureIndex(new Index().on("recruiterId", Sort.Direction.ASC).on("createdAt", Sort.Direction.DESC).named("jobs_recruiter_created_at_idx"));
        jobs.ensureIndex(new Index().on("recruiterId", Sort.Direction.ASC).on("active", Sort.Direction.ASC)
                .on("inactiveAt", Sort.Direction.ASC).on("_id", Sort.Direction.ASC)
                .named("jobs_cleanup_eligibility_idx"));
        jobs.ensureIndex(new TextIndexDefinition.TextIndexDefinitionBuilder().onField("title").onField("description")
                .onField("company").named("jobs_search_text_idx").build());
    }
}
