package com.example.backend.geo.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LocalityIndexTest {
    private static final LocalityIndex INDEX = LocalityIndex.fromRows(List.of(
            row("osm-n1", "Whitefield", "", "Vaitpild|White Field", "100", 12.9698, 77.7500, "suburb"),
            row("osm-n2", "Kadugodi", "", "Whitefield", "100", 12.9980, 77.7600, "neighbourhood"),
            row("osm-w3", "Whitefield", "", "", "200", 18.5000, 73.8000, "neighbourhood"),
            row("osm-r4", "ಕೋರಮಂಗಲ", "Koramangala", "", "100", 12.9352, 77.6245, "suburb"),
            row("osm-n5", "Bellandur", "", "", "100", 12.9260, 77.6762, "neighbourhood"),
            row("osm-n5", "Duplicate id", "", "", "100", 12.0, 77.0, "suburb")));

    @Test
    void matchesNameEnglishNameAndAlternatesWithinOnePlace() {
        assertEquals(List.of("osm-n1", "osm-n2"), ids(INDEX.matches("whitefield", "100")),
                "the primary name beats another locality's alternate name");
        assertEquals(List.of("osm-w3"), ids(INDEX.matches("whitefield", "200")));
        assertEquals(List.of("osm-r4"), ids(INDEX.matches("koramangala", "100")));
        assertEquals(List.of("osm-n1"), ids(INDEX.matches("white field", "100")));
        assertTrue(INDEX.matches("whitefield", "300").isEmpty());
        assertTrue(INDEX.matches("", "100").isEmpty());
    }

    @Test
    void looksUpByIdAndPlaceAndIgnoresDuplicateIds() {
        assertEquals(5, INDEX.size());
        assertEquals("Bellandur", INDEX.byId("osm-n5").orElseThrow().name());
        assertEquals(4, INDEX.inPlace("100").size());
        assertTrue(INDEX.inPlace(null).isEmpty());
    }

    @Test
    void nearestStaysInsideThePlaceAndTheRadius() {
        assertEquals("osm-n5", INDEX.nearest("100", 12.9300, 77.6800, 2).orElseThrow().id());
        assertTrue(INDEX.nearest("100", 13.5, 78.5, 2).isEmpty());
        assertTrue(INDEX.nearest("200", 12.9698, 77.7500, 2).isEmpty(), "a Pune locality never matches a Bengaluru point");
    }

    @Test
    void localityKeysUnifyCommonSpellings() {
        assertEquals("sector 62", LocalityIndex.localityKey(Gazetteer.key("Sec-62")));
        assertEquals("sector 62", LocalityIndex.localityKey(Gazetteer.key("sec. 62")));
        assertEquals("sector 62", LocalityIndex.localityKey(Gazetteer.key("Sector62")));
        assertEquals("hinjewadi phase 2", LocalityIndex.localityKey(Gazetteer.key("Hinjewadi Ph. II")));
        assertEquals("sector 5", LocalityIndex.localityKey(Gazetteer.key("Sector V")));
        assertEquals("phase 3 block 1", LocalityIndex.localityKey(Gazetteer.key("Phase III Block I")));
        assertEquals("i park", LocalityIndex.localityKey(Gazetteer.key("I Park")), "numerals only after sector/phase/block/stage");
        assertEquals("", LocalityIndex.localityKey(""));
    }

    private static List<String> ids(List<LocalityIndex.Locality> localities) {
        return localities.stream().map(LocalityIndex.Locality::id).toList();
    }

    private static String[] row(String id, String name, String nameEn, String alternates, String place, double lat,
                                double lng, String kind) {
        return new String[] {id, name, nameEn, alternates, place, String.valueOf(lat), String.valueOf(lng), kind, "place=" + kind};
    }
}
