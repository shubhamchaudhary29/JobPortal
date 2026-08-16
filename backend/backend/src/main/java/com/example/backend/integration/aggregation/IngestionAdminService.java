package com.example.backend.integration.aggregation;

import com.example.backend.job.infrastructure.JobDocument;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.bson.Document;
import java.util.ArrayList;
import java.util.List;

@Service
public class IngestionAdminService {
    private final MongoTemplate mongo;
    public IngestionAdminService(MongoTemplate mongo) { this.mongo = mongo; }
    public Counts counts() {
        return new Counts(
                mongo.count(Query.query(Criteria.where("recruiterId").is(null).and("active").is(true)), JobDocument.class),
                mongo.count(Query.query(Criteria.where("recruiterId").is(null).and("active").is(false)), JobDocument.class));
    }
    public List<ProviderCompanyCount> providerCompanyCounts() {
        List<Document> rows = mongo.getCollection("jobs").aggregate(List.of(
                new Document("$match", new Document("recruiterId", null)
                        .append("sourceListings.0", new Document("$exists", true))),
                new Document("$unwind", "$sourceListings"),
                new Document("$group", new Document("_id", new Document("provider", "$sourceListings.provider")
                        .append("employer", "$sourceListings.employer").append("company", "$company"))
                        .append("activeListings", new Document("$sum", new Document("$cond",
                                List.of("$sourceListings.active", 1, 0))))
                        .append("inactiveListings", new Document("$sum", new Document("$cond",
                                List.of("$sourceListings.active", 0, 1))))),
                new Document("$sort", new Document("_id.provider", 1).append("_id.employer", 1)
                        .append("_id.company", 1)))).into(new ArrayList<>());
        return rows.stream().map(row -> {
            Document id = row.get("_id", Document.class);
            return new ProviderCompanyCount(id.getString("provider"), id.getString("employer"),
                    id.getString("company"), ((Number) row.get("activeListings")).longValue(),
                    ((Number) row.get("inactiveListings")).longValue());
        }).toList();
    }
    public record Counts(long active, long inactive) { }
    public record ProviderCompanyCount(String provider, String employer, String company,
                                       long activeListings, long inactiveListings) { }
}
