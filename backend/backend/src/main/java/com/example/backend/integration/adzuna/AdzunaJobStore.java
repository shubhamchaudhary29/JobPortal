package com.example.backend.integration.adzuna;

import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.integration.aggregation.AggregationConflictService;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationUpdate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import com.example.backend.job.infrastructure.ImportedSourceListing;
import com.example.backend.matching.application.JobFeatureService;
import com.example.backend.geo.application.GeoResolver;
import com.example.backend.geo.infrastructure.UnresolvedLocationRecorder;
import com.example.backend.job.domain.GeoStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Repository
public class AdzunaJobStore {
    private static final Logger log = LoggerFactory.getLogger(AdzunaJobStore.class);
    private static final int MAX_RAW_LOCATION = 300;
    private static final int MAX_RAW_AREA_ELEMENTS = 10;
    private static final int MAX_RAW_AREA_LENGTH = 100;
    private final MongoTemplate mongo;
    private final AggregationConflictService conflicts;
    private final JobFeatureService jobFeatures;
    private final GeoResolver geoResolver;
    private final UnresolvedLocationRecorder unresolvedLocations;
    @Autowired
    public AdzunaJobStore(MongoTemplate mongo, AggregationConflictService conflicts, JobFeatureService jobFeatures,
                          GeoResolver geoResolver, UnresolvedLocationRecorder unresolvedLocations) {
        this.mongo = mongo;
        this.conflicts = conflicts;
        this.jobFeatures = jobFeatures;
        this.geoResolver = geoResolver;
        this.unresolvedLocations = unresolvedLocations;
    }
    AdzunaJobStore(MongoTemplate mongo) { this(mongo, null, null, null, null); }
    public UpsertOutcome upsert(JobDocument job, LocalDateTime now) {
        return upsert(job, now, null);
    }
    public UpsertOutcome upsert(JobDocument job, LocalDateTime now, String employer) {
        if (jobFeatures != null) jobFeatures.prepare(job);
        if (job.getFingerprint() == null || job.getFingerprint().isBlank()) job.setFingerprint(legacyFingerprint(job));
        String identity = job.getSource() + ":" + job.getExternalId();
        // A provider listing identity is permanent even when its title/location changes.
        // Legacy source/externalId is included until the documented backfill has completed.
        Query identityQuery = Query.query(new Criteria().andOperator(Criteria.where("recruiterId").is(null),
                new Criteria().orOperator(Criteria.where("sourceIdentities").is(identity),
                        Criteria.where("source").is(job.getSource()).and("externalId").is(job.getExternalId()))));
        JobDocument identityMatch = mongo.findOne(identityQuery, JobDocument.class);
        JobDocument fingerprintMatch = mongo.findOne(Query.query(Criteria.where("fingerprint").is(job.getFingerprint()).and("recruiterId").is(null)), JobDocument.class);
        if (identityMatch != null && fingerprintMatch != null && !identityMatch.getId().equals(fingerprintMatch.getId())) {
            // This is a legacy conflict, not an ordinary duplicate.  Deleting or rewriting
            // either document here can orphan applications that retain its job id.  Surface it
            // for the documented audit/migration instead of making an unsafe guess at runtime.
            if (conflicts != null) {
                conflicts.recordIdentityFingerprint(identity, job.getFingerprint(), identityMatch.getId(),
                        fingerprintMatch.getId(), now);
            }
            throw new AdzunaPersistenceException(new IllegalStateException("Imported identity/fingerprint conflict requires reconciliation"));
        }
        Query query = identityMatch != null ? Query.query(Criteria.where("_id").is(identityMatch.getId()))
                : fingerprintMatch != null ? Query.query(Criteria.where("_id").is(fingerprintMatch.getId()))
                : Query.query(new Criteria().andOperator(Criteria.where("recruiterId").is(null), new Criteria().orOperator(
                        Criteria.where("fingerprint").is(job.getFingerprint()),
                        Criteria.where("source").is(job.getSource()).and("externalId").is(job.getExternalId()))));
        ImportedSourceListing listing = new ImportedSourceListing();
        listing.setIdentity(identity); listing.setProvider(job.getSource()); listing.setExternalId(job.getExternalId());
        listing.setEmployer(employer);
        listing.setApplicationUrl(job.getApplicationUrl()); listing.setFirstSeenAt(now); listing.setLastSeenAt(now); listing.setActive(true);
        resolveLocation(job, listing);
        UpdateDefinition update = canonicalUpsertUpdate(job, now, identity, listing);
        try {
            UpsertOutcome result = outcome(mongo.findAndModify(query, update,
                    FindAndModifyOptions.options().upsert(true), JobDocument.class), job, identity, storesPoint(listing));
            return result;
        }
        catch (DuplicateKeyException race) {
            try {
                JobDocument winner = mongo.findOne(query, JobDocument.class);
                if (winner == null || winner.getId() == null) {
                    throw new IllegalStateException("Duplicate-key winner could not be resolved");
                }
                Query winnerQuery = Query.query(Criteria.where("_id").is(winner.getId()));
                UpsertOutcome result = outcome(mongo.findAndModify(winnerQuery, update,
                        FindAndModifyOptions.options().upsert(false), JobDocument.class), job, identity, storesPoint(listing));
                return result;
            }
            catch (RuntimeException failure) { throw new AdzunaPersistenceException(failure); }
        } catch (RuntimeException failure) { throw new AdzunaPersistenceException(failure); }
    }
    private UpsertOutcome outcome(JobDocument previous, JobDocument incoming, String identity, boolean storesPoint) {
        if (previous == null) return UpsertOutcome.INSERTED;
        ImportedSourceListing existingListing = previous.getSourceListings() == null ? null
                : previous.getSourceListings().stream()
                        .filter(listing -> identity.equals(listing.getIdentity())).findFirst().orElse(null);
        // GEO-10A: a listing stored without a provider point that now has one has a new resolution to show.
        if (existingListing != null && storesPoint && (existingListing.getGeo() == null || existingListing.getGeo().point() == null)) {
            return UpsertOutcome.UPDATED;
        }
        if (existingListing != null
                && !(java.util.Objects.equals(previous.getSource(), incoming.getSource())
                && java.util.Objects.equals(previous.getExternalId(), incoming.getExternalId()))) {
            return java.util.Objects.equals(existingListing.getApplicationUrl(), incoming.getApplicationUrl())
                    ? UpsertOutcome.UNCHANGED : UpsertOutcome.UPDATED;
        }
        if (existingListing == null && !(java.util.Objects.equals(previous.getSource(), incoming.getSource())
                && java.util.Objects.equals(previous.getExternalId(), incoming.getExternalId()))) {
            return UpsertOutcome.UPDATED;
        }
        return same(previous, incoming) ? UpsertOutcome.UNCHANGED : UpsertOutcome.UPDATED;
    }
    /** The new resolution keeps a provider point (one inside India; others are dropped by the resolver). */
    private static boolean storesPoint(ImportedSourceListing listing) {
        return listing.getGeo() != null && listing.getGeo().point() != null;
    }
    private UpdateDefinition canonicalUpsertUpdate(JobDocument job, LocalDateTime now, String identity,
                                                   ImportedSourceListing listing) {
        Document mappedListing = listingDocument(listing);
        Object observedAt = mappedListing.get("lastSeenAt");
        Document currentListings = new Document("$ifNull", List.of("$sourceListings", List.of()));
        Document sameIdentity = filter(currentListings, "listing", new Document("$eq", List.of("$$listing.identity", identity)));
        Document otherIdentities = filter(currentListings, "listing", new Document("$ne", List.of("$$listing.identity", identity)));
        Document sameIdentityWithFirstSeen = filter(sameIdentity, "listing",
                new Document("$ne", java.util.Arrays.asList("$$listing.firstSeenAt", null)));
        Document existingFirstSeenValues = new Document("$map", new Document("input", sameIdentityWithFirstSeen)
                .append("as", "listing").append("in", "$$listing.firstSeenAt"));
        mappedListing.put("firstSeenAt", new Document("$ifNull", List.of(
                new Document("$min", existingFirstSeenValues), observedAt)));
        Document nextListings = new Document("$sortArray", new Document("input",
                new Document("$concatArrays", List.of(otherIdentities, List.of(mappedListing))))
                .append("sortBy", new Document("provider", 1).append("identity", 1).append("applicationUrl", 1)));
        Document listingIdentities = new Document("$map", new Document("input", "$sourceListings")
                .append("as", "listing").append("in", "$$listing.identity"));
        Document listingsWithUrls = filter("$sourceListings", "listing",
                new Document("$ne", java.util.Arrays.asList("$$listing.applicationUrl", null)));
        Document listingUrls = new Document("$map", new Document("input", listingsWithUrls)
                .append("as", "listing").append("in", "$$listing.applicationUrl"));
        Object observedDate = mongoDate(now);
        Document managedFields = new Document("sourceListings", nextListings)
                .append("fetchedAt", observedDate)
                .append("lastSeenAt", observedDate)
                .append("active", true)
                .append("fingerprint", literal(job.getFingerprint()))
                // Every imported write path computes the current JobFingerprint version.
                .append("fingerprintVersion", com.example.backend.integration.aggregation.JobFingerprint.VERSION)
                .append("lastSuccessfulSyncAt", observedDate)
                .append("consecutiveMissingRuns", 0)
                .append("firstSeenAt", new Document("$ifNull", List.of("$firstSeenAt", observedDate)))
                .append("createdAt", new Document("$ifNull", List.of("$createdAt", observedDate)))
                .append("recruiterId", new Document("$ifNull", java.util.Arrays.asList("$recruiterId", null)));

        Document canonicalFields = new Document("title", canonicalValue("title", job.getTitle(), identity))
                .append("description", canonicalValue("description", job.getDescription(), identity))
                .append("company", canonicalValue("company", job.getCompany(), identity))
                .append("location", canonicalValue("location", job.getLocation(), identity))
                .append("salary", canonicalValue("salary", job.getSalary(), identity))
                .append("experience", canonicalValue("experience", job.getExperience(), identity))
                .append("employmentType", canonicalValue("employmentType", job.getEmploymentType(), identity))
                .append("salaryMin", canonicalValue("salaryMin", job.getSalaryMin(), identity))
                .append("salaryMax", canonicalValue("salaryMax", job.getSalaryMax(), identity))
                .append("publishedAt", canonicalValue("publishedAt", mongoDate(job.getPublishedAt()), identity))
                .append("expiresAt", canonicalValue("expiresAt", mongoDate(job.getExpiresAt()), identity))
                .append("source", "$_canonicalSourceListing.provider")
                .append("externalId", "$_canonicalSourceListing.externalId")
                .append("applicationUrl", "$_canonicalSourceListing.applicationUrl")
                .append("sourceUrl", "$_canonicalSourceListing.applicationUrl")
                .append("sourceIdentities", listingIdentities)
                .append("applicationUrls", listingUrls);
        if (job.getMatchFeatures() != null) {
            Object mappedFeatures = mongo.getConverter().convertToMongoType(job.getMatchFeatures());
            canonicalFields.append("matchFeatures", canonicalValue("matchFeatures", mappedFeatures, identity));
        }
        return AggregationUpdate.from(List.of(
                Aggregation.stage(new Document("$set", managedFields)),
                Aggregation.stage(new Document("$set", new Document("_canonicalSourceListing",
                        new Document("$arrayElemAt", List.of("$sourceListings", 0))))),
                Aggregation.stage(new Document("$set", canonicalFields)),
                Aggregation.stage(new Document("$unset", List.of("_canonicalSourceListing", "inactiveReason", "inactiveAt")))));
    }
    private Document canonicalValue(String field, Object incoming, String identity) {
        return new Document("$cond", List.of(
                new Document("$eq", List.of("$_canonicalSourceListing.identity", identity)),
                literal(incoming), "$" + field));
    }
    private Document listingDocument(ImportedSourceListing listing) {
        Document document = new Document("identity", listing.getIdentity())
                .append("provider", listing.getProvider())
                .append("externalId", listing.getExternalId())
                .append("firstSeenAt", mongoDate(listing.getFirstSeenAt()))
                .append("lastSeenAt", mongoDate(listing.getLastSeenAt()))
                .append("active", listing.isActive())
                .append("consecutiveMissingRuns", listing.getConsecutiveMissingRuns());
        if (listing.getEmployer() != null) document.append("employer", listing.getEmployer());
        if (listing.getApplicationUrl() != null) document.append("applicationUrl", listing.getApplicationUrl());
        // Pipeline update: provider strings starting with '$' would otherwise be read as field paths.
        if (listing.getRawLocation() != null) document.append("rawLocation", literal(listing.getRawLocation()));
        if (listing.getRawArea() != null && !listing.getRawArea().isEmpty()) {
            document.append("rawArea", literal(listing.getRawArea()));
        }
        if (listing.getGeo() != null) document.append("geo", literal(mongo.getConverter().convertToMongoType(listing.getGeo())));
        return document;
    }
    private void resolveLocation(JobDocument job, ImportedSourceListing listing) {
        if (geoResolver == null) return;
        String location = job.getLocation();
        listing.setRawLocation(location == null || location.length() <= MAX_RAW_LOCATION ? location
                : location.substring(0, MAX_RAW_LOCATION));
        if (job.getLocationHint() != null && !job.getLocationHint().area().isEmpty()) {
            listing.setRawArea(job.getLocationHint().area().stream().limit(MAX_RAW_AREA_ELEMENTS)
                    .map(element -> element.length() <= MAX_RAW_AREA_LENGTH ? element : element.substring(0, MAX_RAW_AREA_LENGTH))
                    .toList());
        }
        listing.setGeo(geoResolver.resolve(location, job.getLocationHint(), job.getDescription()));
        if (listing.getGeo().status() != GeoStatus.UNRESOLVED || unresolvedLocations == null) return;
        try {
            unresolvedLocations.record(location, job.getSource());
        } catch (RuntimeException failure) {
            // Visibility only; an unresolved-location counter must never fail an import.
            log.debug("event=geo_unresolved_record_failed type={}", failure.getClass().getSimpleName());
        }
    }
    private Document literal(Object value) {
        return new Document("$literal", value);
    }
    private Date mongoDate(LocalDateTime value) {
        return value == null ? null : Date.from(value.atZone(ZoneId.systemDefault()).toInstant());
    }
    private Document filter(Object input, String alias, Document condition) {
        return new Document("$filter", new Document("input", input).append("as", alias).append("cond", condition));
    }
    private boolean same(JobDocument a, JobDocument b) {
        return java.util.Objects.equals(a.getTitle(), b.getTitle()) && java.util.Objects.equals(a.getDescription(), b.getDescription())
                && java.util.Objects.equals(a.getCompany(), b.getCompany()) && java.util.Objects.equals(a.getLocation(), b.getLocation())
                && java.util.Objects.equals(a.getApplicationUrl(), b.getApplicationUrl()) && java.util.Objects.equals(a.getFingerprint(), b.getFingerprint())
                && java.util.Objects.equals(a.getSalaryMin(), b.getSalaryMin()) && java.util.Objects.equals(a.getSalaryMax(), b.getSalaryMax())
                && java.util.Objects.equals(a.getEmploymentType(), b.getEmploymentType()) && java.util.Objects.equals(a.getSourceUrl(), b.getSourceUrl())
                && java.util.Objects.equals(a.getPublishedAt(), b.getPublishedAt()) && java.util.Objects.equals(a.getExpiresAt(), b.getExpiresAt())
                && sameFeatures(a, b)
                && java.util.Objects.equals(a.getActive(), b.getActive()) && Double.compare(a.getSalary(), b.getSalary()) == 0
                && Double.compare(a.getExperience(), b.getExperience()) == 0;
    }
    private boolean sameFeatures(JobDocument a, JobDocument b) {
        if (a.getMatchFeatures() == null || b.getMatchFeatures() == null)
            return a.getMatchFeatures() == b.getMatchFeatures();
        return java.util.Objects.equals(a.getMatchFeatures().getFeatureExtractionVersion(),
                b.getMatchFeatures().getFeatureExtractionVersion())
                && java.util.Objects.equals(a.getMatchFeatures().getSourceHash(), b.getMatchFeatures().getSourceHash());
    }
    private String legacyFingerprint(JobDocument job) {
        return com.example.backend.integration.aggregation.JobFingerprint.of(safe(job.getCompany()), safe(job.getTitle()),
                job.getLocation());
    }
    private String safe(String value) { return value == null ? "" : value.trim().replaceAll("\\s+", " "); }
}
