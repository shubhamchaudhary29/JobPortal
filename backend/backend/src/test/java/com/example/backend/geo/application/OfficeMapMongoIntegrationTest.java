package com.example.backend.geo.application;

import static com.example.backend.geo.application.GeoTestJobs.job;
import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.geo.domain.Place;
import com.example.backend.shared.error.ResourceNotFoundException;
import com.example.backend.shared.text.CompanyNames;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Office pins in the snapshot and the map job APIs (GEO-6C). Cache TTL 0 so every call reaches MongoDB. */
@SpringBootTest(properties = {"geo.snapshot.enabled=false", "geo.map-jobs.cache-ttl-ms=0"})
class OfficeMapMongoIntegrationTest {
    @Autowired CompanyOfficeService offices;
    @Autowired MapSnapshotService snapshots;
    @Autowired MapJobService jobs;
    @Autowired Gazetteer gazetteer;
    @Autowired MongoTemplate mongo;
    private String blr;
    private String pune;
    private Place bengaluru;
    /** About 3 km east of the gazetteer's Bengaluru centre. */
    private double officeLat;
    private double officeLng;

    @BeforeEach
    void setUp() {
        mongo.getCollection("jobs").deleteMany(new Document());
        mongo.getCollection("company_offices").deleteMany(new Document());
        mongo.getCollection("company_office_audit").deleteMany(new Document());
        offices.loadIndex();
        blr = GeoTestJobs.placeId(gazetteer, "Bengaluru");
        pune = GeoTestJobs.placeId(gazetteer, "Pune");
        bengaluru = gazetteer.byId(blr).orElseThrow();
        officeLat = bengaluru.lat();
        officeLng = bengaluru.lng() + 3 / (111.32 * Math.cos(Math.toRadians(bengaluru.lat())));
        insert(job("a1", "Acme Pvt Ltd", 1, blr));
        insert(job("a2", "ACME", 2, blr));
        insert(job("a3", "acme pvt ltd ", 3, blr, pune));
        insert(job("a4", "Acme Pvt Ltd", 4, pune));
        insert(job("g1", "Globex", 5, blr));
        insert(job("x1", "Acme Pvt Ltd", 6, blr).append("active", false));
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
    void officeJobsAreAddedWithoutChangingCityCounts() {
        MapSnapshotService.MapSnapshot before = snapshots.current().orElseThrow().snapshot();
        assertEquals(List.of(), before.offices());
        CompanyOfficeService.OfficeView office = offices.create("Acme Pvt Ltd", "Bellandur", officeLat, officeLng, blr, "admin");
        snapshots.refresh();
        MapSnapshotService.MapSnapshot after = snapshots.current().orElseThrow().snapshot();

        assertEquals(before.placedJobs(), after.placedJobs());
        assertEquals(List.of(new MapSnapshotService.MapOffice(office.id(), "Acme Pvt Ltd", "Bellandur", blr, officeLat, officeLng, 3)),
                after.offices());
        assertEquals(place(before, blr).jobs(), place(after, blr).jobs());
        assertEquals(4, place(after, blr).jobs());
        assertEquals(3, place(after, blr).officeJobs());
        assertEquals(place(before, pune), place(after, pune), "Pune has no Acme office");
        assertEquals(Set.of("Acme Pvt Ltd", "ACME", "acme pvt ltd "), snapshots.officeCompanies(office.id()));
        assertNotEquals(before.version(), after.version());

        offices.create("ACME", "Whitefield", 12.97, 77.75, blr, "admin");
        snapshots.refresh();
        MapSnapshotService.MapSnapshot ambiguous = snapshots.current().orElseThrow().snapshot();
        assertEquals(List.of(), ambiguous.offices());
        assertEquals(0, place(ambiguous, blr).officeJobs());
        assertEquals(before.placedJobs(), ambiguous.placedJobs());
    }

    @Test
    void officeListIsThePlaceDefinitionPlusTheCompany() {
        CompanyOfficeService.OfficeView office = offices.create("Acme", "Bellandur", officeLat, officeLng, blr, "admin");
        snapshots.refresh();

        Set<String> expected = new HashSet<>();
        mongo.getCollection("jobs").aggregate(List.of(new Document("$match", GeoPipelines.visibleJobs()),
                new Document("$project", new Document("company", 1).append("placeIds", GeoPipelines.placeIds()))))
                .forEach(row -> {
                    if (row.getList("placeIds", String.class).contains(blr) && CompanyNames.key(row.getString("company")).equals("acme")) {
                        expected.add(row.getString("_id"));
                    }
                });
        assertEquals(Set.of("a1", "a2", "a3"), expected);
        MapJobService.MapJobSlice slice = jobs.office(office.id(), null, 0, 20);
        assertEquals(expected, ids(slice));
        assertEquals(3L, slice.totalHint());
        assertEquals(new MapJobService.MapJobPlace(blr, "Bengaluru", null, office.id(), "Bellandur"), slice.content().get(0).places().get(0));
        assertEquals(List.of("a3", "a2", "a1"), slice.content().stream().map(MapJobService.MapJob::id).toList(), "newest first");

        MapJobService.MapJobSlice searched = jobs.office(office.id(), "a2", 0, 20);
        assertEquals(Set.of("a2"), ids(searched));
        assertNull(searched.totalHint());

        MapJobService.MapJob placeJob = jobs.place(blr, null, 0, 20).content().stream()
                .filter(job -> job.id().equals("a1")).findFirst().orElseThrow();
        assertEquals("Bellandur", placeJob.places().get(0).officeLabel());
        MapJobService.MapJob globex = jobs.place(blr, null, 0, 20).content().stream()
                .filter(job -> job.id().equals("g1")).findFirst().orElseThrow();
        assertNull(globex.places().get(0).officeId());

        assertThrows(ResourceNotFoundException.class, () -> jobs.office("665f1c2e9b1e8a0012345678", null, 0, 20));
        offices.update(office.id(), null, null, null, false, "admin");
        assertThrows(ResourceNotFoundException.class, () -> jobs.office(office.id(), null, 0, 20));
    }

    @Test
    void nearbyIncludesOfficesWithTheirOwnDistance() {
        CompanyOfficeService.OfficeView office = offices.create("Acme", "Bellandur", officeLat, officeLng, blr, "admin");
        snapshots.refresh();

        MapJobService.NearbyResult result = jobs.nearby(bengaluru.lat(), bengaluru.lng(), 25, null, 0, 20);
        double expectedKm = Math.round(MapJobService.haversineKm(result.centerLat(), result.centerLng(), officeLat, officeLng) * 10) / 10.0;
        MapJobService.NearbyPlace officeChip = result.places().stream().filter(place -> "office".equals(place.kind()))
                .findFirst().orElseThrow();
        assertEquals(office.id(), officeChip.officeId());
        assertEquals(blr, officeChip.id());
        assertEquals("Acme", officeChip.company());
        assertEquals("Bellandur", officeChip.label());
        assertEquals(3, officeChip.jobs());
        assertEquals(expectedKm, officeChip.distanceKm());
        assertTrue(Math.abs(expectedKm - 3) < 4, "office about 3 km from the centre (search centre is grid-rounded)");
        MapJobService.NearbyPlace cityChip = result.places().stream().filter(place -> place.id().equals(blr) && "city".equals(place.kind()))
                .findFirst().orElseThrow();
        assertNull(cityChip.officeId());
        List<Double> distances = result.places().stream().map(MapJobService.NearbyPlace::distanceKm).toList();
        assertEquals(distances.stream().sorted().toList(), distances);

        MapJobService.MapJob acme = result.jobs().content().stream().filter(job -> job.id().equals("a1")).findFirst().orElseThrow();
        assertEquals(office.id(), acme.places().get(0).officeId());
        assertEquals(expectedKm, acme.places().get(0).distanceKm());
        MapJobService.MapJob globex = result.jobs().content().stream().filter(job -> job.id().equals("g1")).findFirst().orElseThrow();
        assertNull(globex.places().get(0).officeId());
        assertEquals(cityChip.distanceKm(), globex.places().get(0).distanceKm());
    }

    @Test
    void anOfficeWhoseCityCentreIsOutOfRangeStillBringsItsOwnJobs() {
        // Bengaluru's centre is ~20 km from this point; the office is ~1.5 km away, inside a 10 km radius.
        double lat = bengaluru.lat() + 0.18;
        double lng = bengaluru.lng();
        CompanyOfficeService.OfficeView office = offices.create("Acme", "North campus", lat - 0.0135, lng, blr, "admin");
        snapshots.refresh();
        MapJobService.NearbyResult result = jobs.nearby(lat, lng, 10, null, 0, 20);
        assertTrue(result.places().stream().noneMatch(place -> "city".equals(place.kind()) && place.id().equals(blr)));
        assertEquals(office.id(), result.places().get(0).officeId());
        assertEquals(Set.of("a1", "a2", "a3"), ids(result.jobs()), "only the office's jobs, not all of Bengaluru");
    }

    private void insert(Document job) { mongo.getCollection("jobs").insertOne(job); }

    private static MapSnapshotService.MapPlace place(MapSnapshotService.MapSnapshot snapshot, String id) {
        return snapshot.places().stream().filter(place -> place.id().equals(id)).findFirst().orElseThrow();
    }

    private static Set<String> ids(MapJobService.MapJobSlice slice) {
        Set<String> ids = new HashSet<>();
        slice.content().forEach(job -> ids.add(job.id()));
        return ids;
    }
}
