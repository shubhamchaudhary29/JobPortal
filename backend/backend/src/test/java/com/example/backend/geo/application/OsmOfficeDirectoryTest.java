package com.example.backend.geo.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.backend.shared.text.CompanyNames;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OsmOfficeDirectoryTest {
    private static final Set<String> BENGALURU = Set.of("bengaluru", "bangalore");
    private static final String BLR = "1277333";

    @Test
    void namesBrandsAndOperatorsMatchByExactKeyAfterStrippingGenericWords() {
        OsmOfficeDirectory directory = directory(
                office("osm-n1", "Infosys Campus", "", "", ""),
                office("osm-n2", "Google Office Bengaluru", "", "", ""),
                office("osm-n3", "Amazon Development Centre", "", "", ""),
                office("osm-n4", "", "", "Wipro", ""),
                office("osm-n5", "Building 7", "", "", "Tata Consultancy Services Limited"),
                office("osm-n6", "Microsoft India R&D Tower B", "", "", ""));
        assertEquals(List.of("osm-n1"), refs(directory.candidates("infosys", BLR)));
        assertEquals(List.of("osm-n2"), refs(directory.candidates("google", BLR)));
        assertEquals(List.of("osm-n3"), refs(directory.candidates("amazon", BLR)));
        assertEquals(List.of("osm-n4"), refs(directory.candidates("wipro", BLR)), "brand");
        assertEquals(List.of("osm-n5"), refs(directory.candidates(CompanyNames.key("Tata Consultancy Services"), BLR)), "operator");
        assertEquals(List.of("osm-n6"), refs(directory.candidates("microsoft", BLR)));
        assertTrue(directory.candidates("infosys", "1259229").isEmpty(), "another city");
    }

    @Test
    void neverMatchesByPrefixOrSimilarity() {
        OsmOfficeDirectory directory = directory(
                office("osm-n1", "Info Edge", "", "", ""),
                office("osm-n2", "Infosys BPM", "", "", ""),
                office("osm-n3", "Infosyss", "", "", ""));
        assertTrue(directory.candidates("infosys", BLR).isEmpty());
        assertEquals(List.of("osm-n1"), refs(directory.candidates("info edge", BLR)));
        assertTrue(directory.candidates("info", BLR).isEmpty());
    }

    @Test
    void genericShortAndCityNamesNeverMatch() {
        OsmOfficeDirectory directory = directory(
                office("osm-n1", "IT Park", "", "", ""),
                office("osm-n2", "Tech Park Office", "", "", ""),
                office("osm-n3", "HQ", "", "", ""),
                office("osm-n4", "Bengaluru Office", "", "", ""),
                office("osm-n5", "Business Centre", "", "", ""));
        for (String key : List.of("it park", "tech park", "office", "hq", "bengaluru", "business centre", "")) {
            assertTrue(directory.candidates(key, BLR).isEmpty(), key);
        }
        assertEquals("", OsmOfficeDirectory.strip("", BENGALURU));
    }

    @Test
    void rowsWithin150MetresAreOneOfficeKeepingTheLowerId() {
        OsmOfficeDirectory directory = directory(
                office("osm-w20", "Acme", "", "", "", 12.9500, 77.7000),
                office("osm-n30", "Acme Campus", "", "", "", 12.9505, 77.7005),
                office("osm-n40", "Acme", "", "", "", 12.9600, 77.7000));
        assertEquals(List.of("osm-w20", "osm-n40"), refs(directory.candidates("acme", BLR)),
                "osm-n30 is about 75 m from osm-w20; osm-n40 is 1.1 km away");
    }

    @Test
    void linksToTheObjectOnOpenStreetMap() {
        assertEquals("https://www.openstreetmap.org/way/456", office("osm-w456", "A", "", "", "").url());
        assertEquals("https://www.openstreetmap.org/relation/7", office("osm-r7", "A", "", "", "").url());
        assertEquals("Wipro", office("osm-n1", "", "", "Wipro", "").displayName());
    }

    private static OsmOfficeDirectory directory(OsmOfficeDirectory.OsmOffice... offices) {
        return new OsmOfficeDirectory(List.of(offices), placeId -> BLR.equals(placeId) ? BENGALURU : Set.of());
    }

    private static OsmOfficeDirectory.OsmOffice office(String ref, String name, String nameEn, String brand, String operator) {
        return office(ref, name, nameEn, brand, operator, 12.9 + Long.parseLong(ref.substring(5)) * 0.01, 77.6);
    }

    private static OsmOfficeDirectory.OsmOffice office(String ref, String name, String nameEn, String brand, String operator,
                                                       double lat, double lng) {
        return new OsmOfficeDirectory.OsmOffice(ref, name, nameEn, brand, operator, "office=company", BLR, lat, lng);
    }

    private static List<String> refs(List<OsmOfficeDirectory.OsmOffice> offices) {
        return offices.stream().map(OsmOfficeDirectory.OsmOffice::osmRef).toList();
    }
}
