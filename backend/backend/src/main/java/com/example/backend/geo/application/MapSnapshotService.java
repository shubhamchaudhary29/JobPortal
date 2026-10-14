package com.example.backend.geo.application;

import com.example.backend.geo.domain.Place;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * In-memory map read model: one entry per place with its count of visible jobs, plus counts of jobs without a
 * place. Built by one aggregation on a fixed delay and served from memory, so map traffic never reaches MongoDB.
 *
 * <p>Visible jobs use the same filter as matching: active (or legacy null) and not a reconciliation duplicate.
 * A job pending reconciliation still exists alongside its canonical job until resolution, so counting it would
 * count the same posting twice.
 *
 * <p>The refresh runs on its own daemon executor rather than {@code @EnableScheduling}: this is a read model,
 * and the map must stay fresh even when ingestion scheduling is switched off.
 */
@Service
public class MapSnapshotService {
    private static final Logger log = LoggerFactory.getLogger(MapSnapshotService.class);
    static final long MAX_TIME_MS = 15_000;

    private final MongoTemplate mongo;
    private final Gazetteer gazetteer;
    private final Clock clock;
    private final boolean enabled;
    private final long refreshMs;
    private final ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).disable(SerializationFeature.INDENT_OUTPUT).build();
    private final AtomicReference<Published> published = new AtomicReference<>();
    private final Timer buildTimer;
    private final Counter failures;
    private volatile Instant lastSuccess;
    private ScheduledExecutorService executor;

    public MapSnapshotService(MongoTemplate mongo, Gazetteer gazetteer, MeterRegistry registry, Clock clock,
                              @Value("${geo.snapshot.enabled:true}") boolean enabled,
                              @Value("${geo.snapshot.refresh-ms:300000}") long refreshMs) {
        if (refreshMs < 1_000) throw new IllegalArgumentException("geo.snapshot.refresh-ms must be at least 1000");
        this.mongo = mongo;
        this.gazetteer = gazetteer;
        this.clock = clock;
        this.enabled = enabled;
        this.refreshMs = refreshMs;
        this.buildTimer = Timer.builder("jobportal.geo.snapshot.build").register(registry);
        this.failures = Counter.builder("jobportal.geo.snapshot.build.failures").register(registry);
        Gauge.builder("jobportal.geo.snapshot.places", this,
                service -> service.current().map(p -> p.snapshot().places().size()).orElse(0)).register(registry);
        Gauge.builder("jobportal.geo.snapshot.placed.jobs", this,
                service -> service.current().map(p -> p.snapshot().placedJobs()).orElse(0L)).register(registry);
        Gauge.builder("jobportal.geo.snapshot.age.seconds", this, MapSnapshotService::ageSeconds).register(registry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        if (!enabled || executor != null) return;
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "geo-map-snapshot");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::refreshSafely, 0, refreshMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public synchronized void stop() {
        if (executor != null) executor.shutdownNow();
        executor = null;
    }

    /** Pure memory read; never touches MongoDB. */
    public Optional<Published> current() { return Optional.ofNullable(published.get()); }

    /** Synchronous rebuild. A failure keeps serving the previous snapshot. */
    public synchronized void refresh() {
        long started = System.nanoTime();
        try {
            publish(build());
            lastSuccess = clock.instant();
        } catch (Exception failure) {
            failures.increment();
            log.warn("event=geo_snapshot_build_failed type={} message={}", failure.getClass().getSimpleName(),
                    failure.getMessage());
        } finally {
            buildTimer.record(Duration.ofNanos(System.nanoTime() - started));
        }
    }

    private void refreshSafely() {
        try {
            refresh();
        } catch (Throwable unexpected) {
            // An escaping Throwable would silently cancel the fixed-delay task and freeze the map.
            log.warn("event=geo_snapshot_task_failed type={}", unexpected.getClass().getSimpleName());
        }
    }

    private Content build() {
        Document facets = mongo.getCollection("jobs").aggregate(pipeline())
                .maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS).first();
        Map<String, Long> unplaced = GeoPipelines.zeroFilledStatuses();
        Map<String, Long> jobsByPlace = new TreeMap<>();
        long placedJobs = 0;
        long droppedJobs = 0;
        List<Document> placed = facets == null ? List.of() : facets.getList("placed", Document.class, List.of());
        for (Document row : placed) {
            long count = ((Number) row.get("count")).longValue();
            List<String> known = row.getList("_id", String.class, List.of()).stream()
                    .filter(id -> gazetteer.byId(id).isPresent()).toList();
            if (known.isEmpty()) { droppedJobs += count; continue; }
            placedJobs += count;
            known.forEach(id -> jobsByPlace.merge(id, count, Long::sum));
        }
        List<Document> unplacedRows = facets == null ? List.of() : facets.getList("unplaced", Document.class, List.of());
        for (Document row : unplacedRows) {
            unplaced.merge(GeoPipelines.statusForRank(((Number) row.get("_id")).intValue()),
                    ((Number) row.get("count")).longValue(), Long::sum);
        }
        if (droppedJobs > 0) log.warn("event=geo_snapshot_unknown_places dropped_jobs={}", droppedJobs);
        List<MapPlace> places = new ArrayList<>();
        jobsByPlace.forEach((id, jobs) -> {
            Place place = gazetteer.byId(id).orElseThrow();
            places.add(new MapPlace(id, place.name(), gazetteer.admin1Name(place.admin1()).orElse(null),
                    place.lat(), place.lng(), jobs));
        });
        // unmodifiableMap, not Map.copyOf: the status order is part of the payload.
        return new Content(placedJobs, Collections.unmodifiableMap(unplaced), List.copyOf(places));
    }

    private List<Document> pipeline() {
        Document visible = GeoPipelines.visibleJobs();
        Document hasPlace = new Document("placeIds.0", new Document("$exists", true));
        Document noPlace = new Document("placeIds.0", new Document("$exists", false));
        return List.of(
                new Document("$match", visible),
                new Document("$project", new Document("_id", 0).append("placeIds", GeoPipelines.placeIds())
                        .append("rank", GeoPipelines.bestRank())),
                new Document("$facet", new Document("placed", List.of(
                        new Document("$match", hasPlace),
                        new Document("$project", new Document("key",
                                new Document("$sortArray", new Document("input", "$placeIds").append("sortBy", 1)))),
                        new Document("$group", new Document("_id", "$key").append("count", new Document("$sum", 1)))))
                        .append("unplaced", List.of(
                                new Document("$match", noPlace),
                                new Document("$group", new Document("_id", "$rank").append("count", new Document("$sum", 1)))))));
    }

    private void publish(Content content) throws IOException {
        String version = hash(json.writeValueAsBytes(content));
        Published current = published.get();
        if (current != null && current.snapshot().version().equals(version)) return;
        MapSnapshot snapshot = new MapSnapshot(version, clock.instant(), content.placedJobs(), content.unplaced(),
                content.places());
        byte[] raw = json.writeValueAsBytes(snapshot);
        published.set(new Published(snapshot, raw, gzip(raw), "\"" + version + "\""));
    }

    private double ageSeconds() {
        Instant success = lastSuccess;
        return success == null ? Double.NaN : Duration.between(success, clock.instant()).toMillis() / 1000.0;
    }

    private static String hash(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)).substring(0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] gzip(byte[] raw) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) { gzip.write(raw); }
        return out.toByteArray();
    }

    /** Everything that defines the snapshot's identity; {@code updatedAt} is deliberately excluded. */
    record Content(long placedJobs, Map<String, Long> unplaced, List<MapPlace> places) { }

    public record MapPlace(String id, String name, String state, double lat, double lng, long jobs) { }

    public record MapSnapshot(String version, Instant updatedAt, long placedJobs, Map<String, Long> unplaced,
                              List<MapPlace> places) { }

    /** A published snapshot with its precomputed bodies; {@code etag} is already quoted. */
    public record Published(MapSnapshot snapshot, byte[] raw, byte[] gzip, String etag) { }
}
