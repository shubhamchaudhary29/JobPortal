package com.example.backend.geo.application;

import com.example.backend.geo.infrastructure.UnresolvedLocationRecorder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * Geo-hit rate over active jobs, computed by one aggregation and cached briefly. A job's status is the best
 * status over its <em>active</em> listings (or its top-level geo when it has none); UNPROCESSED when nothing
 * has been resolved yet.
 */
@Service
public class GeoCoverageService {
    static final Duration CACHE_TTL = Duration.ofSeconds(60);
    static final int TOP_UNRESOLVED = 50;

    private final MongoTemplate mongo;
    private final GeoResolver resolver;
    private final Clock clock;
    private volatile Coverage cached;
    private volatile Instant cachedAt = Instant.MIN;

    public GeoCoverageService(MongoTemplate mongo, GeoResolver resolver, Clock clock) {
        this.mongo = mongo;
        this.resolver = resolver;
        this.clock = clock;
    }

    public Coverage coverage() {
        Instant now = clock.instant();
        Coverage current = cached;
        if (current != null && now.isBefore(cachedAt.plus(CACHE_TTL))) return current;
        Coverage computed = compute();
        cached = computed;
        cachedAt = now;
        return computed;
    }

    private Coverage compute() {
        String version = resolver.version();
        Map<String, Long> byStatus = GeoPipelines.zeroFilledStatuses();
        long activeJobs = 0;
        long staleJobs = 0;
        for (Document group : mongo.getCollection("jobs").aggregate(pipeline(version)).into(new ArrayList<>())) {
            int rank = ((Number) group.get("_id")).intValue();
            long count = ((Number) group.get("count")).longValue();
            String status = GeoPipelines.statusForRank(rank);
            byStatus.merge(status, count, Long::sum);
            activeJobs += count;
            staleJobs += ((Number) group.get("stale")).longValue();
        }
        double placePercent = activeJobs == 0 ? 0.0
                : Math.round(byStatus.get("PLACE") * 1000.0 / activeJobs) / 10.0;
        return new Coverage(version, activeJobs, byStatus, placePercent, staleJobs, topUnresolved());
    }

    private List<Document> pipeline(String version) {
        Document listingStale = new Document("$anyElementTrue", List.of(new Document("$map",
                new Document("input", GeoPipelines.listings()).append("as", "l")
                        .append("in", new Document("$ne", List.of("$$l.geo.version", version))))));
        Document stale = new Document("$cond", List.of(GeoPipelines.hasListings(), listingStale,
                new Document("$ne", List.of("$geo.version", version))));
        return List.of(
                new Document("$match", new Document("$or", List.of(new Document("active", true), new Document("active", null)))),
                new Document("$project", new Document("_rank", GeoPipelines.bestRank()).append("_stale", stale)),
                new Document("$group", new Document("_id", "$_rank").append("count", new Document("$sum", 1))
                        .append("stale", new Document("$sum", new Document("$cond", List.of("$_stale", 1, 0))))));
    }

    private List<UnresolvedLocation> topUnresolved() {
        List<UnresolvedLocation> top = new ArrayList<>();
        for (Document row : mongo.getCollection(UnresolvedLocationRecorder.COLLECTION).find()
                .sort(new Document("count", -1).append("_id", 1)).limit(TOP_UNRESOLVED)) {
            Object lastSeen = row.get("lastSeenAt");
            top.add(new UnresolvedLocation(row.getString("sample"), ((Number) row.get("count")).longValue(),
                    lastSeen instanceof java.util.Date date ? date.toInstant() : null));
        }
        return top;
    }

    public record Coverage(String resolverVersion, long activeJobs, Map<String, Long> byStatus,
                           double placeResolvedPercent, long staleJobs, List<UnresolvedLocation> topUnresolved) { }

    public record UnresolvedLocation(String location, long count, Instant lastSeenAt) { }
}
