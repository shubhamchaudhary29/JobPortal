package com.example.backend.geo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.example.backend.geo.application.GeoResolver;
import com.example.backend.geo.infrastructure.UnresolvedLocationRecorder;
import com.example.backend.integration.adzuna.AdzunaJobMapper;
import com.example.backend.integration.adzuna.AdzunaJobStore;
import com.example.backend.integration.adzuna.AdzunaResponse;
import com.example.backend.integration.aggregation.AggregationConflictDocument;
import com.example.backend.integration.aggregation.AggregationConflictService;
import com.example.backend.integration.aggregation.ImportedJobLifecycleService;
import com.example.backend.job.api.dto.CreateJobRequest;
import com.example.backend.job.api.dto.UpdateJobRequest;
import com.example.backend.job.application.JobService;
import com.example.backend.job.domain.GeoResolution;
import com.example.backend.job.domain.GeoStatus;
import com.example.backend.job.infrastructure.ImportedSourceListing;
import com.example.backend.job.infrastructure.JobDocument;
import com.example.backend.shared.security.CurrentUserProvider;
import com.example.backend.user.domain.UserRole;
import com.example.backend.user.infrastructure.UserDocument;
import java.time.LocalDateTime;
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

@SpringBootTest(properties = "job-aggregation.lifecycle.missing-threshold=1")
class GeoPersistenceMongoIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final String RECRUITER = "geo-recruiter@example.test";

    @Autowired AdzunaJobStore store;
    @Autowired ImportedJobLifecycleService lifecycle;
    @Autowired AggregationConflictService conflicts;
    @Autowired JobService jobs;
    @Autowired GeoResolver resolver;
    @Autowired MongoTemplate mongo;
    @MockitoBean CurrentUserProvider currentUser;

    @BeforeEach
    void clear() {
        mongo.remove(new Query(), JobDocument.class);
        mongo.remove(new Query(), AggregationConflictDocument.class);
        mongo.remove(Query.query(Criteria.where("email").is(RECRUITER)), UserDocument.class);
        mongo.getCollection(UnresolvedLocationRecorder.COLLECTION).deleteMany(new Document());
        mongo.getCollection("ingestion_locks").deleteMany(new Document());
    }

    @Test
    void importedListingStoresItsOwnLocationAndResolution() {
        store.upsert(imported("lever", "board:one", "Bengaluru / Mumbai", "fp-one"), NOW, "board");

        ImportedSourceListing listing = singleJob().getSourceListings().get(0);
        assertAll(
                () -> assertEquals("Bengaluru / Mumbai", listing.getRawLocation()),
                () -> assertFalse(listing.isRawLocationInferred()),
                () -> assertEquals(GeoStatus.PLACE, listing.getGeo().status()),
                () -> assertEquals(2, listing.getGeo().placeIds().size()),
                () -> assertEquals(resolver.version(), listing.getGeo().version()),
                () -> assertNull(singleJob().getGeo(), "imported jobs resolve per listing, not top-level"));
    }

    @Test
    void dollarPrefixedLocationIsStoredLiterally() {
        assertDoesNotThrow(() -> store.upsert(imported("lever", "board:one", "$where", "fp-one"), NOW, "board"));

        ImportedSourceListing listing = singleJob().getSourceListings().get(0);
        assertEquals("$where", listing.getRawLocation());
        assertEquals("$where", singleJob().getLocation());
    }

    @Test
    void mergedProvidersKeepTheirOwnLocationsAndCanonicalLocationIsUnchanged() {
        store.upsert(imported("adzuna", "adzuna-1", "Bengaluru, Karnataka", "shared"), NOW);
        store.upsert(imported("lever", "board:one", "Pune", "shared"), NOW, "board");

        JobDocument job = singleJob();
        ImportedSourceListing adzuna = listing(job, "adzuna:adzuna-1");
        ImportedSourceListing lever = listing(job, "lever:board:one");
        assertAll(
                () -> assertEquals(2, job.getSourceListings().size()),
                () -> assertEquals("Bengaluru, Karnataka", adzuna.getRawLocation()),
                () -> assertEquals("Pune", lever.getRawLocation()),
                () -> assertNotEquals(adzuna.getGeo().placeIds(), lever.getGeo().placeIds()),
                // The canonical listing (first by provider) still owns canonical content, exactly as before GEO-1.
                () -> assertEquals("Bengaluru, Karnataka", job.getLocation()));
    }

    @Test
    void reUpsertingAnIdentityReplacesItsResolution() {
        store.upsert(imported("lever", "board:one", "Bengaluru / Mumbai", "fp-one"), NOW, "board");
        store.upsert(imported("lever", "board:one", "Pune", "fp-one"), NOW.plusHours(1), "board");

        GeoResolution geo = singleJob().getSourceListings().get(0).getGeo();
        assertEquals(1, geo.placeIds().size());
        assertEquals(resolver.resolve("Pune", null).placeIds(), geo.placeIds());
    }

    @Test
    void lifecycleDeactivationKeepsListingLocationAndResolution() {
        store.upsert(imported("lever", "board:one", "Hyderabad, Telangana", "fp-one"), NOW, "board");
        GeoResolution before = singleJob().getSourceListings().get(0).getGeo();

        lifecycle.completeSuccessfulRun("lever", "board", Set.of(), NOW.plusDays(1));

        ImportedSourceListing listing = singleJob().getSourceListings().get(0);
        assertFalse(listing.isActive());
        assertEquals("Hyderabad, Telangana", listing.getRawLocation());
        assertEquals(before, listing.getGeo());
    }

    @Test
    void conflictReconciliationPreservesListingGeo() {
        JobDocument canonical = withListing("greenhouse", "board:one", "old-fingerprint", "Chennai, TN");
        JobDocument duplicate = withListing("lever", "board:two", "new-fingerprint", "Kochi");
        mongo.insert(canonical);
        mongo.insert(duplicate);
        conflicts.recordIdentityFingerprint("greenhouse:board:one", "new-fingerprint",
                canonical.getId(), duplicate.getId(), NOW);
        AggregationConflictDocument conflict = mongo.findAll(AggregationConflictDocument.class).get(0);

        conflicts.resolve(conflict.getId(), canonical.getId(), duplicate.getId(), "admin@example.test");

        JobDocument merged = mongo.findById(canonical.getId(), JobDocument.class);
        assertNotNull(merged);
        for (String identity : List.of("greenhouse:board:one", "lever:board:two")) {
            ImportedSourceListing listing = listing(merged, identity);
            String expected = identity.startsWith("greenhouse") ? "Chennai, TN" : "Kochi";
            assertEquals(expected, listing.getRawLocation());
            assertTrue(listing.isRawLocationInferred());
            assertEquals(resolver.resolve(expected, null), listing.getGeo());
        }
    }

    @Test
    void manualJobsResolveTopLevelGeoOnCreateAndUpdate() {
        UserDocument recruiter = new UserDocument();
        recruiter.setEmail(RECRUITER);
        recruiter.setRole(UserRole.RECRUITER);
        mongo.insert(recruiter);
        when(currentUser.email()).thenReturn(RECRUITER);

        String id = jobs.create(new CreateJobRequest("Engineer", "Build things", "Gurgaon", "Acme", 10, 1)).id();
        GeoResolution created = mongo.findById(id, JobDocument.class).getGeo();
        assertEquals(GeoStatus.PLACE, created.status());
        assertEquals(resolver.resolve("Gurugram", null).placeIds(), created.placeIds());

        jobs.update(id, new UpdateJobRequest("Engineer", "Build things", "Remote", "Acme", 10, 1));
        GeoResolution updated = mongo.findById(id, JobDocument.class).getGeo();
        assertEquals(GeoStatus.REMOTE, updated.status());
        assertTrue(updated.placeIds().isEmpty());
    }

    @Test
    void adzunaAreaHintAndCoordinatesReachTheListing() {
        AdzunaResponse.AdzunaJob withArea = adzuna("adz-1",
                new AdzunaResponse.Location("Bangalore, Karnataka", List.of("India", "Karnataka", "Bangalore")),
                12.97, 77.59);
        store.upsert(AdzunaJobMapper.toDocument(withArea, NOW).orElseThrow(), NOW);

        GeoResolution geo = singleJob().getSourceListings().get(0).getGeo();
        assertEquals(GeoStatus.PLACE, geo.status());
        assertEquals(resolver.resolve("Bengaluru", null).placeIds(), geo.placeIds());
        assertNotNull(geo.point());
        assertEquals(77.59, geo.point().lng());

        mongo.remove(new Query(), JobDocument.class);
        store.upsert(AdzunaJobMapper.toDocument(adzuna("adz-2", null, null, null), NOW).orElseThrow(), NOW);
        assertEquals(GeoStatus.COUNTRY, singleJob().getSourceListings().get(0).getGeo().status());
    }

    @Test
    void unresolvedLocationsAreCountedOncePerNormalizedString() {
        store.upsert(imported("lever", "board:one", "Multiple Locations", "fp-one"), NOW, "board");
        store.upsert(imported("lever", "board:one", "Multiple Locations", "fp-one"), NOW.plusHours(1), "board");

        List<Document> recorded = mongo.getCollection(UnresolvedLocationRecorder.COLLECTION).find().into(new java.util.ArrayList<>());
        assertEquals(1, recorded.size());
        assertEquals("multiple locations", recorded.get(0).getString("_id"));
        assertEquals(2, recorded.get(0).getInteger("count"));
        assertEquals("Multiple Locations", recorded.get(0).getString("sample"));
        assertEquals(List.of("lever"), recorded.get(0).getList("providers", String.class));
    }

    private JobDocument singleJob() {
        List<JobDocument> all = mongo.findAll(JobDocument.class);
        assertEquals(1, all.size());
        return all.get(0);
    }

    private static ImportedSourceListing listing(JobDocument job, String identity) {
        return job.getSourceListings().stream().filter(candidate -> identity.equals(candidate.getIdentity()))
                .findFirst().orElseThrow();
    }

    private static JobDocument imported(String provider, String externalId, String location, String fingerprint) {
        JobDocument job = new JobDocument();
        job.setSource(provider);
        job.setExternalId(externalId);
        job.setTitle("Engineer");
        job.setDescription("Description");
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
        listing.setRawLocationInferred(true);
        listing.setGeo(resolver.resolve(location, null));
        job.setSourceListings(List.of(listing));
        return job;
    }

    private static AdzunaResponse.AdzunaJob adzuna(String id, AdzunaResponse.Location location, Double lat, Double lng) {
        return new AdzunaResponse.AdzunaJob(id, "Backend Engineer", "Build APIs", "https://www.adzuna.in/details/" + id,
                null, null, null, null, new AdzunaResponse.Company("Acme"), location, lat, lng);
    }
}
