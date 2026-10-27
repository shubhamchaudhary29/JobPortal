package com.example.backend.geo.application;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DescriptionLocationScannerTest {
    private static final Gazetteer GAZETTEER = new Gazetteer();
    private static final GeoResolver RESOLVER = new GeoResolver(GAZETTEER);
    private static final DescriptionLocationScanner SCANNER = new DescriptionLocationScanner(GAZETTEER, RESOLVER::locality);
    private static final String BENGALURU = GeoTestJobs.placeId(GAZETTEER, "Bengaluru");
    private static final String PUNE = GeoTestJobs.placeId(GAZETTEER, "Pune");

    private static String name(Optional<String> id) {
        return id.map(value -> GAZETTEER.localityById(value).orElseThrow().name()).orElse(null);
    }

    @Test
    void cueLineLocalityWithBlockStripping() {
        assertEquals("Hinjawadi Phase 2", name(SCANNER.scan("Work location: Hinjewadi Phase 2, Pune", List.of(PUNE))));
        assertEquals("Marathahalli", name(SCANNER.scan("We are based in Marathahalli.", List.of(BENGALURU))));
        assertEquals("Whitefield", name(SCANNER.scan("<ul><li>Location</li><li>x</li></ul><p>Venue: Whitefield</p>",
                List.of(BENGALURU))), "a block tag ends a line, but a cue inside the line still counts");
    }

    @Test
    void conflictsOtherCitiesAndMissingCuesGiveNothing() {
        assertTrue(SCANNER.scan("Offices: Whitefield, Koramangala", List.of(BENGALURU)).isEmpty());
        assertTrue(SCANNER.scan("Whitefield is lovely", List.of(BENGALURU)).isEmpty());
        assertTrue(SCANNER.scan("Location: Whitefield", List.of(BENGALURU, PUNE)).isEmpty());
        assertTrue(SCANNER.scan("Location: Whitefield", List.of()).isEmpty());
        assertTrue(SCANNER.scan(null, List.of(BENGALURU)).isEmpty());
        assertTrue(SCANNER.scan("Location: Bengaluru", List.of(BENGALURU)).isEmpty(), "the city itself is no locality");
    }

    @Test
    void aTechParkInsideTheNamedSuburbIsOnePlace() {
        assertEquals("Prestige Tech Park", name(SCANNER.scan("Address: Prestige Tech Park, Marathahalli", List.of(BENGALURU))));
        assertTrue(SCANNER.scan("Address: Prestige Tech Park, Whitefield", List.of(BENGALURU)).isEmpty(),
                "Whitefield is over 2 km from the park");
    }

    @Test
    void pinCodesAnywhereInTheTextCount() {
        assertEquals("Whitefield", name(SCANNER.scan("Reach us at 560066 for interviews.", List.of(BENGALURU))));
        assertTrue(SCANNER.scan("Send mail to 560066 or 560100", List.of(BENGALURU)).isEmpty());
    }

    @Test
    void onlyTheFirst20000CharactersAreRead() {
        String padding = "x ".repeat(DescriptionLocationScanner.MAX_CHARS / 2 + 10);
        assertTrue(SCANNER.scan(padding + "\nLocation: Whitefield", List.of(BENGALURU)).isEmpty());
        assertEquals("Whitefield", name(SCANNER.scan("Location: Whitefield\n" + padding, List.of(BENGALURU))));
    }

    @Test
    void plainTextKeepsLinesAndEntities() {
        assertEquals(" a & b\n", DescriptionLocationScanner.plainText("<p>a &amp; b</p>"));
        assertEquals("x\ny", DescriptionLocationScanner.plainText("x<br/>y"));
    }
}
