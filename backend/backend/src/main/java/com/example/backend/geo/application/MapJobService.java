package com.example.backend.geo.application;

import com.example.backend.geo.domain.Place;
import com.example.backend.shared.error.BadRequestException;
import com.example.backend.shared.error.ResourceNotFoundException;
import com.mongodb.MongoExecutionTimeoutException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.bson.Document;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Job lists for the map: jobs at a place, near a point, and without a place. Candidate places for "nearby" come
 * from the in-memory snapshot (no geo query); coordinates are rounded to a 0.05° grid before any use and are
 * never stored or logged. Results are cached per snapshot version.
 */
@Service
public class MapJobService {
    public static final String SNAPSHOT_UNAVAILABLE = "SNAPSHOT_UNAVAILABLE";
    public static final String QUERY_TIMEOUT = "QUERY_TIMEOUT";
    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 20;
    static final int MAX_PAGE = 49;
    static final int MAX_QUERY_LENGTH = 100;
    static final int DEFAULT_RADIUS_KM = 25;
    static final int MAX_RADIUS_KM = 100;
    static final int MAX_QUERY_PLACES = 200;
    static final int MAX_RETURNED_PLACES = 50;
    /** 0.05° grid (about 5 km of latitude): 20 steps per degree. */
    static final int GRID_STEPS_PER_DEGREE = 20;

    private final MapSnapshotService snapshots;
    private final MapJobQueries queries;
    private final MapJobCache cache;
    private final Gazetteer gazetteer;
    private final MeterRegistry registry;
    private final Counter timeouts;

    public MapJobService(MapSnapshotService snapshots, MapJobQueries queries, MapJobCache cache, Gazetteer gazetteer,
                         MeterRegistry registry) {
        this.snapshots = snapshots;
        this.queries = queries;
        this.cache = cache;
        this.gazetteer = gazetteer;
        this.registry = registry;
        this.timeouts = Counter.builder("jobportal.geo.mapjobs.timeouts").register(registry);
    }

    public MapJobSlice place(String placeId, String q, Integer page, Integer size) {
        int pageNumber = page(page);
        int pageSize = size(size);
        String phrase = phrase(q);
        Place place = gazetteer.byId(placeId).orElseThrow(() -> new ResourceNotFoundException("Place not found"));
        MapSnapshotService.MapSnapshot snapshot = snapshot();
        Rows rows = rows("place", snapshot, List.of(place.id()), phrase, pageNumber, pageSize);
        Long totalHint = phrase != null ? null
                : snapshot.places().stream().filter(candidate -> candidate.id().equals(place.id()))
                        .mapToLong(MapSnapshotService.MapPlace::jobs).findFirst().orElse(0L);
        List<MapJob> content = rows.rows().stream()
                .map(row -> row.toJob(List.of(new MapJobPlace(place.id(), place.name(), null)))).toList();
        return new MapJobSlice(content, pageNumber, pageSize, rows.hasNext(), totalHint);
    }

    public NearbyResult nearby(Double lat, Double lng, Integer radiusKm, String q, Integer page, Integer size) {
        if (lat == null || !Double.isFinite(lat) || lat < -90 || lat > 90) throw new BadRequestException("lat must be between -90 and 90");
        if (lng == null || !Double.isFinite(lng) || lng < -180 || lng > 180) throw new BadRequestException("lng must be between -180 and 180");
        int radius = radiusKm == null ? DEFAULT_RADIUS_KM : radiusKm;
        if (radius < 1 || radius > MAX_RADIUS_KM) throw new BadRequestException("radiusKm must be between 1 and " + MAX_RADIUS_KM);
        int pageNumber = page(page);
        int pageSize = size(size);
        String phrase = phrase(q);
        double centerLat = snap(lat);
        double centerLng = snap(lng);
        MapSnapshotService.MapSnapshot snapshot = snapshot();
        List<NearbyPlace> inRadius = new ArrayList<>();
        for (MapSnapshotService.MapPlace place : snapshot.places()) {
            double km = haversineKm(centerLat, centerLng, place.lat(), place.lng());
            if (km <= radius) inRadius.add(new NearbyPlace(place.id(), place.name(), place.state(), place.lat(),
                    place.lng(), Math.round(km * 10) / 10.0, place.jobs()));
        }
        inRadius.sort(Comparator.comparingDouble(NearbyPlace::distanceKm).thenComparing(NearbyPlace::id));
        List<NearbyPlace> candidates = inRadius.subList(0, Math.min(MAX_QUERY_PLACES, inRadius.size()));
        List<NearbyPlace> returned = List.copyOf(candidates.subList(0, Math.min(MAX_RETURNED_PLACES, candidates.size())));
        if (candidates.isEmpty()) {
            return new NearbyResult(centerLat, centerLng, radius, List.of(),
                    new MapJobSlice(List.of(), pageNumber, pageSize, false, null));
        }
        Map<String, NearbyPlace> byId = new HashMap<>();
        candidates.forEach(place -> byId.put(place.id(), place));
        Rows rows = rows("nearby", snapshot, byId.keySet(), phrase, pageNumber, pageSize);
        List<MapJob> content = rows.rows().stream().map(row -> row.toJob(row.placeIds().stream()
                        .map(byId::get).filter(java.util.Objects::nonNull)
                        .sorted(Comparator.comparingDouble(NearbyPlace::distanceKm).thenComparing(NearbyPlace::id))
                        .map(place -> new MapJobPlace(place.id(), place.name(), place.distanceKm())).toList()))
                .toList();
        return new NearbyResult(centerLat, centerLng, radius, returned,
                new MapJobSlice(content, pageNumber, pageSize, rows.hasNext(), null));
    }

    public MapJobSlice unplaced(String kind, String q, Integer page, Integer size) {
        List<String> statuses = switch (kind == null ? "" : kind) {
            case "remote" -> List.of("REMOTE");
            case "india" -> List.of("STATE", "COUNTRY");
            default -> throw new BadRequestException("kind must be remote or india");
        };
        int pageNumber = page(page);
        int pageSize = size(size);
        String phrase = phrase(q);
        MapSnapshotService.MapSnapshot snapshot = snapshot();
        List<Integer> ranks = statuses.stream().map(status -> GeoPipelines.STATUSES.indexOf(status) + 1).toList();
        MapJobCache.Key key = new MapJobCache.Key("unplaced", snapshot.version(), kind, cacheText(phrase), pageNumber, pageSize);
        Rows rows = cache.get(key, () -> timed("unplaced",
                () -> Rows.of(queries.unplaced(ranks, phrase, pageNumber, pageSize), pageSize)));
        Long totalHint = phrase != null ? null
                : statuses.stream().mapToLong(status -> snapshot.unplaced().getOrDefault(status, 0L)).sum();
        return new MapJobSlice(rows.rows().stream().map(row -> row.toJob(List.of())).toList(), pageNumber, pageSize,
                rows.hasNext(), totalHint);
    }

    /** Rounds to the nearest 0.05° grid line (half up); e.g. 12.9716 → 12.95 and 77.5946 → 77.60. */
    static double snap(double degrees) {
        return Math.round(degrees * GRID_STEPS_PER_DEGREE) / (double) GRID_STEPS_PER_DEGREE;
    }

    static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLng / 2), 2);
        return 6371.0088 * 2 * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    private Rows rows(String endpoint, MapSnapshotService.MapSnapshot snapshot, java.util.Collection<String> placeIds,
                      String phrase, int page, int size) {
        List<String> sorted = placeIds.stream().sorted().toList();
        MapJobCache.Key key = new MapJobCache.Key(endpoint, snapshot.version(), String.join(",", sorted),
                cacheText(phrase), page, size);
        return cache.get(key, () -> timed(endpoint, () -> Rows.of(queries.atPlaces(sorted, phrase, page, size), size)));
    }

    private Rows timed(String endpoint, Supplier<Rows> query) {
        Timer.Sample sample = Timer.start(registry);
        try {
            return query.get();
        } catch (MongoExecutionTimeoutException timeout) {
            timeouts.increment();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, QUERY_TIMEOUT);
        } finally {
            sample.stop(Timer.builder("jobportal.geo.mapjobs.query").tag("endpoint", endpoint).register(registry));
        }
    }

    private MapSnapshotService.MapSnapshot snapshot() {
        return snapshots.current().map(MapSnapshotService.Published::snapshot)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, SNAPSHOT_UNAVAILABLE));
    }

    private static int page(Integer page) {
        int value = page == null ? 0 : page;
        if (value < 0 || value > MAX_PAGE) throw new BadRequestException("page must be between 0 and " + MAX_PAGE);
        return value;
    }

    private static int size(Integer size) {
        int value = size == null ? DEFAULT_SIZE : size;
        if (value < 1 || value > MAX_SIZE) throw new BadRequestException("size must be between 1 and " + MAX_SIZE);
        return value;
    }

    /** Trimmed search phrase, or null. Quotes would end the MongoDB phrase early, so they become spaces. */
    static String phrase(String q) {
        if (q != null && q.length() > MAX_QUERY_LENGTH) throw new BadRequestException("q filter is too long");
        if (q == null) return null;
        String cleaned = q.replace('"', ' ').replace('\\', ' ').trim().replaceAll("\\s+", " ");
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String cacheText(String phrase) {
        return phrase == null ? "" : phrase.toLowerCase(Locale.ROOT);
    }

    /** One job row from the projection plus its places per the shared job definition. */
    record Row(String id, String title, String company, String location, String employmentType, Double salaryMin,
               Double salaryMax, String source, LocalDateTime createdAt, LocalDateTime publishedAt, Set<String> placeIds) {
        static Row of(Document document) {
            return new Row(String.valueOf(document.get("_id")), document.getString("title"), document.getString("company"),
                    document.getString("location"), document.getString("employmentType"), number(document.get("salaryMin")),
                    number(document.get("salaryMax")), document.getString("source"), time(document.get("createdAt")),
                    time(document.get("publishedAt")), placeIds(document));
        }

        MapJob toJob(List<MapJobPlace> places) {
            return new MapJob(id, title, company, location, employmentType, salaryMin, salaryMax, source, createdAt,
                    publishedAt, places);
        }

        /** Same rule as {@link GeoPipelines#placeIds()}: active listings, or top-level geo without listings. */
        private static Set<String> placeIds(Document document) {
            Set<String> ids = new LinkedHashSet<>();
            List<Document> listings = document.getList("sourceListings", Document.class);
            if (listings != null && !listings.isEmpty()) {
                for (Document listing : listings) {
                    if (Boolean.FALSE.equals(listing.get("active"))) continue;
                    Document geo = listing.get("geo", Document.class);
                    if (geo != null) ids.addAll(geo.getList("placeIds", String.class, List.of()));
                }
            } else {
                Document geo = document.get("geo", Document.class);
                if (geo != null) ids.addAll(geo.getList("placeIds", String.class, List.of()));
            }
            return Set.copyOf(ids);
        }

        private static Double number(Object value) { return value instanceof Number number ? number.doubleValue() : null; }

        private static LocalDateTime time(Object value) {
            return value instanceof Date date ? LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault()) : null;
        }
    }

    record Rows(List<Row> rows, boolean hasNext) {
        static Rows of(List<Document> documents, int size) {
            List<Row> mapped = documents.stream().limit(size).map(Row::of).toList();
            return new Rows(mapped, documents.size() > size);
        }
    }

    public record MapJobPlace(String id, String name, Double distanceKm) { }

    public record MapJob(String id, String title, String company, String location, String employmentType,
                         Double salaryMin, Double salaryMax, String source, LocalDateTime createdAt,
                         LocalDateTime publishedAt, List<MapJobPlace> places) { }

    public record MapJobSlice(List<MapJob> content, int page, int size, boolean hasNext, Long totalHint) { }

    public record NearbyPlace(String id, String name, String state, double lat, double lng, double distanceKm, long jobs) { }

    public record NearbyResult(double centerLat, double centerLng, int radiusKm, List<NearbyPlace> places,
                               MapJobSlice jobs) { }
}
