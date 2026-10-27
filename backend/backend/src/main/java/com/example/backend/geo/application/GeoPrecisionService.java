package com.example.backend.geo.application;

import com.example.backend.geo.domain.Place;
import com.example.backend.shared.text.CompanyNames;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

/**
 * How precisely visible jobs can be placed (GEO-8): at an office, a locality, a city centre, or only a state, the
 * country, remote, foreign or not at all. ADMIN report, computed by two bounded aggregations and cached five minutes.
 * The job definition is the snapshot's ({@link GeoPipelines}); offices come from the current {@link OfficeIndex}.
 *
 * <p>{@code topSiteLabels} (GEO-9D) is the worklist for per-site offices (GEO-9C): the commonest (company, city, site
 * label) of city-centre jobs, from a third bounded aggregation.
 */
@Service
public class GeoPrecisionService {
    static final Duration CACHE_TTL = Duration.ofMinutes(5);
    static final long MAX_TIME_MS = 15_000;
    static final int TOP_STRINGS = 50;
    static final int TOP_SITE_LABELS = 50;
    static final int TOP_CITIES = 10;
    static final int CITY_STRINGS = 500;
    static final int MAX_LOCALITY_WORDS = 5;
    static final double OFF_CENTRE_KM = 1.5;
    static final double NEAR_LOCALITY_KM = 2.0;
    static final List<String> PRECISIONS = List.of("office", "locality", "city", "state", "country", "remote", "foreign",
            "unresolved");
    static final Set<String> NCR_STRINGS = Set.of("ncr", "delhi ncr", "delhi-ncr");

    private final MongoTemplate mongo;
    private final Gazetteer gazetteer;
    private final GeoResolver resolver;
    private final Supplier<OfficeIndex> offices;
    private final Clock clock;
    private final int reResolutionJobsPerHour;
    private volatile Precision cached;
    private volatile Instant cachedAt = Instant.MIN;

    @Autowired
    public GeoPrecisionService(MongoTemplate mongo, Gazetteer gazetteer, GeoResolver resolver,
                               CompanyOfficeService offices, Clock clock,
                               @Value("${geo.reresolution.batch-size:200}") int batchSize,
                               @Value("${geo.reresolution.max-batches:25}") int maxBatches,
                               @Value("${geo.reresolution.delay-ms:600000}") long delayMs) {
        this(mongo, gazetteer, resolver, offices::officeIndex, clock,
                (int) Math.max(1, batchSize * (long) maxBatches * 3_600_000L / Math.max(1, delayMs)));
    }

    GeoPrecisionService(MongoTemplate mongo, Gazetteer gazetteer, GeoResolver resolver, Supplier<OfficeIndex> offices,
                        Clock clock, int reResolutionJobsPerHour) {
        this.mongo = mongo;
        this.gazetteer = gazetteer;
        this.resolver = resolver;
        this.offices = offices;
        this.clock = clock;
        this.reResolutionJobsPerHour = reResolutionJobsPerHour;
    }

    public synchronized Precision precision() {
        Instant now = clock.instant();
        Precision current = cached;
        if (current != null && now.isBefore(cachedAt.plus(CACHE_TTL))) return current;
        Precision computed = compute(now);
        cached = computed;
        cachedAt = now;
        return computed;
    }

    private Precision compute(Instant now) {
        OfficeIndex officeIndex = offices.get();
        String delhi = gazetteer.aliasMatches("delhi").stream().map(Place::id).findFirst().orElse(null);
        Map<String, Long> byPrecision = new LinkedHashMap<>();
        PRECISIONS.forEach(precision -> byPrecision.put(precision, 0L));
        Map<String, Tally> centreStrings = new HashMap<>();
        Map<String, Long> jobsByPlace = new HashMap<>();
        Map<String, Long> centreByPlace = new HashMap<>();
        Map<String, Map<String, Tally>> centreStringsByPlace = new HashMap<>();
        long activeJobs = 0;
        long ncrJobs = 0;
        long delhiJobs = 0;
        for (Document group : mongo.getCollection("jobs").aggregate(groupsPipeline())
                .maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS).allowDiskUse(true)) {
            Document id = group.get("_id", Document.class);
            long count = ((Number) group.get("count")).longValue();
            String sample = group.getString("sample");
            String lowerLocation = id.getString("location");
            activeJobs += count;
            List<String> places = id.getList("places", String.class, List.of()).stream()
                    .filter(place -> gazetteer.byId(place).isPresent()).toList();
            Set<String> localityPlaces = new java.util.HashSet<>();
            for (String localityId : id.getList("localities", String.class, List.of())) {
                gazetteer.localityById(localityId).ifPresent(locality -> localityPlaces.add(locality.placeId()));
            }
            if (lowerLocation != null && NCR_STRINGS.contains(lowerLocation)) ncrJobs += count;
            if (delhi != null && places.contains(delhi)) delhiJobs += count;
            String precision;
            if (places.isEmpty()) {
                precision = statusPrecision(GeoPipelines.statusForRank(((Number) id.get("rank")).intValue()));
            } else {
                String companyKey = CompanyNames.key(id.getString("company"));
                boolean office = false;
                boolean locality = false;
                for (String place : places) {
                    jobsByPlace.merge(place, count, Long::sum);
                    if (officeIndex.officeFor(companyKey, place).isPresent()) { office = true; continue; }
                    if (localityPlaces.contains(place)) { locality = true; continue; }
                    centreByPlace.merge(place, count, Long::sum);
                    if (lowerLocation != null && !lowerLocation.isEmpty()) {
                        centreStringsByPlace.computeIfAbsent(place, ignored -> new HashMap<>())
                                .computeIfAbsent(lowerLocation, ignored -> new Tally(sample)).jobs += count;
                    }
                }
                precision = office ? "office" : locality ? "locality" : "city";
                if (precision.equals("city") && lowerLocation != null && !lowerLocation.isEmpty()) {
                    centreStrings.computeIfAbsent(lowerLocation, ignored -> new Tally(sample)).jobs += count;
                }
            }
            byPrecision.merge(precision, count, Long::sum);
        }
        List<LocationCount> topStrings = top(centreStrings, TOP_STRINGS);
        List<CityGain> cities = jobsByPlace.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP_CITIES)
                .map(entry -> cityGain(entry.getKey(), entry.getValue(), centreByPlace.getOrDefault(entry.getKey(), 0L),
                        centreStringsByPlace.getOrDefault(entry.getKey(), Map.of())))
                .toList();
        double ncrPercent = delhiJobs == 0 ? 0.0 : Math.round(ncrJobs * 1000.0 / delhiJobs) / 10.0;
        double hours = Math.round(activeJobs * 10.0 / reResolutionJobsPerHour) / 10.0;
        return new Precision(resolver.version(), now, activeJobs, byPrecision, topStrings, cities, adzuna(),
                new Ncr(ncrJobs, delhiJobs, ncrPercent), new ReResolution(reResolutionJobsPerHour, hours),
                topSiteLabels(officeIndex));
    }

    /**
     * City-precision jobs per (company, place, site label): no pinned office of the company there and none of the job's
     * localities in that place, same as {@code byPrecision}. A job's label for a place is its first active listing's
     * there (or the listing-less top-level geo), so a job counts once per place.
     */
    private List<SiteLabelCount> topSiteLabels(OfficeIndex officeIndex) {
        Map<List<String>, SiteLabelCount> counts = new HashMap<>();
        for (Document group : mongo.getCollection("jobs").aggregate(siteLabelPipeline())
                .maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS).allowDiskUse(true)) {
            Document id = group.get("_id", Document.class);
            String placeId = id.getString("place");
            String siteLabel = id.getString("siteLabel");
            Place place = placeId == null ? null : gazetteer.byId(placeId).orElse(null);
            if (place == null || siteLabel == null) continue;
            String company = group.getString("company") == null ? "" : group.getString("company").trim();
            if (officeIndex.officeFor(CompanyNames.key(company), placeId).isPresent()) continue;
            boolean atLocality = id.getList("localities", String.class, List.of()).stream()
                    .anyMatch(localityId -> gazetteer.localityById(localityId)
                            .map(locality -> locality.placeId().equals(placeId)).orElse(false));
            if (atLocality) continue;
            long jobs = ((Number) group.get("count")).longValue();
            counts.merge(List.of(id.getString("company"), placeId, siteLabel),
                    new SiteLabelCount(company, placeId, place.name(), siteLabel, jobs),
                    (left, right) -> new SiteLabelCount(preferCapitalized(left.company(), right.company()),
                            placeId, place.name(), siteLabel, left.jobs() + right.jobs()));
        }
        return counts.values().stream()
                .sorted(Comparator.comparingLong(SiteLabelCount::jobs).reversed()
                        .thenComparing(SiteLabelCount::company).thenComparing(SiteLabelCount::placeId)
                        .thenComparing(SiteLabelCount::siteLabel))
                .limit(TOP_SITE_LABELS).toList();
    }

    private static String preferCapitalized(String left, String right) {
        if (left == null || left.isBlank()) return right == null ? "" : right.trim();
        if (right == null || right.isBlank()) return left.trim();
        boolean leftUpper = Character.isUpperCase(left.charAt(0));
        boolean rightUpper = Character.isUpperCase(right.charAt(0));
        if (leftUpper != rightUpper) return leftUpper ? left : right;
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static List<Document> siteLabelPipeline() {
        Document geos = new Document("$cond", List.of(GeoPipelines.hasListings(),
                new Document("$map", new Document("input", GeoPipelines.activeListings()).append("as", "l")
                        .append("in", new Document("$ifNull", List.of("$$l.geo", new Document())))),
                List.of(new Document("$ifNull", List.of("$geo", new Document())))));
        return List.of(
                new Document("$match", new Document("$and", List.of(GeoPipelines.visibleJobs(), new Document("$or", List.of(
                        new Document("sourceListings.geo.siteLabel", new Document("$type", "string")),
                        new Document("geo.siteLabel", new Document("$type", "string"))))))),
                new Document("$project", new Document("company", GeoPipelines.lowerCompany()).append("raw", "$company")
                        .append("localities", new Document("$sortArray", new Document("input", GeoPipelines.localityIds())
                                .append("sortBy", 1)))
                        .append("geos", geos)),
                new Document("$unwind", "$geos"),
                new Document("$unwind", "$geos.placeIds"),
                // First listing per (job, place), in listing order: its label, possibly none.
                new Document("$group", new Document("_id", new Document("job", "$_id").append("place", "$geos.placeIds"))
                        .append("siteLabel", new Document("$first", "$geos.siteLabel"))
                        .append("company", new Document("$first", "$company")).append("raw", new Document("$first", "$raw"))
                        .append("localities", new Document("$first", "$localities"))),
                new Document("$match", new Document("siteLabel", new Document("$type", "string"))),
                new Document("$sort", new Document("raw", 1)),
                new Document("$group", new Document("_id", new Document("company", "$company").append("place", "$_id.place")
                        .append("siteLabel", "$siteLabel").append("localities", "$localities"))
                        .append("count", new Document("$sum", 1)).append("company", new Document("$first", "$raw"))));
    }

    private CityGain cityGain(String placeId, long jobs, long centreJobs, Map<String, Tally> strings) {
        Place place = gazetteer.byId(placeId).orElseThrow();
        List<Map.Entry<String, Tally>> top = strings.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, Tally> entry) -> entry.getValue().jobs).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(CITY_STRINGS).toList();
        long matchingJobs = 0;
        long matchingStrings = 0;
        long scannedJobs = 0;
        for (Map.Entry<String, Tally> entry : top) {
            scannedJobs += entry.getValue().jobs;
            if (mentionsLocality(entry.getKey(), placeId)) {
                matchingJobs += entry.getValue().jobs;
                matchingStrings++;
            }
        }
        return new CityGain(placeId, place.name(), jobs, centreJobs, top.size(), scannedJobs, matchingStrings,
                matchingJobs, gazetteer.localitiesInPlace(placeId).size());
    }

    /** True when some run of 1–5 consecutive words of the string is a known locality name of this place. */
    boolean mentionsLocality(String text, String placeId) {
        String[] words = Gazetteer.key(text).split(" ");
        for (int start = 0; start < words.length; start++) {
            StringBuilder phrase = new StringBuilder();
            for (int end = start; end < Math.min(words.length, start + MAX_LOCALITY_WORDS); end++) {
                if (end > start) phrase.append(' ');
                phrase.append(words[end]);
                if (!gazetteer.localityMatches(phrase.toString(), placeId).isEmpty()) return true;
            }
        }
        return false;
    }

    private AdzunaPoints adzuna() {
        List<Document> pipeline = List.of(
                new Document("$match", new Document("$and", List.of(GeoPipelines.visibleJobs(),
                        new Document("sourceListings.provider", "adzuna")))),
                new Document("$project", new Document("_id", 0).append("listing", new Document("$first",
                        new Document("$filter", new Document("input", GeoPipelines.activeListings()).append("as", "l")
                                .append("cond", new Document("$eq", List.of("$$l.provider", "adzuna"))))))),
                new Document("$match", new Document("listing", new Document("$ne", null))),
                new Document("$project", new Document("point", "$listing.geo.point")
                        .append("places", "$listing.geo.placeIds").append("localities", "$listing.geo.localityIds")));
        long jobs = 0;
        long withPoint = 0;
        long offCentre = 0;
        long nearLocality = 0;
        long snapped = 0;
        for (Document row : mongo.getCollection("jobs").aggregate(pipeline)
                .maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS).allowDiskUse(true)) {
            jobs++;
            Document point = row.get("point", Document.class);
            if (point == null || !(point.get("lat") instanceof Number lat) || !(point.get("lng") instanceof Number lng)) continue;
            withPoint++;
            List<String> places = row.getList("places", String.class, List.of());
            if (places.size() != 1) continue;
            Place place = gazetteer.byId(places.get(0)).orElse(null);
            if (place == null) continue;
            if (MapJobService.haversineKm(lat.doubleValue(), lng.doubleValue(), place.lat(), place.lng()) < OFF_CENTRE_KM) continue;
            offCentre++;
            if (gazetteer.nearestLocality(place.id(), lat.doubleValue(), lng.doubleValue(), NEAR_LOCALITY_KM).isPresent()) {
                nearLocality++;
            }
            if (!row.getList("localities", String.class, List.of()).isEmpty()) snapped++;
        }
        return new AdzunaPoints(jobs, withPoint, offCentre, nearLocality, snapped);
    }

    private static List<Document> groupsPipeline() {
        Document location = new Document("$toLower", new Document("$trim",
                new Document("input", new Document("$ifNull", List.of("$location", "")))));
        return List.of(
                new Document("$match", GeoPipelines.visibleJobs()),
                new Document("$project", new Document("_id", 0)
                        .append("places", new Document("$sortArray", new Document("input", GeoPipelines.placeIds()).append("sortBy", 1)))
                        .append("localities", new Document("$sortArray", new Document("input", GeoPipelines.localityIds()).append("sortBy", 1)))
                        .append("rank", GeoPipelines.bestRank()).append("company", GeoPipelines.lowerCompany())
                        .append("location", location).append("raw", "$location")),
                new Document("$group", new Document("_id", new Document("places", "$places").append("localities", "$localities")
                        .append("rank", "$rank").append("company", "$company").append("location", "$location"))
                        .append("count", new Document("$sum", 1)).append("sample", new Document("$first", "$raw"))));
    }

    private static String statusPrecision(String status) {
        return switch (status) {
            case "STATE" -> "state";
            case "COUNTRY" -> "country";
            case "REMOTE" -> "remote";
            case "FOREIGN" -> "foreign";
            default -> "unresolved";
        };
    }

    private static List<LocationCount> top(Map<String, Tally> tallies, int limit) {
        return tallies.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, Tally> entry) -> entry.getValue().jobs).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(limit).map(entry -> new LocationCount(entry.getValue().sample, entry.getValue().jobs)).toList();
    }

    private static final class Tally {
        final String sample;
        long jobs;
        Tally(String sample) { this.sample = sample == null ? "" : sample.trim(); }
    }

    /**
     * {@code byPrecision}: visible jobs by their best placement over their places (office &gt; locality &gt; city);
     * jobs without a place by status ({@code unresolved} includes EMPTY and not yet resolved).
     */
    public record Precision(String resolverVersion, Instant computedAt, long activeJobs, Map<String, Long> byPrecision,
                            List<LocationCount> topCityCentreStrings, List<CityGain> topCities, AdzunaPoints adzuna,
                            Ncr ncr, ReResolution reResolution, List<SiteLabelCount> topSiteLabels) {
        public Precision {
            topSiteLabels = topSiteLabels == null ? List.of() : List.copyOf(topSiteLabels);
        }

        /** Without site labels (pre-GEO-9 shape). */
        public Precision(String resolverVersion, Instant computedAt, long activeJobs, Map<String, Long> byPrecision,
                         List<LocationCount> topCityCentreStrings, List<CityGain> topCities, AdzunaPoints adzuna, Ncr ncr,
                         ReResolution reResolution) {
            this(resolverVersion, computedAt, activeJobs, byPrecision, topCityCentreStrings, topCities, adzuna, ncr,
                    reResolution, List.of());
        }
    }

    /**
     * City-centre jobs of one company in one city whose listings name the same site ("We Work"); {@code company} is one
     * spelling of the case-insensitive group. The top {@value #TOP_SITE_LABELS} are the GEO-9C worklist.
     */
    public record SiteLabelCount(String company, String placeId, String placeName, String siteLabel, long jobs) { }

    public record LocationCount(String location, long jobs) { }

    /**
     * One of the ten cities with the most jobs: {@code centreJobs} sit at its centre; among the top
     * {@code stringsScanned} centre-job location strings ({@code jobsScanned} jobs), {@code stringsWithLocality}
     * strings ({@code jobsWithLocality} jobs) name a locality the city has in {@code in-localities.tsv}.
     */
    public record CityGain(String placeId, String name, long jobs, long centreJobs, long stringsScanned, long jobsScanned,
                           long stringsWithLocality, long jobsWithLocality, long knownLocalities) { }

    /**
     * Adzuna jobs (first active Adzuna listing): with a stored point; of those with one place, how many points are at
     * least 1.5 km from its centre, how many of those are within 2 km of a known locality, and how many already have
     * a locality.
     */
    public record AdzunaPoints(long jobs, long withPoint, long offCentre, long nearLocality, long withLocality) { }

    /** Jobs whose location is exactly "NCR", "Delhi NCR" or "Delhi-NCR" (any case) versus all jobs placed in Delhi. */
    public record Ncr(long ncrJobs, long delhiJobs, double percentOfDelhi) { }

    /** Re-resolution throughput from the configured batch size, batches per run and delay. */
    public record ReResolution(int jobsPerHour, double hoursForAllActiveJobs) { }
}
