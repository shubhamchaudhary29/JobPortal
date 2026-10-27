package com.example.backend.geo.application;

import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.geo.domain.Place;
import com.example.backend.shared.error.BadRequestException;
import com.example.backend.shared.error.ResourceNotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Localities in the snapshot and the map job APIs (GEO-8C). Cache TTL 0 so every call reaches MongoDB. */
@SpringBootTest(properties = {"geo.snapshot.enabled=false", "geo.map-jobs.cache-ttl-ms=0"})
class LocalityMapMongoIntegrationTest {
    @Autowired CompanyOfficeService offices;
    @Autowired MapSnapshotService snapshots;
    @Autowired MapJobService jobs;
    @Autowired Gazetteer gazetteer;
    @Autowired MongoTemplate mongo;
    private String blr;
    private String pune;
    private LocalityIndex.Locality whitefield;
    private LocalityIndex.Locality koramangala;

    @BeforeEach
    void setUp() {
        mongo.getCollection("jobs").deleteMany(new Document());
        mongo.getCollection("company_offices").deleteMany(new Document());
        mongo.getCollection("company_office_audit").deleteMany(new Document());
        offices.loadIndex();
        blr = GeoTestJobs.placeId(gazetteer, "Bengaluru");
        pune = GeoTestJobs.placeId(gazetteer, "Pune");
        whitefield = gazetteer.localityMatches("whitefield", blr).get(0);
        koramangala = gazetteer.localityMatches("koramangala", blr).get(0);
        Place bengaluru = gazetteer.byId(blr).orElseThrow();
        offices.create("Acme", "Acme HQ", bengaluru.lat(), bengaluru.lng() + 0.01, blr, "admin");
        insert(job("w1", "Globex", 1, listing(true, List.of(blr), whitefield.id())));
        insert(job("w2", "Initech", 2, listing(true, List.of(blr), whitefield.id(), koramangala.id())));
        insert(job("k1", "Hooli", 3, listing(true, List.of(blr), koramangala.id())));
        insert(job("a1", "Acme", 4, listing(true, List.of(blr), whitefield.id())));
        insert(job("c1", "Wonka", 5, listing(true, List.of(blr))));
        insert(job("c2", "Stark", 6, listing(true, List.of(blr))));
        insert(job("m1", "Wayne", 7, listing(true, List.of(blr, pune), koramangala.id())));
        insert(job("p1", "Wayne", 8, listing(true, List.of(pune))));
        // Only the inactive listing names Whitefield, so this job is at the centre.
        insert(job("x1", "Umbrella", 9, listing(false, List.of(blr), whitefield.id()), listing(true, List.of(blr))));
        insert(new Document("_id", "t1").append("title", "Engineer t1").append("company", "Tyrell").append("active", true)
                .append("createdAt", Date.from(Instant.parse("2026-10-11T00:00:00Z")))
                .append("geo", geo(List.of(blr), koramangala.id())));
        snapshots.refresh();
    }

    @AfterEach
    void tearDown() {
        mongo.getCollection("jobs").deleteMany(new Document());
        mongo.getCollection("company_offices").deleteMany(new Document());
        mongo.getCollection("company_office_audit").deleteMany(new Document());
        offices.loadIndex();
    }

    @Test
    void eachJobCountsOncePerPlaceAtItsOfficeFirstLocalityOrCentre() {
        MapSnapshotService.MapSnapshot snapshot = snapshots.current().orElseThrow().snapshot();
        MapSnapshotService.MapPlace bengaluru = place(snapshot, blr);
        MapSnapshotService.MapPlace pune = place(snapshot, this.pune);
        Map<String, Long> byLocality = snapshot.localities().stream()
                .collect(Collectors.toMap(MapSnapshotService.MapLocality::id, MapSnapshotService.MapLocality::jobs));
        assertAll(
                () -> assertEquals(9, bengaluru.jobs()),
                () -> assertEquals(1, bengaluru.officeJobs()),
                () -> assertEquals(5, bengaluru.localityJobs()),
                () -> assertEquals(3, bengaluru.centreJobs()),
                () -> assertEquals(2, pune.jobs()),
                () -> assertEquals(0, pune.localityJobs()),
                () -> assertEquals(Map.of(whitefield.id(), 2L, koramangala.id(), 3L), byLocality),
                () -> assertEquals(10, snapshot.placedJobs()));
        MapSnapshotService.MapLocality first = snapshot.localities().get(0);
        LocalityIndex.Locality expected = gazetteer.localityById(first.id()).orElseThrow();
        assertEquals(new MapSnapshotService.MapLocality(expected.id(), expected.name(), blr, expected.lat(), expected.lng(),
                first.jobs()), first);
        assertInvariant(snapshot);
    }

    @Test
    void unlistedPlaceListHoldsOnlyCentreJobs() {
        MapJobService.MapJobSlice unlisted = jobs.place(blr, "unlisted", null, 0, 20);
        assertEquals(Set.of("c1", "c2", "x1"), ids(unlisted));
        assertEquals(3, unlisted.totalHint());
        assertTrue(unlisted.content().stream().allMatch(job -> job.places().get(0).localityId() == null
                && job.places().get(0).officeId() == null));

        MapJobService.MapJobSlice all = jobs.place(blr, "all", null, 0, 20);
        assertEquals(9, all.content().size());
        assertEquals(9, all.totalHint());
        assertEquals("Whitefield", byId(all, "w1").places().get(0).localityName());
        assertEquals(whitefield.id(), byId(all, "w2").places().get(0).localityId(), "the first locality wins");
        assertEquals("Acme HQ", byId(all, "a1").places().get(0).officeLabel());
        assertNull(byId(all, "a1").places().get(0).localityId(), "an office beats a locality");
        assertNull(byId(all, "c1").places().get(0).localityId());
        assertEquals(ids(all), ids(jobs.place(blr, null, null, 0, 20)), "no scope means all");
        assertEquals(Set.of("m1", "p1"), ids(jobs.place(pune, "unlisted", null, 0, 20)), "a Bengaluru locality is not a Pune one");
        assertThrows(BadRequestException.class, () -> jobs.place(blr, "office", null, 0, 20));
    }

    @Test
    void localityListExcludesPinnedOfficeCompanies() {
        MapJobService.MapJobSlice white = jobs.locality(whitefield.id(), null, 0, 20);
        assertEquals(Set.of("w1", "w2"), ids(white));
        assertEquals(2, white.totalHint());
        assertTrue(white.content().stream().allMatch(job -> whitefield.id().equals(job.places().get(0).localityId())));
        MapJobService.MapJobSlice kora = jobs.locality(koramangala.id(), null, 0, 20);
        assertEquals(Set.of("w2", "k1", "m1", "t1"), ids(kora), "a job is listed at each of its localities");
        assertEquals(3, kora.totalHint(), "but counted at its first");
        assertNull(jobs.locality(koramangala.id(), "nothing-matches-this", 0, 20).totalHint());
        assertThrows(BadRequestException.class, () -> jobs.locality("whitefield", null, 0, 20));
        assertThrows(BadRequestException.class, () -> jobs.locality("osm-x1", null, 0, 20));
        assertThrows(ResourceNotFoundException.class, () -> jobs.locality("osm-n1", null, 0, 20));
    }

    @Test
    void nearbyShowsLocalitiesAtTheirOwnPointAndMeasuresToThem() {
        MapJobService.NearbyResult result = jobs.nearby(whitefield.lat(), whitefield.lng(), 5, null, 0, 20);

        MapJobService.NearbyPlace chip = result.places().stream().filter(place -> "locality".equals(place.kind()))
                .findFirst().orElseThrow();
        assertEquals(whitefield.id(), chip.localityId());
        assertEquals(whitefield.name(), chip.label());
        assertEquals(blr, chip.id());
        assertEquals(2, chip.jobs());
        assertTrue(result.places().stream().noneMatch(place -> "city".equals(place.kind())), "Bengaluru's centre is out of range");
        assertEquals(Set.of("w1", "w2"), result.jobs().content().stream().map(MapJobService.MapJob::id).collect(Collectors.toSet()));
        for (MapJobService.MapJob job : result.jobs().content()) {
            MapJobService.MapJobPlace at = job.places().get(0);
            assertEquals(whitefield.id(), at.localityId());
            assertTrue(at.distanceKm() < 5, "measured to Whitefield, not the city centre: " + at.distanceKm());
        }
    }

    /** Per place: jobs = officeJobs + localityJobs + centreJobs, centreJobs >= 0, localities sum to localityJobs. */
    static void assertInvariant(MapSnapshotService.MapSnapshot snapshot) {
        Map<String, Long> byPlace = snapshot.localities().stream().collect(Collectors.groupingBy(
                MapSnapshotService.MapLocality::placeId, Collectors.summingLong(MapSnapshotService.MapLocality::jobs)));
        for (MapSnapshotService.MapPlace place : snapshot.places()) {
            assertTrue(place.centreJobs() >= 0, place.name());
            assertEquals(place.jobs(), place.officeJobs() + place.localityJobs() + place.centreJobs(), place.name());
            assertEquals(place.localityJobs(), byPlace.getOrDefault(place.id(), 0L), place.name());
        }
        Set<String> places = snapshot.places().stream().map(MapSnapshotService.MapPlace::id).collect(Collectors.toSet());
        assertTrue(places.containsAll(byPlace.keySet()), "every locality's city is in the snapshot");
    }

    private void insert(Document job) { mongo.getCollection("jobs").insertOne(job); }

    private static Set<String> ids(MapJobService.MapJobSlice slice) {
        return slice.content().stream().map(MapJobService.MapJob::id).collect(Collectors.toSet());
    }

    private static MapJobService.MapJob byId(MapJobService.MapJobSlice slice, String id) {
        return slice.content().stream().filter(job -> job.id().equals(id)).findFirst().orElseThrow();
    }

    private static MapSnapshotService.MapPlace place(MapSnapshotService.MapSnapshot snapshot, String id) {
        return snapshot.places().stream().filter(place -> place.id().equals(id)).findFirst().orElseThrow();
    }

    private static Document geo(List<String> places, String... localities) {
        return new Document("status", "PLACE").append("placeIds", places).append("remote", false).append("version", "test")
                .append("localityIds", List.of(localities));
    }

    private static Document listing(boolean active, List<String> places, String... localities) {
        return new Document("identity", "lever:" + places + List.of(localities) + active).append("provider", "lever")
                .append("active", active).append("geo", geo(places, localities));
    }

    private static Document job(String id, String company, int day, Document... listings) {
        return new Document("_id", id).append("title", "Engineer " + id).append("description", "about " + id)
                .append("company", company).append("location", "Somewhere").append("source", "lever").append("active", true)
                .append("createdAt", Date.from(Instant.parse("2026-10-01T00:00:00Z").plusSeconds(86_400L * day)))
                .append("sourceListings", new ArrayList<>(List.of(listings)));
    }
}
