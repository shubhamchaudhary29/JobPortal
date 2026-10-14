package com.example.backend.geo.application;

import com.example.backend.geo.domain.Place;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GazetteerDataTest {
    private static final Gazetteer GAZETTEER = new Gazetteer();

    @Test
    void containsTheMajorIndianCities() {
        assertTrue(GAZETTEER.placeCount() > 3000, "only " + GAZETTEER.placeCount() + " places");
        for (String city : List.of("Bengaluru", "Mumbai", "Delhi", "Chennai", "Kolkata", "Hyderabad", "Pune")) {
            assertFalse(GAZETTEER.primaryMatches(Gazetteer.key(city)).isEmpty(), city + " is missing");
        }
    }

    @Test
    void everyPlaceAliasResolvesToExactlyOnePlace() throws IOException {
        List<String> problems = new ArrayList<>();
        for (String[] row : rows(Gazetteer.PLACE_ALIASES)) {
            List<Place> matches = GAZETTEER.aliasMatches(Gazetteer.key(row[0]));
            if (matches.size() != 1) problems.add(row[0] + " -> " + row[1] + " matched " + matches.size());
        }
        assertTrue(problems.isEmpty(), "place aliases must each resolve to one place: " + problems);
    }

    @Test
    void everyStateAliasPointsAtARealAdmin1Code() throws IOException {
        List<String> problems = new ArrayList<>();
        for (String[] row : rows(Gazetteer.STATE_ALIASES)) {
            if (!GAZETTEER.admin1Exists(row[1])) problems.add(row[0] + " -> " + row[1]);
        }
        assertTrue(problems.isEmpty(), "unknown admin1 codes: " + problems);
    }

    @Test
    void dataHashAndVersionAreStable() {
        Gazetteer second = new Gazetteer();
        assertEquals(GAZETTEER.dataHash(), second.dataHash());
        assertEquals(12, GAZETTEER.dataHash().length());
        assertTrue(new GeoResolver(GAZETTEER).version().startsWith(GeoResolver.PARSER_VERSION + "-"));
        assertEquals(new GeoResolver(GAZETTEER).version(), new GeoResolver(second).version());
    }

    private static List<String[]> rows(String resource) throws IOException {
        try (InputStream input = GazetteerDataTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            List<String[]> rows = new ArrayList<>();
            for (String line : new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank() && !line.startsWith("#")) rows.add(line.split("\t", -1));
            }
            return rows;
        }
    }
}
