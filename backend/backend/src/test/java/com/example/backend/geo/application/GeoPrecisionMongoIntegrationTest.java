package com.example.backend.geo.application;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.backend.geo.domain.Place;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

@SpringBootTest
class GeoPrecisionMongoIntegrationTest {
    @Autowired MongoTemplate mongo;
    @Autowired Gazetteer gazetteer;
    @Autowired GeoResolver resolver;
    @Autowired Clock clock;

    @BeforeEach
    void clear() { mongo.getCollection("jobs").deleteMany(new Document()); }

    @Test
    void classifiesJobsAndMeasuresLocalityPotential() {
        String bengaluru = GeoTestJobs.placeId(gazetteer, "Bengaluru");
        String delhi = gazetteer.aliasMatches("delhi").get(0).id();
        LocalityIndex.Locality whitefield = gazetteer.localityMatches("whitefield", bengaluru).get(0);
        Place centre = gazetteer.byId(bengaluru).orElseThrow();
        insert(job("office", "Acme", "Bengaluru", geo("PLACE", bengaluru)));
        insert(job("locality", "Globex", "Whitefield, Bengaluru", geo("PLACE", bengaluru)
                .append("localityIds", List.of(whitefield.id()))));
        insert(job("centre-1", "Initech", "Whitefield, Bengaluru, Karnataka", geo("PLACE", bengaluru)));
        insert(job("centre-2", "Hooli", "Bengaluru, Karnataka, India", geo("PLACE", bengaluru)));
        insert(job("centre-3", "Hooli", "bengaluru, karnataka, india ", geo("PLACE", bengaluru)));
        insert(job("ncr", "Wonka", "Delhi NCR", geo("PLACE", delhi)));
        insert(job("delhi", "Wonka", "New Delhi", geo("PLACE", delhi)));
        insert(job("remote", "Stark", "Remote", geo("REMOTE")));
        insert(job("india", "Stark", "India", geo("COUNTRY")));
        insert(job("state", "Stark", "Karnataka", geo("STATE")));
        insert(job("foreign", "Stark", "London, UK", geo("FOREIGN")));
        insert(job("unresolved", "Stark", "HQ", geo("UNRESOLVED")));
        insert(job("unprocessed", "Stark", "Somewhere", null));
        insert(job("inactive", "Stark", "Bengaluru", geo("PLACE", bengaluru)).append("active", false));
        // Adzuna: one point at the centre, one 1 km from Whitefield (far from the centre), one without a point.
        insert(job("adzuna-centre", "Umbrella", "Bangalore", geo("PLACE", bengaluru)
                .append("point", new Document("lat", centre.lat()).append("lng", centre.lng()))));
        insert(job("adzuna-whitefield", "Umbrella", "Bengaluru Urban", geo("PLACE", bengaluru)
                .append("point", new Document("lat", whitefield.lat() + 0.009).append("lng", whitefield.lng()))));
        insert(job("adzuna-none", "Umbrella", "India", geo("COUNTRY")));
        OfficeIndex offices = new OfficeIndex(List.of(new OfficeIndex.Office("o1", "acme", "Acme", "Acme HQ", bengaluru,
                centre.lat(), centre.lng())));

        GeoPrecisionService.Precision report = new GeoPrecisionService(mongo, gazetteer, resolver, () -> offices, clock,
                30_000).precision();

        Map<String, Long> byPrecision = report.byPrecision();
        GeoPrecisionService.CityGain city = report.topCities().get(0);
        assertAll(
                () -> assertEquals(List.of("office", "locality", "city", "state", "country", "remote", "foreign",
                        "unresolved"), List.copyOf(byPrecision.keySet())),
                () -> assertEquals(1, byPrecision.get("office")),
                () -> assertEquals(1, byPrecision.get("locality")),
                () -> assertEquals(7, byPrecision.get("city")),
                () -> assertEquals(1, byPrecision.get("state")),
                () -> assertEquals(2, byPrecision.get("country")),
                () -> assertEquals(1, byPrecision.get("remote")),
                () -> assertEquals(1, byPrecision.get("foreign")),
                () -> assertEquals(2, byPrecision.get("unresolved")),
                () -> assertEquals(16, report.activeJobs()),
                () -> assertEquals("Bengaluru, Karnataka, India", report.topCityCentreStrings().get(0).location()),
                () -> assertEquals(2, report.topCityCentreStrings().get(0).jobs()),
                () -> assertEquals(bengaluru, city.placeId()),
                () -> assertEquals(7, city.jobs()),
                () -> assertEquals(5, city.centreJobs()),
                () -> assertEquals(1, city.stringsWithLocality(), "only \"Whitefield, Bengaluru, Karnataka\""),
                () -> assertEquals(1, city.jobsWithLocality()),
                () -> assertTrue(city.knownLocalities() > 0),
                () -> assertEquals(3, report.adzuna().jobs()),
                () -> assertEquals(2, report.adzuna().withPoint()),
                () -> assertEquals(1, report.adzuna().offCentre()),
                () -> assertEquals(1, report.adzuna().nearLocality()),
                () -> assertEquals(1, report.ncr().ncrJobs()),
                () -> assertEquals(2, report.ncr().delhiJobs()),
                () -> assertEquals(50.0, report.ncr().percentOfDelhi()),
                () -> assertEquals(0.0, report.reResolution().hoursForAllActiveJobs()));
    }

    @Test
    void isCachedForFiveMinutes() {
        GeoPrecisionService service = new GeoPrecisionService(mongo, gazetteer, resolver, () -> OfficeIndex.EMPTY, clock, 30_000);
        assertEquals(0, service.precision().activeJobs());
        insert(job("remote", "Stark", "Remote", geo("REMOTE")));
        assertEquals(0, service.precision().activeJobs());
    }

    private void insert(Document job) { mongo.getCollection("jobs").insertOne(job); }

    private static Document geo(String status, String... placeIds) {
        return new Document("status", status).append("placeIds", List.of(placeIds)).append("remote", false)
                .append("version", "test");
    }

    private static Document job(String id, String company, String location, Document geo) {
        String provider = id.startsWith("adzuna") ? "adzuna" : "greenhouse";
        Document listing = new Document("identity", provider + ":" + id).append("provider", provider).append("active", true);
        if (geo != null) listing.append("geo", geo);
        return new Document("_id", id).append("title", "Engineer").append("company", company).append("location", location)
                .append("active", true).append("sourceListings", new ArrayList<>(List.of(listing)));
    }
}
