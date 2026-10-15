package com.example.backend.geo.application;

import com.example.backend.geo.domain.Place;
import com.example.backend.geo.infrastructure.CompanyOfficeAuditDocument;
import com.example.backend.geo.infrastructure.CompanyOfficeDocument;
import com.example.backend.shared.error.BadRequestException;
import com.example.backend.shared.error.ResourceNotFoundException;
import com.example.backend.shared.pagination.PageResponse;
import com.example.backend.shared.text.CompanyNames;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * ADMIN-managed company office pins ({@code company_offices}). An office is pinned on the map, belongs to one
 * gazetteer city (given, or the nearest city of 100k+ people) and must lie within {@value #MAX_CITY_DISTANCE_KM} km
 * of it, which catches misclicks. Every change is audited in {@code company_office_audit}, rebuilds the in-memory
 * {@link OfficeIndex} and publishes {@link OfficesChangedEvent}.
 *
 * <p>Offices are applied to jobs at read time (snapshot build and per-page mapping), never written into jobs.
 */
@Service
public class CompanyOfficeService {
    static final double MAX_CITY_DISTANCE_KM = 60;
    static final long NEAREST_CITY_MIN_POPULATION = 100_000;
    static final double MIN_LAT = 6;
    static final double MAX_LAT = 37.5;
    static final double MIN_LNG = 68;
    static final double MAX_LNG = 97.5;
    static final Duration CANDIDATES_TTL = Duration.ofMinutes(5);
    static final int MAX_CANDIDATES = 200;
    static final long CANDIDATES_MAX_TIME_MS = 15_000;

    private final MongoTemplate mongo;
    private final Gazetteer gazetteer;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final AtomicReference<OfficeIndex> index = new AtomicReference<>();
    private volatile CachedCandidates candidates;

    public CompanyOfficeService(MongoTemplate mongo, Gazetteer gazetteer, Clock clock, ApplicationEventPublisher events) {
        this.mongo = mongo;
        this.gazetteer = gazetteer;
        this.clock = clock;
        this.events = events;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadIndex() { rebuildIndex(); }

    /** Current enabled offices; loaded on first use if startup has not done it yet. */
    public OfficeIndex officeIndex() {
        OfficeIndex current = index.get();
        return current != null ? current : rebuildIndex();
    }

    public OfficeView create(String company, String label, Double lat, Double lng, String placeId, String actor) {
        String name = company(company);
        String key = CompanyNames.key(name);
        if (key.isEmpty()) throw new BadRequestException("Company name must contain letters or digits");
        String officeLabel = label(label);
        double latitude = latitude(lat);
        double longitude = longitude(lng);
        Place place = placeId == null || placeId.isBlank() ? nearestCity(latitude, longitude) : place(placeId.trim());
        checkDistance(place, latitude, longitude);
        checkUnique(key, place, officeLabel, null);
        Instant now = clock.instant();
        CompanyOfficeDocument office = new CompanyOfficeDocument();
        office.setCompanyKey(key);
        office.setCompany(name);
        office.setLabel(officeLabel);
        office.setPlaceId(place.id());
        office.setLat(latitude);
        office.setLng(longitude);
        office.setEnabled(true);
        office.setCreatedAt(now);
        office.setCreatedBy(actor(actor));
        office.setUpdatedAt(now);
        office.setUpdatedBy(actor(actor));
        try {
            mongo.insert(office);
        } catch (DuplicateKeyException duplicate) {
            throw duplicate(officeLabel, place);
        }
        audit(office.getId(), CompanyOfficeAuditDocument.Action.CREATE, actor, null, snapshot(office));
        changed();
        return view(office);
    }

    /** Label, position and enabled flag are editable; company and city are the office's identity. */
    public OfficeView update(String id, String label, Double lat, Double lng, Boolean enabled, String actor) {
        CompanyOfficeDocument office = find(id);
        Map<String, Object> before = snapshot(office);
        if (label != null) office.setLabel(label(label));
        if (lat != null || lng != null) {
            office.setLat(latitude(lat != null ? lat : office.getLat()));
            office.setLng(longitude(lng != null ? lng : office.getLng()));
        }
        if (enabled != null) office.setEnabled(enabled);
        Map<String, Object> after = snapshot(office);
        if (before.equals(after)) return view(office);
        Place place = place(office.getPlaceId());
        checkDistance(place, office.getLat(), office.getLng());
        if (!Objects.equals(before.get("label"), after.get("label"))) {
            checkUnique(office.getCompanyKey(), place, office.getLabel(), office.getId());
        }
        office.setUpdatedAt(clock.instant());
        office.setUpdatedBy(actor(actor));
        try {
            mongo.save(office);
        } catch (DuplicateKeyException duplicate) {
            throw duplicate(office.getLabel(), place);
        }
        CompanyOfficeAuditDocument.Action action = !Objects.equals(before.get("enabled"), after.get("enabled"))
                ? (office.isEnabled() ? CompanyOfficeAuditDocument.Action.ENABLE : CompanyOfficeAuditDocument.Action.DISABLE)
                : CompanyOfficeAuditDocument.Action.UPDATE;
        audit(office.getId(), action, actor, before, after);
        changed();
        return view(office);
    }

    public OfficeView get(String id) { return view(find(id)); }

    public PageResponse<OfficeView> list(String text, Boolean enabled, int page, int size) {
        checkPage(page, size);
        List<Criteria> filters = new ArrayList<>();
        if (enabled != null) filters.add(Criteria.where("enabled").is(enabled));
        if (text != null && !text.isBlank()) {
            String trimmed = text.trim();
            if (trimmed.length() > 100) throw new BadRequestException("Search text is too long");
            Pattern pattern = Pattern.compile(Pattern.quote(trimmed), Pattern.CASE_INSENSITIVE);
            filters.add(new Criteria().orOperator(Criteria.where("company").regex(pattern),
                    Criteria.where("label").regex(pattern)));
        }
        Criteria criteria = filters.isEmpty() ? new Criteria() : new Criteria().andOperator(filters);
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.asc("companyKey"), Sort.Order.asc("placeId"), Sort.Order.asc("label"), Sort.Order.asc("id")));
        List<OfficeView> content = mongo.find(Query.query(criteria).with(pageable), CompanyOfficeDocument.class)
                .stream().map(this::view).toList();
        long total = mongo.count(Query.query(criteria), CompanyOfficeDocument.class);
        return PageResponse.from(new PageImpl<>(content, pageable, total));
    }

    public PageResponse<AuditView> audit(String id, int page, int size) {
        checkPage(page, size);
        find(id);
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("at"), Sort.Order.desc("id")));
        Query query = Query.query(Criteria.where("officeId").is(id));
        List<AuditView> content = mongo.find(Query.of(query).with(pageable), CompanyOfficeAuditDocument.class)
                .stream().map(AuditView::from).toList();
        return PageResponse.from(new PageImpl<>(content, pageable, mongo.count(query, CompanyOfficeAuditDocument.class)));
    }

    /**
     * Worklist for pinning: (company, city) pairs with the most visible jobs where the company has no enabled office
     * in that city. Companies are grouped by lower-cased trimmed name in MongoDB and merged by
     * {@link CompanyNames#key} here ("Acme Pvt Ltd" and "ACME" are one company). Cached for five minutes and
     * dropped on every office change.
     */
    public List<Candidate> candidates(int limit) {
        if (limit < 1 || limit > MAX_CANDIDATES) throw new BadRequestException("Limit must be between 1 and " + MAX_CANDIDATES);
        CachedCandidates cached = candidates;
        Instant now = clock.instant();
        if (cached == null || now.isAfter(cached.expiresAt())) {
            cached = new CachedCandidates(computeCandidates(), now.plus(CANDIDATES_TTL));
            candidates = cached;
        }
        return cached.rows().subList(0, Math.min(limit, cached.rows().size()));
    }

    private List<Candidate> computeCandidates() {
        List<Document> pipeline = List.of(
                new Document("$match", GeoPipelines.visibleJobs()),
                new Document("$project", new Document("_id", 0).append("company", "$company")
                        .append("lower", GeoPipelines.lowerCompany()).append("placeIds", GeoPipelines.placeIds())),
                new Document("$match", new Document("placeIds.0", new Document("$exists", true))
                        .append("lower", new Document("$ne", ""))),
                new Document("$unwind", "$placeIds"),
                new Document("$group", new Document("_id", new Document("lower", "$lower").append("place", "$placeIds")
                        .append("company", "$company")).append("jobs", new Document("$sum", 1))),
                new Document("$group", new Document("_id", new Document("lower", "$_id.lower").append("place", "$_id.place"))
                        .append("jobs", new Document("$sum", "$jobs"))
                        .append("variants", new Document("$push", new Document("company", "$_id.company").append("jobs", "$jobs")))));
        Map<List<String>, Tally> merged = new HashMap<>();
        for (Document row : mongo.getCollection("jobs").aggregate(pipeline)
                .maxTime(CANDIDATES_MAX_TIME_MS, TimeUnit.MILLISECONDS).allowDiskUse(true)) {
            Document id = row.get("_id", Document.class);
            String key = CompanyNames.key(id.getString("lower"));
            String place = id.getString("place");
            if (key.isEmpty() || gazetteer.byId(place).isEmpty()) continue;
            Tally tally = merged.computeIfAbsent(List.of(key, place), ignored -> new Tally());
            tally.jobs += ((Number) row.get("jobs")).longValue();
            for (Document variant : row.getList("variants", Document.class)) {
                String company = variant.getString("company");
                if (company != null) tally.variants.merge(company.trim(), ((Number) variant.get("jobs")).longValue(), Long::sum);
            }
        }
        Set<List<String>> pinned = new HashSet<>();
        mongo.find(Query.query(Criteria.where("enabled").is(true)), CompanyOfficeDocument.class)
                .forEach(office -> pinned.add(List.of(office.getCompanyKey(), office.getPlaceId())));
        List<Candidate> rows = new ArrayList<>();
        merged.forEach((pair, tally) -> {
            if (pinned.contains(pair)) return;
            Place place = gazetteer.byId(pair.get(1)).orElseThrow();
            String display = tally.variants.entrySet().stream()
                    .max(Map.Entry.<String, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                    .map(Map.Entry::getKey).orElse(pair.get(0));
            rows.add(new Candidate(display, pair.get(0), place.id(), place.name(),
                    gazetteer.admin1Name(place.admin1()).orElse(null), tally.jobs));
        });
        rows.sort(Comparator.comparingLong(Candidate::jobs).reversed().thenComparing(Candidate::companyKey)
                .thenComparing(Candidate::placeId));
        return List.copyOf(rows.subList(0, Math.min(MAX_CANDIDATES, rows.size())));
    }

    private synchronized OfficeIndex rebuildIndex() {
        List<OfficeIndex.Office> offices = mongo.find(Query.query(Criteria.where("enabled").is(true)), CompanyOfficeDocument.class)
                .stream().map(office -> new OfficeIndex.Office(office.getId(), office.getCompanyKey(), office.getCompany(),
                        office.getLabel(), office.getPlaceId(), office.getLat(), office.getLng())).toList();
        OfficeIndex rebuilt = new OfficeIndex(offices);
        index.set(rebuilt);
        return rebuilt;
    }

    private void changed() {
        rebuildIndex();
        candidates = null;
        events.publishEvent(new OfficesChangedEvent());
    }

    private Place nearestCity(double lat, double lng) {
        return gazetteer.places().stream().filter(place -> place.population() >= NEAREST_CITY_MIN_POPULATION)
                .min(Comparator.comparingDouble((Place place) -> MapJobService.haversineKm(lat, lng, place.lat(), place.lng()))
                        .thenComparing(Place::id))
                .orElseThrow(() -> new BadRequestException("No city found for this pin"));
    }

    private Place place(String placeId) {
        if (placeId.length() > 20) throw new BadRequestException("Unknown place");
        return gazetteer.byId(placeId).orElseThrow(() -> new BadRequestException("Unknown place " + placeId));
    }

    private static void checkDistance(Place place, double lat, double lng) {
        double km = MapJobService.haversineKm(lat, lng, place.lat(), place.lng());
        if (km > MAX_CITY_DISTANCE_KM) {
            throw new BadRequestException("Pin is far from " + place.name() + " (" + Math.round(km) + " km); offices must be within "
                    + Math.round(MAX_CITY_DISTANCE_KM) + " km of their city");
        }
    }

    private void checkUnique(String companyKey, Place place, String label, String exceptId) {
        Criteria criteria = Criteria.where("companyKey").is(companyKey).and("placeId").is(place.id()).and("label").is(label);
        if (exceptId != null) criteria = criteria.and("_id").ne(exceptId);
        if (mongo.exists(Query.query(criteria), CompanyOfficeDocument.class)) throw duplicate(label, place);
    }

    private static BadRequestException duplicate(String label, Place place) {
        return new BadRequestException("This company already has an office called \"" + label + "\" in " + place.name());
    }

    private CompanyOfficeDocument find(String id) {
        if (id == null || id.isBlank() || id.length() > 64) throw new BadRequestException("Invalid office ID");
        CompanyOfficeDocument office = mongo.findById(id, CompanyOfficeDocument.class);
        if (office == null) throw new ResourceNotFoundException("Office not found");
        return office;
    }

    private void audit(String officeId, CompanyOfficeAuditDocument.Action action, String actor,
                       Map<String, Object> before, Map<String, Object> after) {
        CompanyOfficeAuditDocument record = new CompanyOfficeAuditDocument();
        record.setOfficeId(officeId);
        record.setAction(action);
        record.setActor(actor(actor));
        record.setAt(clock.instant());
        record.setBefore(before);
        record.setAfter(after);
        mongo.insert(record);
    }

    private static Map<String, Object> snapshot(CompanyOfficeDocument office) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("company", office.getCompany());
        values.put("label", office.getLabel());
        values.put("placeId", office.getPlaceId());
        values.put("lat", office.getLat());
        values.put("lng", office.getLng());
        values.put("enabled", office.isEnabled());
        return values;
    }

    private OfficeView view(CompanyOfficeDocument office) {
        Place place = gazetteer.byId(office.getPlaceId()).orElse(null);
        return new OfficeView(office.getId(), office.getCompany(), office.getCompanyKey(), office.getLabel(),
                office.getPlaceId(), place == null ? null : place.name(),
                place == null ? null : gazetteer.admin1Name(place.admin1()).orElse(null), office.getLat(), office.getLng(),
                office.isEnabled(), office.getCreatedAt(), office.getCreatedBy(), office.getUpdatedAt(), office.getUpdatedBy());
    }

    private static void checkPage(int page, int size) {
        if (page < 0) throw new BadRequestException("Page must not be negative");
        if (size < 1 || size > 100) throw new BadRequestException("Size must be between 1 and 100");
    }

    private static String company(String company) {
        String trimmed = company == null ? "" : company.trim();
        if (trimmed.isEmpty() || trimmed.length() > 200) throw new BadRequestException("Company must be 1 to 200 characters");
        return trimmed;
    }

    private static String label(String label) {
        String trimmed = label == null ? "" : label.trim();
        if (trimmed.length() < 2 || trimmed.length() > 80) throw new BadRequestException("Label must be 2 to 80 characters");
        return trimmed;
    }

    private static double latitude(Double lat) {
        if (lat == null || !Double.isFinite(lat) || lat < MIN_LAT || lat > MAX_LAT) {
            throw new BadRequestException("Pin must be inside India (latitude " + MIN_LAT + " to " + MAX_LAT + ")");
        }
        return lat;
    }

    private static double longitude(Double lng) {
        if (lng == null || !Double.isFinite(lng) || lng < MIN_LNG || lng > MAX_LNG) {
            throw new BadRequestException("Pin must be inside India (longitude " + MIN_LNG + " to " + MAX_LNG + ")");
        }
        return lng;
    }

    private static String actor(String actor) {
        if (actor == null || actor.isBlank()) return "unknown";
        return actor.length() <= 200 ? actor : actor.substring(0, 200);
    }

    private static final class Tally {
        long jobs;
        final Map<String, Long> variants = new HashMap<>();
    }

    private record CachedCandidates(List<Candidate> rows, Instant expiresAt) { }

    /** Published after any office change; the map snapshot refreshes soon after. */
    public record OfficesChangedEvent() { }

    public record OfficeView(String id, String company, String companyKey, String label, String placeId, String placeName,
                             String state, double lat, double lng, boolean enabled, Instant createdAt, String createdBy,
                             Instant updatedAt, String updatedBy) { }

    public record Candidate(String company, String companyKey, String placeId, String placeName, String state, long jobs) { }

    /** {@code action}: CREATE, UPDATE, ENABLE or DISABLE. */
    public record AuditView(String id, String action, String actor, Instant at,
                            Map<String, Object> before, Map<String, Object> after) {
        static AuditView from(CompanyOfficeAuditDocument record) {
            return new AuditView(record.getId(), record.getAction().name(), record.getActor(), record.getAt(),
                    record.getBefore(), record.getAfter());
        }
    }
}
