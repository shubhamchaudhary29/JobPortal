package com.example.backend.geo.application;

import com.example.backend.geo.domain.Place;
import com.example.backend.shared.text.CompanyNames;
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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 * <p>Office pins (GEO-6) are applied here, at read time: jobs are grouped by places and lower-cased company, and a
 * group whose company key has exactly one enabled office in a place counts toward that office. City counts
 * ({@code MapPlace.jobs}, {@code placedJobs}) keep their meaning; {@code officeJobs} and {@code offices} are added.
 *
 * <p>Localities (GEO-8C): at each place a job counts toward its office there if it has one, else toward its
 * <em>first</em> locality in that place (resolver order), else the city centre. So per place
 * {@code jobs = officeJobs + localityJobs + centreJobs} and the place's {@code localities[].jobs} sum to
 * {@code localityJobs}.
 */
@Service
public class MapSnapshotService {
    private static final Logger log = LoggerFactory.getLogger(MapSnapshotService.class);
    static final long MAX_TIME_MS = 15_000;
    static final int MAX_COMPANY_VARIANTS = 5;
    static final int LARGE_FACET_ROWS = 100_000;

    private final MongoTemplate mongo;
    private final Gazetteer gazetteer;
    private final Clock clock;
    private final boolean enabled;
    private final long refreshMs;
    private final long officeRefreshDelayMs;
    private final Supplier<OfficeIndex> offices;
    private final AtomicReference<Map<String, Set<String>>> officeCompanies = new AtomicReference<>(Map.of());
    private ScheduledFuture<?> pendingOfficeRefresh;
    private final ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).disable(SerializationFeature.INDENT_OUTPUT).build();
    private final AtomicReference<Published> published = new AtomicReference<>();
    private final Timer buildTimer;
    private final Counter failures;
    private volatile Instant lastSuccess;
    private ScheduledExecutorService executor;

    /** Without office pins (tests and tools). */
    public MapSnapshotService(MongoTemplate mongo, Gazetteer gazetteer, MeterRegistry registry, Clock clock,
                              boolean enabled, long refreshMs) {
        this(mongo, gazetteer, registry, clock, enabled, refreshMs, 5_000, () -> OfficeIndex.EMPTY);
    }

    @Autowired
    public MapSnapshotService(MongoTemplate mongo, Gazetteer gazetteer, MeterRegistry registry, Clock clock,
                              @Value("${geo.snapshot.enabled:true}") boolean enabled,
                              @Value("${geo.snapshot.refresh-ms:300000}") long refreshMs,
                              @Value("${geo.snapshot.office-refresh-delay-ms:5000}") long officeRefreshDelayMs,
                              CompanyOfficeService offices) {
        this(mongo, gazetteer, registry, clock, enabled, refreshMs, officeRefreshDelayMs, offices::officeIndex);
    }

    private MapSnapshotService(MongoTemplate mongo, Gazetteer gazetteer, MeterRegistry registry, Clock clock,
                               boolean enabled, long refreshMs, long officeRefreshDelayMs, Supplier<OfficeIndex> offices) {
        if (refreshMs < 1_000) throw new IllegalArgumentException("geo.snapshot.refresh-ms must be at least 1000");
        if (officeRefreshDelayMs < 0) throw new IllegalArgumentException("geo.snapshot.office-refresh-delay-ms must not be negative");
        this.officeRefreshDelayMs = officeRefreshDelayMs;
        this.offices = offices;
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

    /**
     * Raw company spellings (up to {@value #MAX_COMPANY_VARIANTS} per place group) counted toward an office in the
     * last build; empty for an office the last build did not see.
     */
    public Set<String> officeCompanies(String officeId) { return officeCompanies.get().getOrDefault(officeId, Set.of()); }

    /**
     * An office change rebuilds the snapshot soon, debounced so a burst of edits costs one build. The fixed
     * schedule keeps running regardless.
     */
    @EventListener
    public synchronized void onOfficesChanged(CompanyOfficeService.OfficesChangedEvent event) {
        if (executor == null) return;
        if (pendingOfficeRefresh != null) pendingOfficeRefresh.cancel(false);
        pendingOfficeRefresh = executor.schedule(this::refreshSafely, officeRefreshDelayMs, TimeUnit.MILLISECONDS);
    }

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
        OfficeIndex officeIndex = offices.get();
        Map<String, Long> unplaced = GeoPipelines.zeroFilledStatuses();
        Map<String, Long> jobsByPlace = new TreeMap<>();
        Map<String, Long> officeJobsByPlace = new HashMap<>();
        Map<String, Long> jobsByOffice = new TreeMap<>();
        Map<String, Long> localityJobsByPlace = new HashMap<>();
        Map<String, Long> jobsByLocality = new TreeMap<>();
        Map<String, Set<String>> companiesByOffice = new HashMap<>();
        Map<String, Map<String, Long>> spellingsByOffice = new HashMap<>();
        long placedJobs = 0;
        long droppedJobs = 0;
        List<Document> placed = facets == null ? List.of() : facets.getList("placed", Document.class, List.of());
        if (placed.size() > LARGE_FACET_ROWS) log.warn("event=geo_snapshot_large_facet rows={}", placed.size());
        for (Document row : placed) {
            long count = ((Number) row.get("count")).longValue();
            Document group = row.get("_id", Document.class);
            List<String> known = group.getList("places", String.class, List.of()).stream()
                    .filter(id -> gazetteer.byId(id).isPresent()).toList();
            if (known.isEmpty()) { droppedJobs += count; continue; }
            placedJobs += count;
            String companyKey = CompanyNames.key(group.getString("company"));
            List<String> variants = row.getList("variants", String.class, List.of());
            List<LocalityIndex.Locality> localities = firstOccurrences(group.getList("localities", String.class, List.of()));
            for (String placeId : known) {
                jobsByPlace.merge(placeId, count, Long::sum);
                OfficeIndex.Office office = officeIndex.officeFor(companyKey, placeId).orElse(null);
                if (office != null) {
                    officeJobsByPlace.merge(placeId, count, Long::sum);
                    jobsByOffice.merge(office.id(), count, Long::sum);
                    variants.stream().filter(java.util.Objects::nonNull).forEach(variant ->
                            companiesByOffice.computeIfAbsent(office.id(), id -> new LinkedHashSet<>()).add(variant));
                    variants.stream().filter(java.util.Objects::nonNull).findFirst().ifPresent(variant -> spellingsByOffice
                            .computeIfAbsent(office.id(), id -> new HashMap<>()).merge(variant.trim(), count, Long::sum));
                    continue;
                }
                localities.stream().filter(locality -> locality.placeId().equals(placeId)).findFirst().ifPresent(locality -> {
                    localityJobsByPlace.merge(placeId, count, Long::sum);
                    jobsByLocality.merge(locality.id(), count, Long::sum);
                });
            }
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
                    place.lat(), place.lng(), jobs, officeJobsByPlace.getOrDefault(id, 0L), localityJobsByPlace.getOrDefault(id, 0L)));
        });
        List<MapLocality> mapLocalities = new ArrayList<>();
        jobsByLocality.forEach((id, jobs) -> gazetteer.localityById(id).ifPresent(locality -> mapLocalities.add(
                new MapLocality(id, locality.name(), locality.placeId(), locality.lat(), locality.lng(), jobs))));
        List<MapOffice> mapOffices = new ArrayList<>();
        // An OpenStreetMap office is named after the company's most common spelling in its jobs, not the OSM name.
        jobsByOffice.forEach((id, jobs) -> officeIndex.byId(id).ifPresent(office -> mapOffices.add(new MapOffice(id,
                office.fromOsm() ? mostCommon(spellingsByOffice.get(id), office.company()) : office.company(), office.label(),
                office.placeId(), office.lat(), office.lng(), jobs, office.source()))));
        Map<String, Set<String>> companies = new HashMap<>();
        companiesByOffice.forEach((id, variants) -> companies.put(id, Set.copyOf(variants)));
        officeCompanies.set(Map.copyOf(companies));
        // unmodifiableMap, not Map.copyOf: the status order is part of the payload.
        return new Content(placedJobs, Collections.unmodifiableMap(unplaced), List.copyOf(places), List.copyOf(mapOffices),
                List.copyOf(mapLocalities));
    }

    private static String mostCommon(Map<String, Long> spellings, String fallback) {
        if (spellings == null || spellings.isEmpty()) return fallback;
        return spellings.entrySet().stream().max(Map.Entry.<String, Long>comparingByValue()
                .thenComparing(Map.Entry.comparingByKey(java.util.Comparator.reverseOrder()))).map(Map.Entry::getKey).orElse(fallback);
    }

    /** Known localities in first-occurrence order (the pipeline concatenates listings, so ids can repeat). */
    private List<LocalityIndex.Locality> firstOccurrences(List<String> ids) {
        if (ids.isEmpty()) return List.of();
        List<LocalityIndex.Locality> localities = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (String id : ids) {
            if (id != null && seen.add(id)) gazetteer.localityById(id).ifPresent(localities::add);
        }
        return localities;
    }

    private List<Document> pipeline() {
        Document visible = GeoPipelines.visibleJobs();
        Document hasPlace = new Document("placeIds.0", new Document("$exists", true));
        Document noPlace = new Document("placeIds.0", new Document("$exists", false));
        return List.of(
                new Document("$match", visible),
                new Document("$project", new Document("_id", 0).append("placeIds", GeoPipelines.placeIds())
                        .append("localityIds", GeoPipelines.orderedLocalityIds())
                        .append("rank", GeoPipelines.bestRank()).append("company", 1)),
                new Document("$facet", new Document("placed", List.of(
                        new Document("$match", hasPlace),
                        new Document("$project", new Document("key",
                                new Document("$sortArray", new Document("input", "$placeIds").append("sortBy", 1)))
                                .append("lower", GeoPipelines.lowerCompany()).append("company", 1)
                                .append("localityIds", 1)),
                        new Document("$group", new Document("_id", new Document("places", "$key").append("company", "$lower")
                                .append("localities", "$localityIds"))
                                .append("count", new Document("$sum", 1))
                                .append("variants", new Document("$addToSet", "$company"))),
                        new Document("$project", new Document("count", 1).append("variants",
                                new Document("$slice", List.of("$variants", MAX_COMPANY_VARIANTS))))))
                        .append("unplaced", List.of(
                                new Document("$match", noPlace),
                                new Document("$group", new Document("_id", "$rank").append("count", new Document("$sum", 1)))))));
    }

    private void publish(Content content) throws IOException {
        String version = hash(json.writeValueAsBytes(content));
        Published current = published.get();
        if (current != null && current.snapshot().version().equals(version)) return;
        MapSnapshot snapshot = new MapSnapshot(version, clock.instant(), content.placedJobs(), content.unplaced(),
                content.places(), content.offices(), content.localities());
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
    record Content(long placedJobs, Map<String, Long> unplaced, List<MapPlace> places, List<MapOffice> offices,
                   List<MapLocality> localities) { }

    /**
     * A city with visible jobs. {@code jobs}: all of them (unchanged meaning); {@code officeJobs}: how many of those
     * belong to a pinned office; {@code localityJobs} (GEO-8C): how many of the rest sit at a locality, so
     * {@code jobs - officeJobs - localityJobs} are at the city centre ("office not listed").
     */
    public record MapPlace(String id, String name, String state, double lat, double lng, long jobs, long officeJobs,
                           long localityJobs) {
        public MapPlace(String id, String name, String state, double lat, double lng, long jobs) {
            this(id, name, state, lat, lng, jobs, 0, 0);
        }

        public MapPlace(String id, String name, String state, double lat, double lng, long jobs, long officeJobs) {
            this(id, name, state, lat, lng, jobs, officeJobs, 0);
        }

        /** Jobs at the city centre: no office and no locality in this city. */
        public long centreJobs() { return jobs - officeJobs - localityJobs; }
    }

    /** An OpenStreetMap locality (GEO-8C) that owns at least one visible job, at its own point. Sorted by id. */
    public record MapLocality(String id, String name, String placeId, double lat, double lng, long jobs) { }

    /**
     * An office that owns at least one visible job: an ADMIN pin, or (GEO-8D, {@code source} "OSM", id {@code osm-…}) an
     * auto-applied OpenStreetMap match, named after the company's most common spelling in its jobs.
     */
    public record MapOffice(String id, String company, String label, String placeId, double lat, double lng, long jobs,
                            String source) {
        public MapOffice(String id, String company, String label, String placeId, double lat, double lng, long jobs) {
            this(id, company, label, placeId, lat, lng, jobs, OfficeIndex.SOURCE_ADMIN);
        }
    }

    public record MapSnapshot(String version, Instant updatedAt, long placedJobs, Map<String, Long> unplaced,
                              List<MapPlace> places, List<MapOffice> offices, List<MapLocality> localities) {
        public MapSnapshot(String version, Instant updatedAt, long placedJobs, Map<String, Long> unplaced, List<MapPlace> places) {
            this(version, updatedAt, placedJobs, unplaced, places, List.of(), List.of());
        }

        public MapSnapshot(String version, Instant updatedAt, long placedJobs, Map<String, Long> unplaced, List<MapPlace> places,
                           List<MapOffice> offices) {
            this(version, updatedAt, placedJobs, unplaced, places, offices, List.of());
        }
    }

    /** A published snapshot with its precomputed bodies; {@code etag} is already quoted. */
    public record Published(MapSnapshot snapshot, byte[] raw, byte[] gzip, String etag) { }
}
