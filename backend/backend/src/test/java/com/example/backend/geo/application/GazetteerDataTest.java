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

    @Test
    void osmLocalitiesBelongToKnownCitiesAndCoverTheTechHubs() {
        assertTrue(GAZETTEER.localities().size() > 1000, "only " + GAZETTEER.localities().size() + " localities");
        List<String> problems = new ArrayList<>();
        for (String[] row : Gazetteer.readOsm(Gazetteer.LOCALITIES).rows()) {
            if (!row[0].matches("osm-[nwr]\\d{1,15}")) problems.add("bad id " + row[0]);
            Place city = GAZETTEER.byId(row[4]).orElse(null);
            if (city == null) { problems.add(row[0] + " has unknown place " + row[4]); continue; }
            double km = MapJobService.haversineKm(city.lat(), city.lng(), Double.parseDouble(row[5]), Double.parseDouble(row[6]));
            if (km > 40.5) problems.add(row[0] + " is " + Math.round(km) + " km from " + city.name());
        }
        assertTrue(problems.isEmpty(), "locality rows: " + problems.subList(0, Math.min(20, problems.size())));
        String bengaluru = GeoTestJobs.placeId(GAZETTEER, "Bengaluru");
        for (String hub : List.of("whitefield", "koramangala")) {
            assertFalse(GAZETTEER.localityMatches(hub, bengaluru).isEmpty(), hub + " is missing from Bengaluru");
        }
    }

    @Test
    void everyLocalityAliasResolvesToALocality() throws IOException {
        assertEquals(List.of(), GAZETTEER.danglingLocalityAliases(), "dangling locality aliases");
        List<String[]> aliases = rows(Gazetteer.LOCALITY_ALIASES);
        assertTrue(aliases.size() >= 40, "only " + aliases.size() + " locality aliases");
        for (String[] row : aliases) {
            assertTrue(GAZETTEER.localityAlias(Gazetteer.key(row[0])).isPresent(), row[0]);
            assertTrue(GAZETTEER.primaryMatches(Gazetteer.key(row[0])).stream().noneMatch(place -> place.population() >= 100_000),
                    row[0] + " is also a city name; a bare alias would shadow it");
        }
    }

    @Test
    void osmOfficesBelongToKnownCities() {
        OsmOfficeDirectory offices = new OsmOfficeDirectory(GAZETTEER);
        assertTrue(offices.size() > 1000, "only " + offices.size() + " offices");
        assertEquals(Gazetteer.readOsm(OsmOfficeDirectory.OFFICES).rows().size(), offices.size(),
                "every office row must have a gazetteer city");
        assertTrue(offices.offices().stream().allMatch(office -> office.osmRef().matches("osm-[nwr]\\d{1,15}")));
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
