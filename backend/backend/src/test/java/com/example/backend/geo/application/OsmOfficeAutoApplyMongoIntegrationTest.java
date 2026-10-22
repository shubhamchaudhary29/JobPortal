package com.example.backend.geo.application;

import static com.example.backend.geo.application.GeoTestJobs.job;
import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.geo.domain.Place;
import com.example.backend.shared.error.ResourceNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

/** GEO-8D with auto-apply on, against synthetic OSM rows (src/test/resources/geo-test/osm-offices-test.tsv). */
@SpringBootTest(properties = {"geo.osm-offices.file=geo-test/osm-offices-test.tsv", "geo.osm-offices.auto-apply=true",
        "geo.snapshot.enabled=false", "geo.map-jobs.cache-ttl-ms=0"})
class OsmOfficeAutoApplyMongoIntegrationTest {
    @Autowired CompanyOfficeService offices;
    @Autowired MapSnapshotService snapshots;
    @Autowired MapJobService jobs;
    @Autowired Gazetteer gazetteer;
    @Autowired MongoTemplate mongo;
    private String blr;
    private String pune;

    @BeforeEach
    void setUp() {
        for (String collection : List.of("jobs", "company_offices", "company_office_audit", "osm_office_decisions")) {
            mongo.getCollection(collection).deleteMany(new Document());
        }
        blr = GeoTestJobs.placeId(gazetteer, "Bengaluru");
        pune = GeoTestJobs.placeId(gazetteer, "Pune");
        insert(job("a1", "Acme Pvt Ltd", 1, blr));
        insert(job("a2", "Acme Pvt Ltd", 2, blr));
        insert(job("a3", "ACME", 3, blr));
        insert(job("g1", "Globex", 4, blr));
        insert(job("i1", "Initech", 5, blr));
        insert(job("h1", "Hooli", 6, blr));
        insert(job("x1", "Infosys", 7, blr));
        insert(job("w1", "Wonka", 8, blr));
        insert(job("s1", "Stark", 9, pune));
        Place bengaluru = gazetteer.byId(blr).orElseThrow();
        CompanyOfficeService.OfficeView initech = offices.create("Initech", "Old office", bengaluru.lat(), bengaluru.lng(), blr, "admin");
        offices.update(initech.id(), null, null, null, false, "admin");
        offices.rejectOsm("osm-n106", "hooli", blr, "admin");
        snapshots.refresh();
    }

    @Test
    void onlyUnambiguousUnrejectedMatchesWithoutAnAdminOfficeApply() {
        OfficeIndex index = offices.officeIndex();
        OfficeIndex.Office acme = index.officeFor("acme", blr).orElseThrow();
        assertAll(
                () -> assertEquals("osm-n101", acme.id()),
                () -> assertEquals(OfficeIndex.SOURCE_OSM, acme.source()),
                () -> assertEquals("Acme Campus", acme.label()),
                () -> assertEquals("osm-w110", index.officeFor("stark", pune).orElseThrow().id(), "a brand-only row"),
                () -> assertTrue(index.officeFor("globex", blr).isEmpty(), "two candidates are ambiguous"),
                () -> assertTrue(index.officeFor("initech", blr).isEmpty(), "a disabled ADMIN office is a deliberate no"),
                () -> assertTrue(index.officeFor("hooli", blr).isEmpty(), "rejected"),
                () -> assertTrue(index.officeFor("infosys", blr).isEmpty(), "Info Edge is not Infosys"),
                () -> assertTrue(index.officeFor("wonka", blr).isEmpty(), "70 km from Bengaluru: the 60 km guard"),
                () -> assertTrue(index.officeFor("it park", blr).isEmpty()));
    }

    @Test
    void snapshotNamesOsmOfficesAfterTheJobsAndKeepsTheInvariants() {
        MapSnapshotService.MapSnapshot snapshot = snapshots.current().orElseThrow().snapshot();
        Map<String, MapSnapshotService.MapOffice> byId = snapshot.offices().stream()
                .collect(Collectors.toMap(MapSnapshotService.MapOffice::id, office -> office));
        MapSnapshotService.MapOffice acme = byId.get("osm-n101");
        assertEquals("Acme Pvt Ltd", acme.company(), "the most common job spelling, not the OSM name");
        assertEquals("Acme Campus", acme.label());
        assertEquals(3, acme.jobs());
        assertEquals(OfficeIndex.SOURCE_OSM, acme.source());
        assertEquals(1, byId.get("osm-w110").jobs());
        MapSnapshotService.MapPlace bengaluru = snapshot.places().stream().filter(place -> place.id().equals(blr)).findFirst().orElseThrow();
        assertEquals(8, bengaluru.jobs());
        assertEquals(3, bengaluru.officeJobs());
        LocalityMapMongoIntegrationTest.assertInvariant(snapshot);
    }

    @Test
    void officeJobsAndNearbyWorkWithOsmIds() {
        MapJobService.MapJobSlice acme = jobs.office("osm-n101", null, 0, 20);
        assertEquals(List.of("a3", "a2", "a1"), acme.content().stream().map(MapJobService.MapJob::id).toList());
        assertEquals("Acme Campus", acme.content().get(0).places().get(0).officeLabel());
        assertThrows(ResourceNotFoundException.class, () -> jobs.office("osm-n999", null, 0, 20));
        // "Globex Office" is a single match for the exact key "globex office" but one of two for "globex": no Globex jobs.
        assertTrue(jobs.office("osm-n102", null, 0, 20).content().isEmpty());

        MapJobService.NearbyResult nearby = jobs.nearby(12.9352, 77.6245, 5, null, 0, 20);
        MapJobService.NearbyPlace chip = nearby.places().stream().filter(place -> "office".equals(place.kind())).findFirst().orElseThrow();
        assertEquals("osm-n101", chip.officeId());
        assertEquals("Acme Pvt Ltd", chip.company());
        List<MapJobService.MapJob> acmeNearby = nearby.jobs().content().stream()
                .filter(job -> job.company().toLowerCase().contains("acme")).toList();
        assertEquals(3, acmeNearby.size());
        for (MapJobService.MapJob job : acmeNearby) {
            assertEquals("osm-n101", job.places().get(0).officeId());
            assertEquals(chip.distanceKm(), job.places().get(0).distanceKm(), 0.11, "measured to the OSM office");
        }
    }

    @Test
    void worklistShowsMatchesAndWhatWasApplied() {
        Map<String, CompanyOfficeService.Candidate> rows = offices.candidates(50).stream()
                .collect(Collectors.toMap(CompanyOfficeService.Candidate::companyKey, row -> row));
        assertEquals("osm-n101", rows.get("acme").autoApplied());
        assertEquals(List.of("osm-n101"), rows.get("acme").osmCandidates().stream().map(CompanyOfficeService.OsmCandidate::osmRef).toList());
        assertEquals(2, rows.get("globex").osmCandidates().size(), "osm-n104 is the same office as osm-n102");
        assertNull(rows.get("globex").autoApplied());
        assertTrue(rows.get("hooli").osmCandidates().isEmpty(), "rejected matches are not suggested again");
        assertEquals("https://www.openstreetmap.org/node/101", rows.get("acme").osmCandidates().get(0).url());

        CompanyOfficeService.OsmReport report = offices.osmReport();
        assertTrue(report.autoApply());
        assertEquals(4, report.jobsMovedByAutoApply(), "3 Acme + 1 Stark");
        assertEquals(List.of("osm-n101", "osm-w110"), report.topMatches().stream().map(CompanyOfficeService.OsmMatch::osmRef).toList());
        // Initech has an ADMIN office (disabled), so it is not in the report at all.
        assertEquals(new CompanyOfficeService.OsmBucket(2, 2), report.noMatch(), "Hooli (rejected) and Infosys");
        assertEquals(new CompanyOfficeService.OsmBucket(3, 5), report.oneMatch(), "Acme, Stark, and Wonka (blocked by the guard)");
        assertEquals(new CompanyOfficeService.OsmBucket(1, 1), report.severalMatches(), "Globex");
    }

    private void insert(Document job) { mongo.getCollection("jobs").insertOne(job); }
}
