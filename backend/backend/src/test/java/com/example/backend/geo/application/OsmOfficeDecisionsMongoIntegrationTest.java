package com.example.backend.geo.application;

import static com.example.backend.geo.application.GeoTestJobs.job;
import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.geo.infrastructure.CompanyOfficeAuditDocument;
import com.example.backend.geo.infrastructure.CompanyOfficeDocument;
import com.example.backend.geo.infrastructure.OsmOfficeDecisionDocument;
import com.example.backend.shared.error.BadRequestException;
import com.example.backend.shared.error.ResourceNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

/** GEO-8D with auto-apply off (the default): suggestions only, and audited ADMIN pick/reject decisions. */
@SpringBootTest(properties = {"geo.osm-offices.file=geo-test/osm-offices-test.tsv", "geo.snapshot.enabled=false"})
class OsmOfficeDecisionsMongoIntegrationTest {
    @Autowired CompanyOfficeService offices;
    @Autowired Gazetteer gazetteer;
    @Autowired MongoTemplate mongo;
    private String blr;

    @BeforeEach
    void setUp() {
        for (String collection : List.of("jobs", "company_offices", "company_office_audit", "osm_office_decisions")) {
            mongo.getCollection(collection).deleteMany(new Document());
        }
        blr = GeoTestJobs.placeId(gazetteer, "Bengaluru");
        insert(job("a1", "Acme Pvt Ltd", 1, blr));
        insert(job("a2", "ACME", 2, blr));
        insert(job("a3", "Acme Pvt Ltd", 3, blr));
        insert(job("g1", "Globex", 4, blr));
        insert(job("w1", "Wonka", 5, blr));
        offices.loadIndex();
    }

    @Test
    void withTheFlagOffMatchesAreOnlySuggested() {
        assertFalse(offices.osmAutoApply());
        assertTrue(offices.officeIndex().officeFor("acme", blr).isEmpty());
        Map<String, CompanyOfficeService.Candidate> rows = rows();
        assertEquals(1, rows.get("acme").osmCandidates().size());
        assertNull(rows.get("acme").autoApplied());
        CompanyOfficeService.OsmReport report = offices.osmReport();
        assertFalse(report.autoApply());
        assertEquals(3, report.jobsMovedByAutoApply(), "what auto-apply would do: Acme's three jobs");
        assertEquals("osm-n101", report.topMatches().get(0).osmRef());
        assertEquals("Acme Pvt Ltd", report.topMatches().get(0).company());
        assertEquals("Bengaluru", report.topMatches().get(0).placeName());
        assertEquals("Acme Campus", report.topMatches().get(0).osmName());
    }

    @Test
    void pickCreatesAnAuditedAdminOfficeAtTheOsmPoint() {
        CompanyOfficeService.OfficeView office = offices.pickOsm("osm-n101", "acme", blr, null, "admin@example.com");

        assertAll(
                () -> assertEquals("Acme Pvt Ltd", office.company(), "the most common job spelling"),
                () -> assertEquals("Acme Campus", office.label()),
                () -> assertEquals(12.9352, office.lat()),
                () -> assertEquals("OSM", office.source()),
                () -> assertEquals("osm-n101", office.osmRef()),
                () -> assertTrue(office.enabled()),
                () -> assertEquals(office.id(), offices.officeIndex().officeFor("acme", blr).orElseThrow().id()),
                () -> assertEquals(OfficeIndex.SOURCE_ADMIN, offices.officeIndex().officeFor("acme", blr).orElseThrow().source()));
        List<String> actions = offices.audit(office.id(), 0, 20).content().stream().map(CompanyOfficeService.AuditView::action).toList();
        assertEquals(List.of("OSM_PICK", "CREATE"), actions);
        OsmOfficeDecisionDocument decision = mongo.findAll(OsmOfficeDecisionDocument.class).get(0);
        assertEquals(OsmOfficeDecisionDocument.Decision.PICK, decision.getDecision());
        assertEquals("admin@example.com", decision.getActor());
        assertEquals("OSM", mongo.findById(office.id(), CompanyOfficeDocument.class).getSource());
        assertTrue(rows().get("acme") == null, "a picked pair leaves the worklist like any pinned pair");
    }

    @Test
    void pickKeepsTheGuardsOfANormalOffice() {
        BadRequestException far = assertThrows(BadRequestException.class,
                () -> offices.pickOsm("osm-n109", "wonka", blr, null, "admin"));
        assertTrue(far.getMessage().startsWith("Pin is far from Bengaluru"), far.getMessage());
        String pune = GeoTestJobs.placeId(gazetteer, "Pune");
        assertThrows(BadRequestException.class, () -> offices.pickOsm("osm-n101", "acme", pune, null, "admin"), "another city");
        assertThrows(BadRequestException.class, () -> offices.pickOsm("bogus", "acme", blr, null, "admin"));
        assertThrows(ResourceNotFoundException.class, () -> offices.pickOsm("osm-n999", "acme", blr, null, "admin"));
        assertThrows(BadRequestException.class, () -> offices.pickOsm("osm-n101", " ", blr, null, "admin"));
        assertTrue(mongo.findAll(CompanyOfficeDocument.class).isEmpty());
        assertTrue(mongo.findAll(OsmOfficeDecisionDocument.class).isEmpty(), "no decision without an office");
    }

    @Test
    void rejectIsAuditedHidesTheMatchAndIsOnePerPairAndObject() {
        assertEquals(2, rows().get("globex").osmCandidates().size());

        CompanyOfficeService.Decision decision = offices.rejectOsm("osm-n102", "globex", blr, "admin@example.com");

        assertEquals("REJECT", decision.decision());
        assertEquals(List.of("osm-n103"), rows().get("globex").osmCandidates().stream()
                .map(CompanyOfficeService.OsmCandidate::osmRef).toList());
        List<CompanyOfficeAuditDocument> audit = mongo.findAll(CompanyOfficeAuditDocument.class);
        assertEquals(1, audit.size());
        assertEquals(CompanyOfficeAuditDocument.Action.OSM_REJECT, audit.get(0).getAction());
        assertEquals("osm-n102", audit.get(0).getOfficeId());
        assertEquals("globex", audit.get(0).getAfter().get("companyKey"));
        offices.rejectOsm("osm-n102", "globex", blr, "other@example.com");
        assertEquals(1, mongo.findAll(OsmOfficeDecisionDocument.class).size(), "a repeated decision replaces the old one");
        assertThrows(DuplicateKeyException.class, () -> {
            OsmOfficeDecisionDocument duplicate = new OsmOfficeDecisionDocument();
            duplicate.setCompanyKey("globex");
            duplicate.setPlaceId(blr);
            duplicate.setOsmRef("osm-n102");
            duplicate.setDecision(OsmOfficeDecisionDocument.Decision.PICK);
            mongo.insert(duplicate);
        }, "unique per (company key, place, OSM object)");
    }

    private Map<String, CompanyOfficeService.Candidate> rows() {
        return offices.candidates(50).stream().collect(Collectors.toMap(CompanyOfficeService.Candidate::companyKey, row -> row));
    }

    private void insert(Document job) { mongo.getCollection("jobs").insertOne(job); }
}
