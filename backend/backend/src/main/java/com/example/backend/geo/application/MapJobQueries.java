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
            .append("sourceListings.geo.placeIds", 1).append("geo.placeIds", 1)
            .append("sourceListings.geo.localityIds", 1).append("geo.localityIds", 1)
            .append("sourceListings.geo.siteLabel", 1).append("geo.siteLabel", 1);
    private static final String JOBS = "jobs";
    private final MongoTemplate mongo;

    public MapJobQueries(MongoTemplate mongo) { this.mongo = mongo; }

    /** Visible jobs with at least one of {@code placeIds}; both {@code $or} branches are index-backed. */
    public List<Document> atPlaces(Collection<String> placeIds, String phrase, int page, int size) {
        return atPlaces(placeIds, List.of(), phrase, page, size);
    }

    /**
     * Visible jobs at any of {@code placeIds}, or at an office scope's place with one of its company spellings.
     * Every branch starts with an index-backed place condition.
     */
    public List<Document> atPlaces(Collection<String> placeIds, Collection<OfficeScope> offices, String phrase, int page,
                                   int size) {
        return atPlaces(placeIds, offices, List.of(), phrase, page, size);
    }

    /**
     * Visible jobs at any of {@code placeIds}, at an office scope, or at a locality scope (a locality id, minus the
     * company spellings of pinned offices in its city). Every branch starts with an index-backed condition.
     */
    public List<Document> atPlaces(Collection<String> placeIds, Collection<OfficeScope> offices,
                                   Collection<LocalityScope> localities, String phrase, int page, int size) {
        List<Document> branches = new ArrayList<>();
        if (!placeIds.isEmpty()) branches.addAll(placeBranches(List.copyOf(placeIds)));
        for (OfficeScope office : offices) {
            if (office.companies().isEmpty()) continue;
            branches.add(new Document("$and", List.of(new Document("$or", placeBranches(List.of(office.placeId()))),
                    new Document("company", new Document("$in", List.copyOf(office.companies()))))));
        }
        for (LocalityScope locality : localities) {
            List<Document> conditions = new ArrayList<>(List.of(new Document("$or", localityBranches(List.of(locality.localityId())))));
            if (!locality.excludedCompanies().isEmpty()) {
                conditions.add(new Document("company", new Document("$nin", List.copyOf(locality.excludedCompanies()))));
            }
            branches.add(new Document("$and", conditions));
        }
        if (branches.isEmpty()) return List.of();
        return find(new Document("$and", List.of(GeoPipelines.visibleJobs(), new Document("$or", branches))), phrase, page, size);
    }

    /**
     * Visible jobs at {@code placeId} that sit at its centre: no pinned office there (company not one of
     * {@code officeCompanies}) and none of the place's localities ({@code localityIds}, from the snapshot).
     */
    public List<Document> unlistedAtPlace(String placeId, Collection<String> officeCompanies, Collection<String> localityIds,
                                          String phrase, int page, int size) {
        return unlistedAtPlace(placeId, officeCompanies, localityIds, null, phrase, page, size);
    }

    /** {@link #unlistedAtPlace} for one stored {@code company} spelling only (GEO-9E ring click); null means any. */
    public List<Document> unlistedAtPlace(String placeId, Collection<String> officeCompanies, Collection<String> localityIds,
                                          String company, String phrase, int page, int size) {
        Document filter = unlisted(placeId, officeCompanies, localityIds);
        if (company != null) filter.getList("$and", Document.class).add(new Document("company", company));
        return find(filter, phrase, page, size);
    }

    /**
     * Companies hiring at {@code placeId}'s centre (GEO-9E ring): the {@link #unlistedAtPlace} filter grouped by the
     * stored {@code company} spelling, most jobs first, then by name, at most {@code limit}. Rows are
     * {@code {_id: company, jobs: n}}; jobs without a company are left out.
     */
    public List<Document> unlistedCompaniesAtPlace(String placeId, Collection<String> officeCompanies,
                                                   Collection<String> localityIds, int limit) {
        Document filter = unlisted(placeId, officeCompanies, localityIds);
        filter.getList("$and", Document.class).add(new Document("company", new Document("$type", "string")));
        List<Document> stages = List.of(new Document("$match", filter),
                new Document("$group", new Document("_id", "$company").append("jobs", new Document("$sum", 1))),
                new Document("$sort", new Document("jobs", -1).append("_id", 1)),
                new Document("$limit", limit));
        return mongo.getCollection(JOBS).aggregate(stages).maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS).into(new ArrayList<>());
    }

    private static Document unlisted(String placeId, Collection<String> officeCompanies, Collection<String> localityIds) {
        List<Document> conditions = new ArrayList<>(List.of(GeoPipelines.visibleJobs(),
                new Document("$or", placeBranches(List.of(placeId)))));
        if (!officeCompanies.isEmpty()) conditions.add(new Document("company", new Document("$nin", List.copyOf(officeCompanies))));
        if (!localityIds.isEmpty()) conditions.add(new Document("$nor", localityBranches(List.copyOf(localityIds))));
        return new Document("$and", conditions);
    }

    private List<Document> find(Document filter, String phrase, int page, int size) {
        if (phrase != null) filter.append("$text", text(phrase));
        return mongo.getCollection(JOBS).find(filter).projection(PROJECTION).sort(SORT)
                .skip(page * size).limit(size + 1).maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS).into(new ArrayList<>());
    }

    /** Same job definition as places: an active listing with the locality, or a listing-less job's top-level geo. */
    private static List<Document> localityBranches(List<String> ids) {
        Document match = ids.size() == 1 ? new Document("geo.localityIds", ids.get(0))
                : new Document("geo.localityIds", new Document("$in", ids));
        Document byListing = new Document("sourceListings", new Document("$elemMatch",
                new Document("active", new Document("$ne", false)).append("geo.localityIds", match.get("geo.localityIds"))));
        Document byTopLevel = new Document("sourceListings.0", new Document("$exists", false)).append("geo.localityIds",
                match.get("geo.localityIds"));
        return List.of(byListing, byTopLevel);
    }

    private static List<Document> placeBranches(List<String> ids) {
        Document byListing = new Document("sourceListings", new Document("$elemMatch",
                new Document("active", new Document("$ne", false)).append("geo.placeIds", new Document("$in", ids))));
        Document byTopLevel = new Document("sourceListings.0", new Document("$exists", false))
                .append("geo.placeIds", new Document("$in", ids));
        return List.of(byListing, byTopLevel);
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

    /** Jobs at {@code placeId} whose raw {@code company} is one of {@code companies} (an office's spellings). */
    public record OfficeScope(String placeId, Collection<String> companies) { }

    /** Jobs with {@code localityId} whose raw {@code company} is not one of {@code excludedCompanies} (pinned offices). */
    public record LocalityScope(String localityId, Collection<String> excludedCompanies) { }

    /** Phrase search, like {@code TextCriteria.matchingPhrase}; {@code phrase} is already free of quotes. */
    private static Document text(String phrase) {
        return new Document("$search", "\"" + phrase + "\"");
    }
}
