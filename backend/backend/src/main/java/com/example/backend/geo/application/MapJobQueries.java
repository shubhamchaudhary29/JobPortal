package com.example.backend.geo.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * MongoDB access for the map job lists. Each call fetches {@code size + 1} rows (slice paging, no count query),
 * projects only list fields (never {@code description} or {@code matchFeatures}) and is bounded by
 * {@code maxTimeMS}. The job definition matches the snapshot: visible jobs, places from active listings or the
 * top-level geo of listing-less jobs.
 */
@Component
public class MapJobQueries {
    static final long MAX_TIME_MS = 3_000;
    static final Document SORT = new Document("createdAt", -1).append("_id", 1);
    static final Document PROJECTION = new Document("title", 1).append("company", 1).append("location", 1)
            .append("employmentType", 1).append("salaryMin", 1).append("salaryMax", 1).append("source", 1)
            .append("createdAt", 1).append("publishedAt", 1).append("sourceListings.active", 1)
            .append("sourceListings.geo.placeIds", 1).append("geo.placeIds", 1);
    private static final String JOBS = "jobs";
    private final MongoTemplate mongo;

    public MapJobQueries(MongoTemplate mongo) { this.mongo = mongo; }

    /** Visible jobs with at least one of {@code placeIds}; both {@code $or} branches are index-backed. */
    public List<Document> atPlaces(Collection<String> placeIds, String phrase, int page, int size) {
        List<String> ids = List.copyOf(placeIds);
        Document byListing = new Document("sourceListings", new Document("$elemMatch",
                new Document("active", new Document("$ne", false)).append("geo.placeIds", new Document("$in", ids))));
        Document byTopLevel = new Document("sourceListings.0", new Document("$exists", false))
                .append("geo.placeIds", new Document("$in", ids));
        Document filter = new Document("$and", List.of(GeoPipelines.visibleJobs(),
                new Document("$or", List.of(byListing, byTopLevel))));
        if (phrase != null) filter.append("$text", text(phrase));
        return mongo.getCollection(JOBS).find(filter).projection(PROJECTION).sort(SORT)
                .skip(page * size).limit(size + 1).maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS).into(new ArrayList<>());
    }

    /** Visible jobs with no place whose best status rank is one of {@code ranks}. */
    public List<Document> unplaced(Collection<Integer> ranks, String phrase, int page, int size) {
        List<Document> stages = new ArrayList<>();
        // $text must be the first stage of an aggregation.
        if (phrase != null) stages.add(new Document("$match", new Document("$text", text(phrase))));
        stages.add(new Document("$match", GeoPipelines.visibleJobs()));
        stages.add(new Document("$addFields", new Document("_mapPlaceIds", GeoPipelines.placeIds())
                .append("_mapRank", GeoPipelines.bestRank())));
        stages.add(new Document("$match", new Document("_mapPlaceIds.0", new Document("$exists", false))
                .append("_mapRank", new Document("$in", List.copyOf(ranks)))));
        stages.add(new Document("$sort", SORT));
        stages.add(new Document("$skip", page * size));
        stages.add(new Document("$limit", size + 1));
        stages.add(new Document("$project", PROJECTION));
        return mongo.getCollection(JOBS).aggregate(stages).maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS)
                .into(new ArrayList<>());
    }

    /** Phrase search, like {@code TextCriteria.matchingPhrase}; {@code phrase} is already free of quotes. */
    private static Document text(String phrase) {
        return new Document("$search", "\"" + phrase + "\"");
    }
}
