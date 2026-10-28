package com.example.backend.integration.aggregation;

import com.example.backend.geo.application.GeoResolver;
import com.example.backend.job.domain.GeoResolution;
import com.example.backend.job.domain.LocationHint;
import java.time.Clock;
import java.util.ArrayList;
import com.mongodb.client.model.CountOptions;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Re-resolves active jobs whose listing (or listing-less top-level) {@code geo.version} is not the current
 * resolver version, e.g. after an alias edit. Lease-locked and batched by {@code _id}. Per-listing writes use
 * array filters that only match listings still on an old version, so a concurrent ingestion that already wrote
 * a current resolution is never overwritten. Lives with the other lease-locked maintenance jobs because
 * {@code geo} cannot depend on this package without a cycle.
 *
 * <p>The stored provider point (Adzuna) is passed back to the resolver, so re-resolution keeps it and can snap it to a
 * locality (GEO-8B); before GEO-8 every version bump dropped it until the next sync. Since GEO-10 the listing's stored
 * {@code rawArea} goes with it, and the job's description is locality evidence as at ingestion. Every query is bounded
 * by {@value #MAX_TIME_MS} ms.
 */
@Service
public class GeoReResolutionService {
    private static final Logger log = LoggerFactory.getLogger(GeoReResolutionService.class);
    static final String LOCK = "geo-reresolution";
    static final long LEASE_MS = 300_000;
    private static final String JOBS = "jobs";
    static final long MAX_TIME_MS = 15_000;

    private final MongoTemplate mongo;
    private final DistributedLeaseLock locks;
    private final GeoResolver resolver;
    private final Clock clock;
    private final int batchSize;
    private final int maxBatches;

    public GeoReResolutionService(MongoTemplate mongo, DistributedLeaseLock locks, GeoResolver resolver, Clock clock,
                                  @Value("${geo.reresolution.batch-size:200}") int batchSize,
                                  @Value("${geo.reresolution.max-batches:25}") int maxBatches) {
        if (batchSize < 1 || maxBatches < 1) throw new IllegalArgumentException("geo re-resolution batches must be positive");
        this.mongo = mongo;
        this.locks = locks;
        this.resolver = resolver;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
    }

    public Result runOnce() {
        long started = clock.millis();
        String owner = locks.acquire(LOCK, LEASE_MS);
        if (owner == null) return new Result(false, 0, 0, 0, 0);
        try {
            String version = resolver.version();
            int scanned = 0;
            int updated = 0;
            Object lastId = null;
            boolean exhausted = false;
            for (int batch = 0; batch < maxBatches; batch++) {
                List<Document> jobs = staleBatch(version, lastId);
                for (Document job : jobs) {
                    scanned++;
                    updated += reResolve(job, version);
                    lastId = job.get("_id");
                }
                if (jobs.size() < batchSize) { exhausted = true; break; }
            }
            long remaining = exhausted ? 0 : mongo.getCollection(JOBS).countDocuments(staleFilter(version, null),
                    new CountOptions().maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS));
            Result result = new Result(true, scanned, updated, remaining, clock.millis() - started);
            log.info("event=geo_reresolution_completed scanned={} updated={} remaining_estimate={} duration_ms={}",
                    result.scanned(), result.updated(), result.remainingEstimate(), result.durationMs());
            return result;
        } finally {
            locks.release(LOCK, owner);
        }
    }

    private List<Document> staleBatch(String version, Object afterId) {
        Document projection = new Document("_id", 1).append("location", 1).append("sourceListings.identity", 1)
                .append("sourceListings.rawLocation", 1).append("sourceListings.geo.version", 1).append("geo.version", 1)
                .append("sourceListings.geo.point", 1).append("geo.point", 1).append("sourceListings.rawArea", 1)
                .append("sourceListings.rawPostalCode", 1).append("sourceListings.rawAddress", 1)
                .append("description", 1);
        return mongo.getCollection(JOBS).find(staleFilter(version, afterId)).projection(projection)
                .sort(new Document("_id", 1)).limit(batchSize).maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS)
                .into(new ArrayList<>());
    }

    /** Active jobs (same semantics as job search) with any listing, or a listing-less top level, off-version. */
    private static Bson staleFilter(String version, Object afterId) {
        Document notCurrent = new Document("$ne", version);
        Document noListings = new Document("$or", List.of(
                new Document("sourceListings", new Document("$exists", false)),
                new Document("sourceListings", null),
                new Document("sourceListings", new Document("$size", 0))));
        List<Document> clauses = new ArrayList<>(List.of(
                new Document("$or", List.of(new Document("active", true), new Document("active", null))),
                new Document("$or", List.of(
                        new Document("sourceListings", new Document("$elemMatch", new Document("geo.version", notCurrent))),
                        new Document("$and", List.of(noListings, new Document("geo.version", notCurrent)))))));
        if (afterId != null) clauses.add(new Document("_id", new Document("$gt", afterId)));
        return new Document("$and", clauses);
    }

    private int reResolve(Document job, String version) {
        Query byId = Query.query(Criteria.where("_id").is(job.get("_id")));
        String location = job.getString("location");
        String description = job.getString("description");
        List<Document> listings = job.getList("sourceListings", Document.class);
        if (listings == null || listings.isEmpty()) {
            Update update = new Update().set("geo", mapped(resolver.resolve(location,
                    hint(null, job.get("geo", Document.class)), description)));
            return (int) mongo.updateFirst(Query.query(Criteria.where("_id").is(job.get("_id"))
                    .and("geo.version").ne(version)), update, JOBS).getModifiedCount();
        }
        int updated = 0;
        for (Document listing : listings) {
            Document geo = listing.get("geo", Document.class);
            if (geo != null && version.equals(geo.getString("version"))) continue;
            String raw = listing.getString("rawLocation");
            boolean seeded = raw == null && location != null;
            Update update = new Update().set("sourceListings.$[l].geo", mapped(resolver.resolve(seeded ? location : raw,
                            hint(listing, geo), description)))
                    .filterArray(Criteria.where("l.identity").is(listing.getString("identity")).and("l.geo.version").ne(version));
            if (seeded) {
                update.set("sourceListings.$[l].rawLocation", location).set("sourceListings.$[l].rawLocationInferred", true);
            }
            updated += (int) mongo.updateFirst(byId, update, JOBS).getModifiedCount();
        }
        return updated;
    }

    /**
     * The listing's stored area (GEO-10A), postal code and address (GEO-10E) and provider point as the hint, or null when
     * none is stored. {@code listing} is null for a listing-less job, which has only a point.
     */
    private static LocationHint hint(Document listing, Document geo) {
        Document point = geo == null ? null : geo.get("point", Document.class);
        boolean hasPoint = point != null && point.get("lat") instanceof Number && point.get("lng") instanceof Number;
        List<String> area = listing == null ? null : listing.getList("rawArea", String.class);
        String postalCode = listing == null ? null : listing.getString("rawPostalCode");
        String address = listing == null ? null : listing.getString("rawAddress");
        if (!hasPoint && (area == null || area.isEmpty()) && postalCode == null && address == null) return null;
        return new LocationHint(area, hasPoint ? ((Number) point.get("lat")).doubleValue() : null,
                hasPoint ? ((Number) point.get("lng")).doubleValue() : null, postalCode, address);
    }

    private Object mapped(GeoResolution resolution) {
        return mongo.getConverter().convertToMongoType(resolution);
    }

    public record Result(boolean acquired, int scanned, int updated, long remainingEstimate, long durationMs) { }
}
