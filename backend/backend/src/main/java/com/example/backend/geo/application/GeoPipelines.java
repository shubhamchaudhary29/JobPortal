package com.example.backend.geo.application;

import java.util.ArrayList;
import java.util.List;
import org.bson.Document;

/**
 * Aggregation expressions that define a job's geo for every read model, so the coverage report and the map
 * snapshot cannot drift apart: a job with source listings is described by its <em>active</em> listings; a
 * job without listings (manual or legacy) by its top-level {@code geo}.
 */
final class GeoPipelines {
    /** Priority order; index + 1 is the rank used inside aggregations (lower is better). */
    static final List<String> STATUSES = List.of("PLACE", "REMOTE", "STATE", "COUNTRY", "FOREIGN", "UNRESOLVED", "EMPTY");
    static final String UNPROCESSED = "UNPROCESSED";
    static final int UNPROCESSED_RANK = 99;

    private GeoPipelines() { }

    /**
     * Jobs a reader may see: active (or legacy null) and not a reconciliation duplicate. A job pending
     * reconciliation still exists beside its canonical job, so including it would show the same posting twice.
     */
    static Document visibleJobs() {
        return new Document("$and", List.of(
                new Document("$or", List.of(new Document("active", true), new Document("active", null))),
                new Document("reconciliationTargetId", null), new Document("reconciliationConflictId", null)));
    }

    /** {@code lower(trim(company))}, "" when missing: spellings that differ only in case or padding group together. */
    static Document lowerCompany() {
        return new Document("$toLower", new Document("$trim",
                new Document("input", new Document("$ifNull", List.of("$company", "")))));
    }

    static Document listings() {
        return new Document("$ifNull", List.of("$sourceListings", List.of()));
    }

    static Document hasListings() {
        return new Document("$gt", List.of(new Document("$size", listings()), 0));
    }

    static Document activeListings() {
        return new Document("$filter", new Document("input", listings()).append("as", "l")
                .append("cond", new Document("$ne", List.of("$$l.active", false))));
    }

    /** Set-union of {@code geo.placeIds} over active listings, or the top-level {@code geo.placeIds}. */
    static Document placeIds() {
        Document union = new Document("$reduce", new Document("input", activeListings())
                .append("initialValue", List.of())
                .append("in", new Document("$setUnion", List.of("$$value",
                        new Document("$ifNull", List.of("$$this.geo.placeIds", List.of()))))));
        return new Document("$cond", List.of(hasListings(), union,
                new Document("$ifNull", List.of("$geo.placeIds", List.of()))));
    }

    /**
     * Set-union of {@code geo.localityIds} over active listings, or the top-level {@code geo.localityIds}; the same
     * rule as {@link #placeIds()}. Documents written before GEO-8 have no field and give an empty array.
     */
    static Document localityIds() {
        Document union = new Document("$reduce", new Document("input", activeListings())
                .append("initialValue", List.of())
                .append("in", new Document("$setUnion", List.of("$$value",
                        new Document("$ifNull", List.of("$$this.geo.localityIds", List.of()))))));
        return new Document("$cond", List.of(hasListings(), union,
                new Document("$ifNull", List.of("$geo.localityIds", List.of()))));
    }

    /**
     * {@code geo.localityIds} of active listings concatenated in listing order (resolver order within a listing), or the
     * top-level list. May repeat ids; readers keep the first occurrence. Used where "first locality" matters.
     */
    static Document orderedLocalityIds() {
        Document concatenated = new Document("$reduce", new Document("input", activeListings())
                .append("initialValue", List.of())
                .append("in", new Document("$concatArrays", List.of("$$value",
                        new Document("$ifNull", List.of("$$this.geo.localityIds", List.of()))))));
        return new Document("$cond", List.of(hasListings(), concatenated,
                new Document("$ifNull", List.of("$geo.localityIds", List.of()))));
    }

    /** Best status rank over active listings (or top-level geo); {@link #UNPROCESSED_RANK} when nothing is resolved. */
    static Document bestRank() {
        Document statuses = new Document("$cond", List.of(hasListings(),
                new Document("$map", new Document("input", activeListings()).append("as", "l").append("in", "$$l.geo.status")),
                List.of("$geo.status")));
        List<Document> branches = new ArrayList<>();
        for (int index = 0; index < STATUSES.size(); index++) {
            branches.add(new Document("case", new Document("$eq", List.of("$$s", STATUSES.get(index))))
                    .append("then", index + 1));
        }
        Document ranks = new Document("$map", new Document("input", statuses).append("as", "s")
                .append("in", new Document("$switch", new Document("branches", branches).append("default", UNPROCESSED_RANK))));
        return new Document("$ifNull", List.of(new Document("$min", ranks), UNPROCESSED_RANK));
    }

    static String statusForRank(int rank) {
        return rank >= 1 && rank <= STATUSES.size() ? STATUSES.get(rank - 1) : UNPROCESSED;
    }

    /** Status names in priority order plus UNPROCESSED, zero-filled. */
    static java.util.Map<String, Long> zeroFilledStatuses() {
        java.util.Map<String, Long> counts = new java.util.LinkedHashMap<>();
        STATUSES.forEach(status -> counts.put(status, 0L));
        counts.put(UNPROCESSED, 0L);
        return counts;
    }
}
