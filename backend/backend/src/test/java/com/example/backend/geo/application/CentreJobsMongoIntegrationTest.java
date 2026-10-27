package com.example.backend.geo.application;

import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.geo.domain.Place;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

/** City-centre jobs (GEO-9D/9E): explicit precision and site labels on job places. Cache TTL 0 so every call reaches MongoDB. */
@SpringBootTest(properties = {"geo.snapshot.enabled=false", "geo.map-jobs.cache-ttl-ms=0"})
class CentreJobsMongoIntegrationTest {
    @Autowired CompanyOfficeService offices;
    @Autowired MapSnapshotService snapshots;
    @Autowired MapJobService jobs;
    @Autowired Gazetteer gazetteer;
    @Autowired MongoTemplate mongo;
    private String blr;
    private String pune;
    private LocalityIndex.Locality whitefield;

    @BeforeEach
    void setUp() {
        mongo.getCollection("jobs").deleteMany(new Document());
        mongo.getCollection("company_offices").deleteMany(new Document());
        mongo.getCollection("company_office_audit").deleteMany(new Document());
        offices.loadIndex();
        blr = GeoTestJobs.placeId(gazetteer, "Bengaluru");
        pune = GeoTestJobs.placeId(gazetteer, "Pune");
        whitefield = gazetteer.localityMatches("whitefield", blr).get(0);
        Place bengaluru = gazetteer.byId(blr).orElseThrow();
        offices.create("Acme", "Acme HQ", bengaluru.lat(), bengaluru.lng() + 0.01, blr, "admin");
        insert(job("a1", "Acme", 1, listing(true, List.of(blr), "EGL Tower")));
        insert(job("w1", "Globex", 2, listing(true, List.of(blr), null, whitefield.id())));
        insert(job("c1", "Wonka", 3, listing(true, List.of(blr), "We Work")));
        insert(job("c2", "Wonka", 4, listing(true, List.of(blr), null)));
        insert(job("c3", "Wonka", 5, listing(true, List.of(blr), "We Work")));
        insert(job("c4", "Hooli", 6, listing(true, List.of(blr), null)));
        insert(job("c5", "Hooli", 7, listing(true, List.of(blr), null)));
        insert(job("c6", "Stark", 8, listing(true, List.of(blr), null)));
        // The inactive listing's label is ignored; the active one names Bengaluru and Pune.
        insert(job("m1", "Initech", 9, listing(false, List.of(blr), "Old"), listing(true, List.of(blr, pune), "Urmi")));
        insert(new Document("_id", "t1").append("title", "Engineer t1").append("company", "Tyrell").append("active", true)
                .append("createdAt", Date.from(Instant.parse("2026-10-20T00:00:00Z")))
                .append("geo", new Document("status", "PLACE").append("placeIds", List.of(blr)).append("remote", false)
                        .append("version", "test").append("siteLabel", "OUC")));
        snapshots.refresh();
    }

    @Test
    void jobPlacesCarryExplicitPrecisionAndTheirListingsSiteLabel() {
        MapJobService.MapJobSlice all = jobs.place(blr, "all", null, 0, 20);

        assertPlace(all, "a1", "office", "EGL Tower");
        assertPlace(all, "w1", "locality", null);
        assertPlace(all, "c1", "city", "We Work");
        assertPlace(all, "c2", "city", null);
        assertPlace(all, "m1", "city", "Urmi");
        assertPlace(all, "t1", "city", "OUC");
        MapJobService.MapJobPlace punePlace = byId(jobs.place(pune, null, null, 0, 20), "m1").places().get(0);
        assertEquals("Urmi", punePlace.siteLabel(), "the listing's label applies to each of its places");
    }

    @Test
    void oldConstructorsDerivePrecision() {
        assertEquals("city", new MapJobService.MapJobPlace("1", "A", null).precision());
        assertEquals("office", new MapJobService.MapJobPlace("1", "A", null, "o1", "HQ").precision());
        assertEquals("locality", new MapJobService.MapJobPlace("1", "A", null, null, null, "osm-n1", "W").precision());
        assertNull(new MapJobService.MapJobPlace("1", "A", null).siteLabel());
    }

    private static void assertPlace(MapJobService.MapJobSlice slice, String id, String precision, String siteLabel) {
        MapJobService.MapJobPlace place = byId(slice, id).places().get(0);
        assertEquals(precision, place.precision(), id);
        assertEquals(siteLabel, place.siteLabel(), id);
    }

    static MapJobService.MapJob byId(MapJobService.MapJobSlice slice, String id) {
        return slice.content().stream().filter(job -> job.id().equals(id)).findFirst().orElseThrow(() -> new AssertionError(id));
    }

    private void insert(Document job) { mongo.getCollection("jobs").insertOne(job); }

    private static Document job(String id, String company, int day, Document... listings) {
        return new Document("_id", id).append("title", "Engineer " + id).append("company", company).append("location", "Somewhere")
                .append("source", "lever").append("active", true)
                .append("createdAt", Date.from(Instant.parse("2026-10-01T00:00:00Z").plusSeconds(86_400L * day)))
                .append("sourceListings", new ArrayList<>(List.of(listings)));
    }

    private static Document listing(boolean active, List<String> placeIds, String siteLabel, String... localityIds) {
        Document geo = new Document("status", "PLACE").append("placeIds", placeIds).append("remote", false)
                .append("version", "test").append("localityIds", List.of(localityIds));
        if (siteLabel != null) geo.append("siteLabel", siteLabel);
        return new Document("identity", "lever:" + placeIds.hashCode() + ":" + active).append("active", active).append("geo", geo);
    }
}
