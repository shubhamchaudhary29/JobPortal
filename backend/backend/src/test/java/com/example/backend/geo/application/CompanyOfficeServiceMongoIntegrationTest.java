package com.example.backend.geo.application;

import static com.example.backend.geo.application.GeoTestJobs.job;
import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.geo.domain.Place;
import com.example.backend.shared.error.BadRequestException;
import com.example.backend.shared.error.ResourceNotFoundException;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

@SpringBootTest(properties = "geo.snapshot.enabled=false")
class CompanyOfficeServiceMongoIntegrationTest {
    private static final double[] BELLANDUR = {12.9260, 77.6762};
    private static final double[] CYBER_CITY = {28.4950, 77.0890};
    @Autowired CompanyOfficeService offices;
    @Autowired Gazetteer gazetteer;
    @Autowired MongoTemplate mongo;
    private String blr;
    private String pune;

    @BeforeEach
    void setUp() {
        mongo.getCollection("jobs").deleteMany(new Document());
        mongo.getCollection("company_offices").deleteMany(new Document());
        mongo.getCollection("company_office_audit").deleteMany(new Document());
        blr = GeoTestJobs.placeId(gazetteer, "Bengaluru");
        pune = GeoTestJobs.placeId(gazetteer, "Pune");
        offices.loadIndex();
    }

    @Test
    void createsUpdatesAndDisablesWithAudit() {
        CompanyOfficeService.OfficeView office = offices.create(" Acme Pvt Ltd ", "Bellandur campus", BELLANDUR[0], BELLANDUR[1],
                blr, "admin@example.com");
        assertEquals("Acme Pvt Ltd", office.company());
        assertEquals("acme", office.companyKey());
        assertEquals(blr, office.placeId());
        assertEquals("Bengaluru", office.placeName());
        assertTrue(office.enabled());
        assertTrue(offices.officeIndex().officeFor("acme", blr).isPresent());

        CompanyOfficeService.OfficeView moved = offices.update(office.id(), "Bellandur tech park", 12.93, 77.68, null, "editor@example.com");
        assertEquals("Bellandur tech park", moved.label());
        assertEquals(12.93, moved.lat());
        assertEquals("editor@example.com", moved.updatedBy());

        offices.update(office.id(), null, null, null, false, "editor@example.com");
        assertTrue(offices.officeIndex().officeFor("acme", blr).isEmpty());

        List<CompanyOfficeService.AuditView> audit = offices.audit(office.id(), 0, 20).content();
        assertEquals(List.of("DISABLE", "UPDATE", "CREATE"), audit.stream().map(CompanyOfficeService.AuditView::action).toList());
        assertEquals("admin@example.com", audit.get(2).actor());
        assertNull(audit.get(2).before());
        assertEquals("Bellandur campus", audit.get(1).before().get("label"));
        assertEquals("Bellandur tech park", audit.get(1).after().get("label"));
        assertEquals(true, audit.get(0).before().get("enabled"));
        assertEquals(false, audit.get(0).after().get("enabled"));

        assertEquals(1, offices.list("acme", null, 0, 20).content().size());
        assertEquals(0, offices.list(null, true, 0, 20).content().size());
        assertEquals(moved.id(), offices.get(office.id()).id());
    }

    @Test
    void assignsTheNearestLargeCityWhenNoPlaceIsGiven() {
        CompanyOfficeService.OfficeView office = offices.create("Acme", "Cyber City", CYBER_CITY[0], CYBER_CITY[1], null, "admin");
        Place place = gazetteer.byId(office.placeId()).orElseThrow();
        assertEquals("Gurugram", place.name());
    }

    @Test
    void rejectsBadInput() {
        assertMessage("inside India", () -> offices.create("Acme", "HQ", 40.0, 77.0, null, "admin"));
        assertMessage("inside India", () -> offices.create("Acme", "HQ", 12.9, 100.0, null, "admin"));
        assertMessage("inside India", () -> offices.create("Acme", "HQ", Double.NaN, 77.0, null, "admin"));
        // Mysuru is about 130 km from Bengaluru's centre.
        assertMessage("Pin is far from Bengaluru", () -> offices.create("Acme", "HQ", 12.30, 76.64, blr, "admin"));
        assertMessage("Unknown place", () -> offices.create("Acme", "HQ", 12.93, 77.68, "999999999", "admin"));
        assertMessage("Company", () -> offices.create("  ", "HQ", 12.93, 77.68, blr, "admin"));
        assertMessage("letters or digits", () -> offices.create("-- .. --", "HQ", 12.93, 77.68, blr, "admin"));
        assertMessage("Label", () -> offices.create("Acme", "H", 12.93, 77.68, blr, "admin"));

        offices.create("Acme", "HQ", 12.93, 77.68, blr, "admin");
        assertMessage("already has an office called \"HQ\" in Bengaluru",
                () -> offices.create("ACME Private Limited", "HQ", 12.95, 77.60, blr, "admin"));
        CompanyOfficeService.OfficeView second = offices.create("Acme", "Whitefield", 12.97, 77.75, blr, "admin");
        assertMessage("already has an office", () -> offices.update(second.id(), "HQ", null, null, null, "admin"));
        assertMessage("Pin is far from", () -> offices.update(second.id(), null, 12.30, 76.64, null, "admin"));
        assertThrows(ResourceNotFoundException.class, () -> offices.update("665f1c2e9b1e8a0012345678", "X1", null, null, null, "a"));
    }

    @Test
    void twoEnabledOfficesForOneCompanyAndCityAreAmbiguous() {
        offices.create("Acme", "Bellandur", BELLANDUR[0], BELLANDUR[1], blr, "admin");
        assertTrue(offices.officeIndex().officeFor("acme", blr).isPresent());
        CompanyOfficeService.OfficeView second = offices.create("ACME", "Whitefield", 12.97, 77.75, blr, "admin");
        assertTrue(offices.officeIndex().officeFor("acme", blr).isEmpty());
        assertTrue(offices.officeIndex().byId(second.id()).isPresent());
        offices.update(second.id(), null, null, null, false, "admin");
        assertEquals("Bellandur", offices.officeIndex().officeFor("acme", blr).orElseThrow().label());
    }

    @Test
    void candidatesRankCompanyCityPairsAndSkipPinnedOnes() {
        for (int day = 1; day <= 3; day++) mongo.getCollection("jobs").insertOne(job("a" + day, "Acme Pvt Ltd", day, blr));
        mongo.getCollection("jobs").insertOne(job("a4", "ACME", 4, blr));
        mongo.getCollection("jobs").insertOne(job("a5", "acme", 5, pune));
        mongo.getCollection("jobs").insertOne(job("g1", "Globex", 6, blr, pune));
        mongo.getCollection("jobs").insertOne(job("g2", "Globex", 7, pune));
        mongo.getCollection("jobs").insertOne(job("r1", "Remoteco", 8));
        mongo.getCollection("jobs").insertOne(job("x1", "Acme", 9, blr).append("active", false));

        List<CompanyOfficeService.Candidate> ranked = offices.candidates(50);
        assertEquals(new CompanyOfficeService.Candidate("Acme Pvt Ltd", "acme", blr, "Bengaluru", "Karnataka", 4), ranked.get(0));
        assertEquals("globex", ranked.get(1).companyKey());
        assertEquals(pune, ranked.get(1).placeId());
        assertEquals(2, ranked.get(1).jobs());
        assertEquals(4, ranked.size(), "Acme BLR, Globex Pune, Acme Pune, Globex BLR; no remote company");
        assertEquals(1, offices.candidates(1).size());

        offices.create("Acme", "Bellandur", BELLANDUR[0], BELLANDUR[1], blr, "admin");
        List<CompanyOfficeService.Candidate> after = offices.candidates(50);
        assertTrue(after.stream().noneMatch(candidate -> candidate.companyKey().equals("acme") && candidate.placeId().equals(blr)));
        assertTrue(after.stream().anyMatch(candidate -> candidate.companyKey().equals("acme") && candidate.placeId().equals(pune)));
        assertThrows(BadRequestException.class, () -> offices.candidates(0));
    }

    private static void assertMessage(String expected, org.junit.jupiter.api.function.Executable call) {
        BadRequestException failure = assertThrows(BadRequestException.class, call);
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }
}
