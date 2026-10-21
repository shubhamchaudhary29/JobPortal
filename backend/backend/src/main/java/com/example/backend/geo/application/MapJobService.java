package com.example.backend.geo.application;

import com.example.backend.geo.domain.Place;
import com.example.backend.shared.text.CompanyNames;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Job lists for the map: jobs at a place, near a point, and without a place. Candidate places for "nearby" come
 * from the in-memory snapshot (no geo query); coordinates are rounded to a 0.05° grid before any use and are
 * never stored or logged. Results are cached per snapshot version.
 *
 * <p>Every job place is shown at the most precise point known: the company's pinned office in that city, else the
 * job's first locality there (GEO-8C), else the city centre. Distances in nearby follow the same order.
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
    static final java.util.regex.Pattern LOCALITY_ID = java.util.regex.Pattern.compile("^osm-[nwr]\\d{1,15}$");

    private final MapSnapshotService snapshots;
    private final MapJobQueries queries;
    private final MapJobCache cache;
    private final Gazetteer gazetteer;
    private final MeterRegistry registry;
    private final Counter timeouts;
    private final java.util.function.Supplier<OfficeIndex> offices;

    /** Without office pins (tests). */
    public MapJobService(MapSnapshotService snapshots, MapJobQueries queries, MapJobCache cache, Gazetteer gazetteer,
                         MeterRegistry registry) {
        this(snapshots, queries, cache, gazetteer, registry, () -> OfficeIndex.EMPTY);
    }

    @Autowired
    public MapJobService(MapSnapshotService snapshots, MapJobQueries queries, MapJobCache cache, Gazetteer gazetteer,
                         MeterRegistry registry, CompanyOfficeService offices) {
        this(snapshots, queries, cache, gazetteer, registry, offices::officeIndex);
    }

    private MapJobService(MapSnapshotService snapshots, MapJobQueries queries, MapJobCache cache, Gazetteer gazetteer,
                          MeterRegistry registry, java.util.function.Supplier<OfficeIndex> offices) {
        this.offices = offices;
        this.snapshots = snapshots;
        this.queries = queries;
        this.cache = cache;
        this.gazetteer = gazetteer;
        this.registry = registry;
        this.timeouts = Counter.builder("jobportal.geo.mapjobs.timeouts").register(registry);
    }

    public MapJobSlice place(String placeId, String q, Integer page, Integer size) {
        return place(placeId, null, q, page, size);
    }

    /**
     * Jobs at one place. {@code scope} "all" (default) is every job there; "unlisted" only the city-centre jobs: no
     * pinned office and no locality in this city ("office not listed").
     */
    public MapJobSlice place(String placeId, String scope, String q, Integer page, Integer size) {
        boolean unlisted = switch (scope == null ? "all" : scope) {
            case "all" -> false;
            case "unlisted" -> true;
            default -> throw new BadRequestException("scope must be all or unlisted");
        };
        int pageNumber = page(page);
        int pageSize = size(size);
        String phrase = phrase(q);
        Place place = gazetteer.byId(placeId).orElseThrow(() -> new ResourceNotFoundException("Place not found"));
        MapSnapshotService.MapSnapshot snapshot = snapshot();
        MapSnapshotService.MapPlace counted = snapshot.places().stream().filter(candidate -> candidate.id().equals(place.id()))
                .findFirst().orElse(null);
        OfficeIndex index = offices.get();
        Rows rows;
        if (unlisted) {
            List<String> companies = officeCompaniesAt(index, place.id());
            List<String> localities = snapshot.localities().stream().filter(locality -> locality.placeId().equals(place.id()))
                    .map(MapSnapshotService.MapLocality::id).sorted().toList();
            String scopeKey = place.id() + "|" + String.join("\u0001", companies) + "|" + String.join(",", localities);
            MapJobCache.Key key = new MapJobCache.Key("place-unlisted", snapshot.version(), scopeKey, cacheText(phrase),
                    pageNumber, pageSize);
            rows = cache.get(key, () -> timed("place", () -> Rows.of(
                    queries.unlistedAtPlace(place.id(), companies, localities, phrase, pageNumber, pageSize), pageSize)));
        } else {
            rows = rows("place", snapshot, List.of(place.id()), phrase, pageNumber, pageSize);
        }
        Long totalHint = phrase != null ? null : counted == null ? 0L : unlisted ? counted.centreJobs() : counted.jobs();
        List<MapJob> content = rows.rows().stream().map(row -> row.toJob(List.of(jobPlace(row, place, index)))).toList();
        return new MapJobSlice(content, pageNumber, pageSize, rows.hasNext(), totalHint);
    }

    /**
     * Jobs at one OpenStreetMap locality: jobs whose localities include it, except those of companies with a pinned
     * office in its city (those show at the office). {@code totalHint} is the snapshot's count, which assigns a job with
     * several localities in one city to its first, so a list can hold a few more jobs than the hint.
     */
    public MapJobSlice locality(String localityId, String q, Integer page, Integer size) {
        if (localityId == null || !LOCALITY_ID.matcher(localityId).matches()) throw new BadRequestException("Invalid locality id");
        int pageNumber = page(page);
        int pageSize = size(size);
        String phrase = phrase(q);
        LocalityIndex.Locality locality = gazetteer.localityById(localityId)
                .orElseThrow(() -> new ResourceNotFoundException("Locality not found"));
        Place place = gazetteer.byId(locality.placeId()).orElseThrow(() -> new ResourceNotFoundException("Locality not found"));
        MapSnapshotService.MapSnapshot snapshot = snapshot();
        OfficeIndex index = offices.get();
        List<String> companies = officeCompaniesAt(index, place.id());
        MapJobCache.Key key = new MapJobCache.Key("locality", snapshot.version(),
                localityId + "|" + String.join("\u0001", companies), cacheText(phrase), pageNumber, pageSize);
        Rows rows = cache.get(key, () -> timed("locality", () -> Rows.of(queries.atPlaces(List.of(), List.of(),
                List.of(new MapJobQueries.LocalityScope(localityId, companies)), phrase, pageNumber, pageSize), pageSize)));
        Long totalHint = phrase != null ? null : snapshot.localities().stream()
                .filter(candidate -> candidate.id().equals(localityId)).mapToLong(MapSnapshotService.MapLocality::jobs)
                .findFirst().orElse(0L);
        MapJobPlace at = new MapJobPlace(place.id(), place.name(), null, null, null, locality.id(), locality.name());
        List<MapJob> content = rows.rows().stream().map(row -> row.toJob(List.of(at))).toList();
        return new MapJobSlice(content, pageNumber, pageSize, rows.hasNext(), totalHint);
    }

    /** A job's place in a list: the company's office there, else its first locality there, else the city. */
    private MapJobPlace jobPlace(Row row, Place place, OfficeIndex index) {
        OfficeIndex.Office office = index.officeFor(CompanyNames.key(row.company()), place.id()).orElse(null);
        if (office != null) return new MapJobPlace(place.id(), place.name(), null, office.id(), office.label());
        LocalityIndex.Locality locality = firstLocality(row, place.id());
        if (locality != null) return new MapJobPlace(place.id(), place.name(), null, null, null, locality.id(), locality.name());
        return new MapJobPlace(place.id(), place.name(), null);
    }

    private LocalityIndex.Locality firstLocality(Row row, String placeId) {
        for (String id : row.localityIds()) {
            LocalityIndex.Locality locality = gazetteer.localityById(id).orElse(null);
            if (locality != null && locality.placeId().equals(placeId)) return locality;
        }
        return null;
    }

    /** Company spellings the last snapshot counted toward pinned offices in this city (sorted, for stable cache keys). */
    private List<String> officeCompaniesAt(OfficeIndex index, String placeId) {
        return index.resolvable().stream().filter(office -> office.placeId().equals(placeId))
                .flatMap(office -> snapshots.officeCompanies(office.id()).stream()).distinct().sorted().toList();
    }

    /**
     * Jobs at one pinned office: the place filter for the office's city AND a company spelling the last snapshot
     * counted toward this office. A spelling first seen after the last snapshot shows up after the next refresh.
     * Unknown, disabled or ambiguous offices (they own no jobs) are 404.
     */
    public MapJobSlice office(String officeId, String q, Integer page, Integer size) {
        int pageNumber = page(page);
        int pageSize = size(size);
        String phrase = phrase(q);
        if (officeId == null || officeId.isBlank() || officeId.length() > 64) throw new ResourceNotFoundException("Office not found");
        OfficeIndex index = offices.get();
        OfficeIndex.Office office = index.byId(officeId)
                .filter(found -> index.officeFor(found.companyKey(), found.placeId()).map(OfficeIndex.Office::id)
                        .filter(officeId::equals).isPresent())
                .orElseThrow(() -> new ResourceNotFoundException("Office not found"));
        Place place = gazetteer.byId(office.placeId()).orElseThrow(() -> new ResourceNotFoundException("Office not found"));
        MapSnapshotService.MapSnapshot snapshot = snapshot();
        List<String> companies = snapshots.officeCompanies(officeId).stream().sorted().toList();
        MapJobCache.Key key = new MapJobCache.Key("office", snapshot.version(), officeId, cacheText(phrase), pageNumber, pageSize);
        Rows rows = companies.isEmpty() ? new Rows(List.of(), false) : cache.get(key, () -> timed("office", () -> Rows.of(
                queries.atPlaces(List.of(), List.of(new MapJobQueries.OfficeScope(place.id(), companies)), phrase,
                        pageNumber, pageSize), pageSize)));
        Long totalHint = phrase != null ? null : snapshot.offices().stream().filter(candidate -> candidate.id().equals(officeId))
                .mapToLong(MapSnapshotService.MapOffice::jobs).findFirst().orElse(0L);
        List<MapJob> content = rows.rows().stream()
                .map(row -> row.toJob(List.of(new MapJobPlace(place.id(), place.name(), null, office.id(), office.label()))))
                .toList();
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
        inRadius.sort(BY_DISTANCE);
        List<NearbyPlace> candidates = inRadius.subList(0, Math.min(MAX_QUERY_PLACES, inRadius.size()));
        Map<String, NearbyPlace> byId = new HashMap<>();
        candidates.forEach(place -> byId.put(place.id(), place));
        // Offices use their own point and job count; their city joins the query even when its centre is out of range.
        List<NearbyPlace> officesInRadius = new ArrayList<>();
        Map<String, String> officeCityNames = new HashMap<>();
        for (MapSnapshotService.MapOffice office : snapshot.offices()) {
            double km = haversineKm(centerLat, centerLng, office.lat(), office.lng());
            if (km > radius) continue;
            Place city = gazetteer.byId(office.placeId()).orElse(null);
            if (city == null) continue;
            officeCityNames.put(city.id(), city.name());
            officesInRadius.add(new NearbyPlace(city.id(), city.name(), gazetteer.admin1Name(city.admin1()).orElse(null),
                    office.lat(), office.lng(), Math.round(km * 10) / 10.0, office.jobs(), "office", office.id(),
                    office.company(), office.label()));
        }
        officesInRadius.sort(BY_DISTANCE);
        // Localities too: their own point and job count; a locality in range brings its jobs even if its city is not.
        List<NearbyPlace> localitiesInRadius = new ArrayList<>();
        for (MapSnapshotService.MapLocality locality : snapshot.localities()) {
            double km = haversineKm(centerLat, centerLng, locality.lat(), locality.lng());
            if (km > radius) continue;
            Place city = gazetteer.byId(locality.placeId()).orElse(null);
            if (city == null) continue;
            officeCityNames.putIfAbsent(city.id(), city.name());
            localitiesInRadius.add(new NearbyPlace(city.id(), city.name(), gazetteer.admin1Name(city.admin1()).orElse(null),
                    locality.lat(), locality.lng(), Math.round(km * 10) / 10.0, locality.jobs(), "locality", null, null,
                    locality.name(), locality.id()));
        }
        localitiesInRadius.sort(BY_DISTANCE);
        List<NearbyPlace> chips = new ArrayList<>(candidates);
        chips.addAll(officesInRadius);
        chips.addAll(localitiesInRadius);
        chips.sort(BY_DISTANCE);
        List<NearbyPlace> returned = List.copyOf(chips.subList(0, Math.min(MAX_RETURNED_PLACES, chips.size())));
        List<MapJobQueries.OfficeScope> officeScopes = officesInRadius.stream()
                .filter(office -> !byId.containsKey(office.id()))
                .map(office -> new MapJobQueries.OfficeScope(office.id(),
                        snapshots.officeCompanies(office.officeId()).stream().sorted().toList()))
                .filter(scope -> !scope.companies().isEmpty())
                .limit(MAX_QUERY_PLACES).toList();
        OfficeIndex index = offices.get();
        List<MapJobQueries.LocalityScope> localityScopes = localitiesInRadius.stream()
                .filter(locality -> !byId.containsKey(locality.id()))
                .map(locality -> new MapJobQueries.LocalityScope(locality.localityId(), officeCompaniesAt(index, locality.id())))
                .limit(MAX_QUERY_PLACES).toList();
        if (candidates.isEmpty() && officeScopes.isEmpty() && localityScopes.isEmpty()) {
            return new NearbyResult(centerLat, centerLng, radius, returned,
                    new MapJobSlice(List.of(), pageNumber, pageSize, false, null));
        }
        Rows rows = officeScopes.isEmpty() && localityScopes.isEmpty()
                ? rows("nearby", snapshot, byId.keySet(), phrase, pageNumber, pageSize)
                : nearbyWithScopes(snapshot, byId.keySet(), officeScopes, localityScopes, phrase, pageNumber, pageSize);
        List<MapJob> content = rows.rows().stream().map(row -> row.toJob(row.placeIds().stream()
                        .map(placeId -> nearbyJobPlace(row, placeId, byId, officeCityNames, index, centerLat, centerLng))
                        .filter(java.util.Objects::nonNull)
                        .sorted(Comparator.comparingDouble(MapJobPlace::distanceKm).thenComparing(MapJobPlace::id))
                        .toList()))
                .toList();
        return new NearbyResult(centerLat, centerLng, radius, returned,
                new MapJobSlice(content, pageNumber, pageSize, rows.hasNext(), null));
    }

    private static final Comparator<NearbyPlace> BY_DISTANCE = Comparator.comparingDouble(NearbyPlace::distanceKm)
            .thenComparing(NearbyPlace::id).thenComparing(place -> place.officeId() == null ? "" : place.officeId())
            .thenComparing(place -> place.localityId() == null ? "" : place.localityId());

    /**
     * A job's place in a nearby result: the office point when the company has one there, else its first locality there,
     * else the city centre; distances follow the same order.
     */
    private MapJobPlace nearbyJobPlace(Row row, String placeId, Map<String, NearbyPlace> cities,
                                       Map<String, String> extraCityNames, OfficeIndex index, double lat, double lng) {
        NearbyPlace city = cities.get(placeId);
        String name = city != null ? city.name() : extraCityNames.get(placeId);
        if (name == null) return null;
        OfficeIndex.Office office = index.officeFor(CompanyNames.key(row.company()), placeId).orElse(null);
        if (office != null) {
            double km = Math.round(haversineKm(lat, lng, office.lat(), office.lng()) * 10) / 10.0;
            return new MapJobPlace(placeId, name, km, office.id(), office.label());
        }
        LocalityIndex.Locality locality = firstLocality(row, placeId);
        if (locality != null) {
            double km = Math.round(haversineKm(lat, lng, locality.lat(), locality.lng()) * 10) / 10.0;
            return new MapJobPlace(placeId, name, km, null, null, locality.id(), locality.name());
        }
        return city == null ? null : new MapJobPlace(placeId, name, city.distanceKm());
    }

    private Rows nearbyWithScopes(MapSnapshotService.MapSnapshot snapshot, java.util.Collection<String> placeIds,
                                  List<MapJobQueries.OfficeScope> offices, List<MapJobQueries.LocalityScope> localities,
                                  String phrase, int page, int size) {
        List<String> sorted = placeIds.stream().sorted().toList();
        String scope = String.join(",", sorted) + "|" + String.join(",", offices.stream()
                .map(office -> office.placeId() + ":" + String.join("\u0001", office.companies())).sorted().toList())
                + "|" + String.join(",", localities.stream()
                .map(locality -> locality.localityId() + ":" + String.join("\u0001", locality.excludedCompanies())).sorted().toList());
        MapJobCache.Key key = new MapJobCache.Key("nearby", snapshot.version(), scope, cacheText(phrase), page, size);
        return cache.get(key, () -> timed("nearby", () -> Rows.of(queries.atPlaces(sorted, offices, localities, phrase, page, size), size)));
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
               Double salaryMax, String source, LocalDateTime createdAt, LocalDateTime publishedAt, Set<String> placeIds,
               List<String> localityIds) {
        static Row of(Document document) {
            return new Row(String.valueOf(document.get("_id")), document.getString("title"), document.getString("company"),
                    document.getString("location"), document.getString("employmentType"), number(document.get("salaryMin")),
                    number(document.get("salaryMax")), document.getString("source"), time(document.get("createdAt")),
                    time(document.get("publishedAt")), placeIds(document), localityIds(document));
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

        /** Same rule as places, in first-occurrence order (listing order, then resolver order). */
        private static List<String> localityIds(Document document) {
            Set<String> ids = new LinkedHashSet<>();
            List<Document> listings = document.getList("sourceListings", Document.class);
            if (listings != null && !listings.isEmpty()) {
                for (Document listing : listings) {
                    if (Boolean.FALSE.equals(listing.get("active"))) continue;
                    Document geo = listing.get("geo", Document.class);
                    if (geo != null) ids.addAll(geo.getList("localityIds", String.class, List.of()));
                }
            } else {
                Document geo = document.get("geo", Document.class);
                if (geo != null) ids.addAll(geo.getList("localityIds", String.class, List.of()));
            }
            return List.copyOf(ids);
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

    /**
     * {@code officeId}/{@code officeLabel}: set when the job's company has a pinned office at this place;
     * otherwise {@code localityId}/{@code localityName} when the job names a locality here; neither means the listing
     * only names the city ("office not listed").
     */
    public record MapJobPlace(String id, String name, Double distanceKm, String officeId, String officeLabel,
                              String localityId, String localityName) {
        public MapJobPlace(String id, String name, Double distanceKm) { this(id, name, distanceKm, null, null, null, null); }

        public MapJobPlace(String id, String name, Double distanceKm, String officeId, String officeLabel) {
            this(id, name, distanceKm, officeId, officeLabel, null, null);
        }
    }

    public record MapJob(String id, String title, String company, String location, String employmentType,
                         Double salaryMin, Double salaryMax, String source, LocalDateTime createdAt,
                         LocalDateTime publishedAt, List<MapJobPlace> places) { }

    public record MapJobSlice(List<MapJob> content, int page, int size, boolean hasNext, Long totalHint) { }

    /**
     * A nearby chip: a city ({@code kind} "city", its centre and all its jobs), a pinned office ({@code kind}
     * "office": {@code id} is the office's city, {@code lat}/{@code lng}/{@code jobs} are the office's own) or a
     * locality ({@code kind} "locality", GEO-8C: {@code id} is its city, {@code localityId} and {@code label} name it,
     * point and jobs are its own).
     */
    public record NearbyPlace(String id, String name, String state, double lat, double lng, double distanceKm, long jobs,
                              String kind, String officeId, String company, String label, String localityId) {
        public NearbyPlace(String id, String name, String state, double lat, double lng, double distanceKm, long jobs) {
            this(id, name, state, lat, lng, distanceKm, jobs, "city", null, null, null, null);
        }

        public NearbyPlace(String id, String name, String state, double lat, double lng, double distanceKm, long jobs,
                           String kind, String officeId, String company, String label) {
            this(id, name, state, lat, lng, distanceKm, jobs, kind, officeId, company, label, null);
        }
    }

    public record NearbyResult(double centerLat, double centerLng, int radiusKm, List<NearbyPlace> places,
                               MapJobSlice jobs) { }
}
