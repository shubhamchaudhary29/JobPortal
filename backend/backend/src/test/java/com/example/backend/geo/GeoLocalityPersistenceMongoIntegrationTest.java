package com.example.backend.geo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.example.backend.geo.application.Gazetteer;
import com.example.backend.geo.application.GeoResolver;
import com.example.backend.geo.application.LocalityIndex;
import com.example.backend.geo.domain.Place;
import com.example.backend.integration.adzuna.AdzunaClient;
import com.example.backend.integration.adzuna.AdzunaJobMapper;
import com.example.backend.integration.adzuna.AdzunaJobStore;
import com.example.backend.integration.adzuna.AdzunaResponse;
import com.example.backend.integration.aggregation.AggregationConflictDocument;
import com.example.backend.integration.aggregation.AggregationConflictService;
import com.example.backend.integration.aggregation.FingerprintMigrationService;
import com.example.backend.integration.aggregation.GeoReResolutionService;
import com.example.backend.integration.aggregation.ImportedJobLifecycleService;
import com.example.backend.job.api.dto.CreateJobRequest;
import com.example.backend.job.application.JobService;
import com.example.backend.job.domain.GeoResolution;
import com.example.backend.job.infrastructure.ImportedSourceListing;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.matching.application.JobFeatureService;
import com.example.backend.matching.infrastructure.JobFeatureStore;
import com.example.backend.shared.security.CurrentUserProvider;
import com.example.backend.user.domain.UserRole;
import com.example.backend.user.infrastructure.UserDocument;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** GEO-8B: every geo write path (GEO-1B list) stores and keeps {@code localityIds}; old documents read back empty. */
@SpringBootTest(properties = "job-aggregation.lifecycle.missing-threshold=1")
class GeoLocalityPersistenceMongoIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final String RECRUITER = "geo8-recruiter@example.test";

    @Autowired AdzunaJobStore store;
    @Autowired ImportedJobLifecycleService lifecycle;
    @Autowired AggregationConflictService conflicts;
    @Autowired FingerprintMigrationService fingerprints;
    @Autowired JobFeatureService features;
    @Autowired JobFeatureStore featureStore;
    @Autowired GeoReResolutionService reResolution;
    @Autowired JobService jobs;
    @Autowired GeoResolver resolver;
    @Autowired Gazetteer gazetteer;
    @Autowired MongoTemplate mongo;
    @MockitoBean CurrentUserProvider currentUser;
    @MockitoBean AdzunaClient adzunaClient;

    @BeforeEach
    void clear() {
        mongo.remove(new Query(), JobDocument.class);
        mongo.remove(new Query(), AggregationConflictDocument.class);
        mongo.remove(Query.query(Criteria.where("email").is(RECRUITER)), UserDocument.class);
        mongo.getCollection("applications").deleteMany(new Document());
        mongo.getCollection("ingestion_locks").deleteMany(new Document());
    }

    @Test
    void importStoreWritesListingLocalities() {
        store.upsert(imported("lever", "board:one", "Whitefield, Bengaluru, Karnataka, India", "fp-one"), NOW, "board");

        assertEquals(List.of(whitefield().id()), singleJob().getSourceListings().get(0).getGeo().localityIds());
        Document raw = mongo.getCollection("jobs").find().first();
        Document geo = raw.getList("sourceListings", Document.class).get(0).get("geo", Document.class);
        assertEquals(List.of(whitefield().id()), geo.getList("localityIds", String.class));
    }

    @Test
    void adzunaPointOffTheCentreSnapsOnIngestion() {
        LocalityIndex.Locality whitefield = whitefield();
        AdzunaResponse.AdzunaJob job = new AdzunaResponse.AdzunaJob("adz-1", "Backend Engineer", "Build APIs",
                "https://www.adzuna.in/details/adz-1", null, null, null, null, new AdzunaResponse.Company("Acme"),
                new AdzunaResponse.Location("Bangalore, Karnataka", List.of("India", "Karnataka", "Bangalore")),
                whitefield.lat() + 0.009, whitefield.lng());
        store.upsert(AdzunaJobMapper.toDocument(job, NOW).orElseThrow(), NOW);

        GeoResolution geo = singleJob().getSourceListings().get(0).getGeo();
        assertEquals(1, geo.localityIds().size());
        assertNotNull(geo.point(), "the raw point is still stored, never used as a pin");
    }

    @Test
    void lifecycleConflictCopyFingerprintMigrationAndFeatureStoreKeepLocalities() {
        store.upsert(imported("lever", "board:one", "Gachibowli, Hyderabad", "fp-one"), NOW, "board");
        GeoResolution before = singleJob().getSourceListings().get(0).getGeo();
        assertFalse(before.localityIds().isEmpty());

        lifecycle.completeSuccessfulRun("lever", "board", Set.of(), NOW.plusDays(1));
        assertEquals(before, singleJob().getSourceListings().get(0).getGeo(), "updateMulti pipeline ($mergeObjects)");

        mongo.updateFirst(new Query(), new org.springframework.data.mongodb.core.query.Update()
                .unset("fingerprintVersion").set("fingerprint", "v1-old"), JobDocument.class);
        FingerprintMigrationService.Report report = fingerprints.run(true);
        assertEquals(1, report.updated);
        assertEquals(before, singleJob().getSourceListings().get(0).getGeo(), "fingerprint migration");

        JobDocument snapshot = singleJob();
        features.prepare(snapshot);
        featureStore.persistIfCurrent(snapshot);
        assertEquals(before, singleJob().getSourceListings().get(0).getGeo(), "feature store bulk update");
    }

    @Test
    void conflictReconciliationCopiesLocalities() {
        JobDocument canonical = withListing("greenhouse", "board:one", "old-fingerprint", "Whitefield, Bengaluru");
        JobDocument duplicate = withListing("lever", "board:two", "new-fingerprint", "Gachibowli, Hyderabad");
        mongo.insert(canonical);
        mongo.insert(duplicate);
        conflicts.recordIdentityFingerprint("greenhouse:board:one", "new-fingerprint", canonical.getId(), duplicate.getId(), NOW);
        AggregationConflictDocument conflict = mongo.findAll(AggregationConflictDocument.class).get(0);

        conflicts.resolve(conflict.getId(), canonical.getId(), duplicate.getId(), "admin@example.test");

        JobDocument merged = mongo.findById(canonical.getId(), JobDocument.class);
        for (ImportedSourceListing listing : merged.getSourceListings()) {
            assertEquals(resolver.resolve(listing.getRawLocation(), null).localityIds(), listing.getGeo().localityIds());
            assertFalse(listing.getGeo().localityIds().isEmpty(), listing.getIdentity());
        }
    }

    @Test
    void manualJobsStoreTopLevelLocalities() {
        UserDocument recruiter = new UserDocument();
        recruiter.setEmail(RECRUITER);
        recruiter.setRole(UserRole.RECRUITER);
        mongo.insert(recruiter);
        when(currentUser.email()).thenReturn(RECRUITER);

        String id = jobs.create(new CreateJobRequest("Engineer", "Build things", "Whitefield, Bengaluru", "Acme", 10, 1)).id();

        assertEquals(List.of(whitefield().id()), mongo.findById(id, JobDocument.class).getGeo().localityIds());
    }

    @Test
    void documentsWithoutLocalitiesReadBackEmpty() {
        Document oldGeo = new Document("status", "PLACE").append("placeIds", List.of(bengaluru().id())).append("remote", false)
                .append("version", "2-old");
        mongo.getCollection("jobs").insertOne(new Document("_id", "old-listing").append("title", "Engineer").append("active", true)
                .append("sourceListings", List.of(new Document("identity", "lever:x").append("active", true).append("geo", oldGeo))));
        mongo.getCollection("jobs").insertOne(new Document("_id", "old-manual").append("title", "Engineer").append("active", true)
                .append("geo", oldGeo));

        assertEquals(List.of(), mongo.findById("old-listing", JobDocument.class).getSourceListings().get(0).getGeo().localityIds());
        assertEquals(List.of(), mongo.findById("old-manual", JobDocument.class).getGeo().localityIds());
        assertEquals(List.of(bengaluru().id()), mongo.findById("old-manual", JobDocument.class).getGeo().placeIds());
    }

    @Test
    void reResolutionFillsLocalitiesAndKeepsAndSnapsAdzunaPoints() {
        LocalityIndex.Locality whitefield = whitefield();
        Document old = new Document("status", "PLACE").append("placeIds", List.of(bengaluru().id())).append("remote", false)
                .append("version", "2-old");
        Document point = new Document("lat", whitefield.lat() + 0.009).append("lng", whitefield.lng());
        mongo.getCollection("jobs").insertOne(new Document("_id", "listed").append("title", "Engineer").append("active", true)
                .append("location", "Bangalore").append("sourceListings", new ArrayList<>(List.of(
                        new Document("identity", "lever:a").append("provider", "lever").append("active", true)
                                .append("rawLocation", "Whitefield, Bengaluru").append("geo", old),
                        new Document("identity", "adzuna:b").append("provider", "adzuna").append("active", true)
                                .append("rawLocation", "Bangalore").append("geo", new Document(old).append("point", point))))));
        mongo.getCollection("jobs").insertOne(new Document("_id", "manual").append("title", "Engineer").append("active", true)
                .append("location", "Koramangala, Bangalore").append("geo", old));

        GeoReResolutionService.Result result = reResolution.runOnce();

        assertEquals(3, result.updated());
        JobDocument listed = mongo.findById("listed", JobDocument.class);
        GeoResolution lever = listed.getSourceListings().get(0).getGeo();
        GeoResolution adzuna = listed.getSourceListings().get(1).getGeo();
        GeoResolution manual = mongo.findById("manual", JobDocument.class).getGeo();
        assertAll(
                () -> assertTrue(resolver.version().startsWith("4-")),
                () -> assertEquals(resolver.version(), lever.version()),
                () -> assertEquals(List.of(whitefield.id()), lever.localityIds()),
                () -> assertNotNull(adzuna.point(), "re-resolution keeps the stored Adzuna point"),
                () -> assertEquals(point.getDouble("lat"), adzuna.point().lat()),
                () -> assertEquals(1, adzuna.localityIds().size(), "and snaps it"),
                () -> assertEquals(resolver.version(), manual.version()),
                () -> assertEquals("Koramangala", gazetteer.localityById(manual.localityIds().get(0)).orElseThrow().name()));
        assertEquals(0, reResolution.runOnce().updated(), "a second run finds nothing stale");
    }

    private Place bengaluru() { return gazetteer.primaryMatches("bengaluru").get(0); }

    private LocalityIndex.Locality whitefield() { return gazetteer.localityMatches("whitefield", bengaluru().id()).get(0); }

    private JobDocument singleJob() {
        List<JobDocument> all = mongo.findAll(JobDocument.class);
        assertEquals(1, all.size());
        return all.get(0);
    }

    private static JobDocument imported(String provider, String externalId, String location, String fingerprint) {
        JobDocument job = new JobDocument();
        job.setSource(provider);
        job.setExternalId(externalId);
        job.setTitle("Engineer");
        job.setDescription("Requirements: Java and Docker.");
        job.setCompany("Acme");
        job.setLocation(location);
        job.setApplicationUrl("https://" + provider + ".test/" + externalId.replace(':', '-'));
        job.setSourceUrl(job.getApplicationUrl());
        job.setFingerprint(fingerprint);
        job.setActive(true);
        return job;
    }

    private JobDocument withListing(String provider, String externalId, String fingerprint, String location) {
        JobDocument job = imported(provider, externalId, location, fingerprint);
        job.setSourceIdentities(new LinkedHashSet<>(Set.of(provider + ":" + externalId)));
        ImportedSourceListing listing = new ImportedSourceListing();
        listing.setIdentity(provider + ":" + externalId);
        listing.setProvider(provider);
        listing.setEmployer("board");
        listing.setExternalId(externalId);
        listing.setApplicationUrl(job.getApplicationUrl());
        listing.setFirstSeenAt(NOW.minusDays(2));
        listing.setLastSeenAt(NOW.minusDays(1));
        listing.setActive(true);
        listing.setRawLocation(location);
        listing.setGeo(resolver.resolve(location, null));
        job.setSourceListings(List.of(listing));
        return job;
    }
}
