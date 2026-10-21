package com.example.backend.geo.application;

import com.example.backend.geo.domain.Place;
import com.example.backend.job.domain.GeoPoint;
import com.example.backend.job.domain.GeoResolution;
import com.example.backend.job.domain.GeoStatus;
import com.example.backend.job.domain.LocationHint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GeoResolverTest {
    private static final Gazetteer GAZETTEER = new Gazetteer();
    private static final GeoResolver RESOLVER = new GeoResolver(GAZETTEER);
    private static final double[] BENGALURU = {12.97, 77.59};
    private static final double[] MUMBAI = {19.08, 72.88};
    private static final double[] PUNE = {18.52, 73.86};
    private static final double[] HYDERABAD = {17.39, 78.49};
    private static final double[] GURUGRAM = {28.46, 77.03};
    private static final double[] DELHI = {28.61, 77.21};

    @Test
    void hierarchicalCommaFormIsOnePlace() {
        GeoResolution result = resolve("Bengaluru, Karnataka, India");
        assertEquals(GeoStatus.PLACE, result.status());
        assertEquals(1, result.placeIds().size());
        assertNear(result.placeIds().get(0), BENGALURU, 25);
        assertFalse(result.remote());
        assertEquals(RESOLVER.version(), result.version());
    }

    @Test
    void aliasesResolveToTheSamePlaceAsTheCurrentName() {
        assertEquals(resolve("Bengaluru, Karnataka, India").placeIds(), resolve("Bangalore").placeIds());
        GeoResolution gurgaon = resolve("Gurgaon");
        assertEquals(gurgaon.placeIds(), resolve("Gurugram, Haryana").placeIds());
        assertNear(single(gurgaon), GURUGRAM, 25);
    }

    @Test
    void separatorsSplitCandidates() {
        GeoResolution result = resolve("Bengaluru / Mumbai");
        assertEquals(GeoStatus.PLACE, result.status());
        assertEquals(2, result.placeIds().size());
        assertNear(result.placeIds().get(0), BENGALURU, 25);
        assertNear(result.placeIds().get(1), MUMBAI, 25);
    }

    @Test
    void placeWithRemoteCandidateKeepsPlaceAndFlagsRemote() {
        GeoResolution result = resolve("Bengaluru; Remote");
        assertEquals(GeoStatus.PLACE, result.status());
        assertNear(single(result), BENGALURU, 25);
        assertTrue(result.remote());
    }

    @Test
    void commaListOfMajorCitiesIsSeveralPlaces() {
        GeoResolution result = resolve("Bengaluru, Pune, Hyderabad");
        assertEquals(3, result.placeIds().size());
        assertNear(result.placeIds().get(0), BENGALURU, 25);
        assertNear(result.placeIds().get(1), PUNE, 25);
        assertNear(result.placeIds().get(2), HYDERABAD, 25);
    }

    @Test
    void stateNamesAndAbbreviationsQualifyPlaces() {
        assertNear(single(resolve("Hyderabad, Telangana")), HYDERABAD, 25);
        assertNear(single(resolve("Hyderabad, TS")), HYDERABAD, 25);
        assertNear(single(resolve("Chennai, TN")), new double[] {13.08, 80.27}, 25);
        assertNear(single(resolve("Noida, Uttar Pradesh 201301")), new double[] {28.54, 77.39}, 25);
    }

    @Test
    void foreignOrUnknownQualifiersNeverProduceAnIndianPlace() {
        assertTrue(resolve("Hyderabad, Sindh").placeIds().isEmpty());
        assertTrue(resolve("Delhi, NY").placeIds().isEmpty());
        GeoResolution foreign = resolve("San Francisco, CA, United States");
        assertEquals(GeoStatus.FOREIGN, foreign.status());
        assertTrue(foreign.placeIds().isEmpty());
        assertFalse(foreign.remote());
    }

    // GEO-6A: "Remote - United States" was REMOTE before parser version 2.
    @ParameterizedTest
    @ValueSource(strings = {"Remote - United States", "Remote - UK", "Remote, USA", "United States (Remote)",
            "Remote - San Francisco, CA, United States", "Remote - Germany / Remote - UK", "United States, Remote"})
    void foreignOnlyRemoteIsForeign(String input) {
        GeoResolution result = resolve(input);
        assertEquals(GeoStatus.FOREIGN, result.status());
        assertTrue(result.remote());
        assertTrue(result.placeIds().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Remote - United States; Remote - India", "Remote - UK / Remote", "Remote - US; Anywhere",
            "Work from home (Remote)"})
    void anyOpenOrIndiaRemoteMarkerKeepsRemote(String input) {
        GeoResolution result = resolve(input);
        assertEquals(GeoStatus.REMOTE, result.status());
        assertTrue(result.remote());
    }

    @Test
    void placeWithForeignRemoteStaysPlace() {
        GeoResolution result = resolve("Bengaluru; Remote - United States");
        assertEquals(GeoStatus.PLACE, result.status());
        assertNear(single(result), BENGALURU, 25);
        assertTrue(result.remote());
    }

    @Test
    void parserVersionIsPartOfTheResolutionVersion() {
        assertTrue(RESOLVER.version().startsWith(GeoResolver.PARSER_VERSION + "-"));
        assertEquals(3, GeoResolver.PARSER_VERSION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Remote", "Remote - India", "Work from home"})
    void remoteMarkersAreRemoteWithoutPlace(String input) {
        GeoResolution result = resolve(input);
        assertEquals(GeoStatus.REMOTE, result.status());
        assertTrue(result.remote());
        assertTrue(result.placeIds().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"India", "Pan India", "Anywhere in India"})
    void countryOnlyIsCountry(String input) {
        GeoResolution result = resolve(input);
        assertEquals(GeoStatus.COUNTRY, result.status());
        assertTrue(result.placeIds().isEmpty());
    }

    @Test
    void statesAloneAreStatesAndAreNotSplitOnAnd() {
        assertEquals(GeoStatus.STATE, resolve("Karnataka").status());
        assertEquals(GeoStatus.STATE, resolve("Jammu and Kashmir").status());
        assertEquals(GeoStatus.STATE, resolve("Jammu & Kashmir").status());
    }

    @Test
    void cityStatesResolveToTheirCity() {
        assertNear(single(resolve("Delhi")), DELHI, 25);
        assertNear(single(resolve("New Delhi, Delhi, India")), DELHI, 25);
        assertNear(single(resolve("Delhi NCR")), DELHI, 25);
    }

    @Test
    void localityFallsBackToItsCity() {
        assertNear(single(resolve("Andheri East, Mumbai")), MUMBAI, 30);
    }

    @Test
    void workModeDecorationsAreIgnored() {
        assertNear(single(resolve("Bengaluru (Hybrid)")), BENGALURU, 25);
        GeoResolution pune = resolve("Hybrid - Pune");
        assertEquals(GeoStatus.PLACE, pune.status());
        assertNear(single(pune), PUNE, 25);
        assertFalse(pune.remote());
        assertTrue(resolve("Pune (Remote)").remote());
    }

    @Test
    void unknownTextIsUnresolved() {
        assertEquals(GeoStatus.UNRESOLVED, resolve("Multiple Locations").status());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void blankIsEmpty(String input) {
        GeoResolution result = resolve(input);
        assertEquals(GeoStatus.EMPTY, result.status());
        assertTrue(result.placeIds().isEmpty());
    }

    @Test
    void hostileAndOversizedInputIsBoundedAndNeverThrows() {
        for (String input : List.of("$where", "{\"a\":1}", "Bengaluru / ".repeat(1_000), "x".repeat(10_000))) {
            long started = System.nanoTime();
            GeoResolution result = assertDoesNotThrow(() -> RESOLVER.resolve(input, null));
            assertTrue((System.nanoTime() - started) / 1_000_000 < 50, "resolution took too long");
            assertTrue(result.placeIds().size() <= GeoResolver.MAX_PLACES);
        }
        assertTrue(List.of(GeoStatus.UNRESOLVED, GeoStatus.EMPTY).contains(resolve("$where").status()));
        assertTrue(List.of(GeoStatus.UNRESOLVED, GeoStatus.EMPTY).contains(resolve("{\"a\":1}").status()));
        assertEquals(GeoStatus.UNRESOLVED, resolve("x".repeat(10_000)).status());
    }

    @Test
    void adzunaAreaHintResolvesAndKeepsIndianCoordinates() {
        GeoResolution result = RESOLVER.resolve("Bangalore, Karnataka",
                new LocationHint(List.of("India", "Karnataka", "Bangalore"), 12.97, 77.59));
        assertEquals(GeoStatus.PLACE, result.status());
        assertNear(single(result), BENGALURU, 25);
        assertNotNull(result.point());
        assertEquals(12.97, result.point().lat());
    }

    @Test
    void countryOnlyHintIsCountry() {
        assertEquals(GeoStatus.COUNTRY, RESOLVER.resolve("India", new LocationHint(List.of("India"), null, null)).status());
    }

    @Test
    void coordinatesOutsideIndiaAreDropped() {
        GeoResolution result = RESOLVER.resolve("London", new LocationHint(List.of(), 51.5, -0.12));
        assertNull(result.point());
    }

    // GEO-8B localities ---------------------------------------------------------------------------------------------

    @Test
    void localityInsideTheResolvedCity() {
        GeoResolution result = resolve("Whitefield, Bengaluru, Karnataka, India");
        assertNear(single(result), BENGALURU, 25);
        assertEquals(List.of("Whitefield"), localityNames(result));
        assertEquals(List.of("Whitefield"), localityNames(resolve("Bengaluru, Whitefield")), "a locality may follow the city");
        assertEquals(List.of("Koramangala 5th Block"), localityNames(resolve("Koramangala 5th Block, Bangalore")),
                "an exact block wins");
        assertEquals(List.of("Koramangala"), localityNames(resolve("Koramangala 12th Block, Bangalore")),
                "an unknown block falls back to the locality");
        assertEquals(List.of("Electronics City Phase 1"), localityNames(resolve("Electronic City, Bengaluru")),
                "curated spelling inside the city");
    }

    @Test
    void localityMustBelongToTheResolvedCity() {
        GeoResolution pune = resolve("Whitefield, Pune");
        assertNear(single(pune), PUNE, 25);
        assertTrue(pune.localityIds().isEmpty(), "Whitefield is a Bengaluru locality");
        GeoResolution foreign = resolve("Delhi, NY");
        assertNotEquals(GeoStatus.PLACE, foreign.status());
        assertTrue(foreign.localityIds().isEmpty());
        assertTrue(resolve("Bengaluru, Karnataka, India").localityIds().isEmpty());
        assertTrue(resolve("New Delhi").localityIds().isEmpty(), "a city name is never read as a locality");
        GeoResolution kolkata = resolve("Sector V, Salt Lake, Kolkata");
        assertEquals("Kolkata", GAZETTEER.byId(single(kolkata)).orElseThrow().name(), "an unknown middle part is tolerated");
        assertEquals(List.of("Sector V"), localityNames(kolkata));
        assertNotEquals(GeoStatus.PLACE, resolve("Delhi, Springfield").status(), "but not an unknown last part");
    }

    @Test
    void noidaSectorsInEveryCommonShape() {
        for (String input : List.of("Noida Sector 62", "Sector-62, Noida", "Sec 62 Noida", "Sector 62, Noida, Uttar Pradesh",
                "sec. 62, noida")) {
            GeoResolution result = resolve(input);
            assertEquals("Noida", GAZETTEER.byId(single(result)).orElseThrow().name(), input);
            assertEquals(List.of("Sector 62"), localityNames(result), input);
        }
        assertEquals(List.of("DLF Cyber City"), localityNames(resolve("Gurgaon Cyber City")));
        assertNear(single(resolve("Gurgaon Cyber City")), GURUGRAM, 25);
    }

    @Test
    void phasesNormaliseAndFallBack() {
        assertEquals(List.of("Hinjawadi Phase 2"), localityNames(resolve("Hinjewadi Phase 2, Pune")));
        assertEquals(List.of("Hinjawadi Phase 2"), localityNames(resolve("Hinjewadi Ph. II, Pune")));
        assertEquals(List.of("Hinjawadi"), localityNames(resolve("Hinjewadi Phase 7, Pune")), "unknown phase → Hinjawadi");
        assertEquals(List.of("Okhla Phase II"), localityNames(resolve("Okhla Phase 2, New Delhi")));
    }

    @Test
    void bareLocalitiesNeedACuratedAlias() {
        GeoResolution whitefield = resolve("Whitefield");
        assertNear(single(whitefield), BENGALURU, 25);
        assertEquals(List.of("Whitefield"), localityNames(whitefield));
        assertEquals(List.of("HITEC City"), localityNames(resolve("HITEC City")));
        assertNear(single(resolve("HITEC City")), HYDERABAD, 25);
        assertEquals(List.of("Electronics City Phase 1"), localityNames(resolve("Electronic City")));
        assertEquals(List.of("HITEC City"), localityNames(resolve("Hitech City, Telangana, India")));
        assertTrue(resolve("HITEC City, Karnataka").localityIds().isEmpty(), "the state must match the alias's city");
        assertEquals(GeoStatus.UNRESOLVED, resolve("Sector 18").status(), "a bare locality never matches the gazetteer");
        assertTrue(resolve("Sector 18").placeIds().isEmpty());
    }

    @Test
    void smallLeadingPlaceGivesWayToTheNamedCity() {
        GeoResolution powai = resolve("Powai, Mumbai, Maharashtra");
        assertNear(single(powai), MUMBAI, 30);
        assertEquals(List.of("Powai"), localityNames(powai));
    }

    @Test
    void everyCandidateKeepsItsOwnLocalities() {
        GeoResolution pune = resolve("Hinjewadi / Kharadi, Pune");
        assertNear(single(pune), PUNE, 25);
        assertEquals(List.of("Hinjawadi", "Kharadi"), localityNames(pune));
        GeoResolution sibling = resolve("Aundh / Kharadi, Pune");
        assertEquals(List.of("Kharadi", "Aundh"), localityNames(sibling), "a bare candidate is tried in the named city");
        assertTrue(resolve("Aundh").placeIds().isEmpty(), "Aundh alone has no alias");
        GeoResolution two = resolve("Whitefield, Bengaluru; Gachibowli, Hyderabad");
        assertEquals(2, two.placeIds().size());
        assertEquals(List.of("Whitefield", "Gachibowli"), localityNames(two));
        for (String id : two.localityIds()) {
            assertTrue(two.placeIds().contains(GAZETTEER.localityById(id).orElseThrow().placeId()));
        }
    }

    @Test
    void adzunaAreaHintCarriesItsLocality() {
        GeoResolution result = RESOLVER.resolve("Bangalore", new LocationHint(List.of("India", "Karnataka", "Bangalore",
                "Whitefield"), null, null));
        assertNear(single(result), BENGALURU, 25);
        assertEquals(List.of("Whitefield"), localityNames(result));
    }

    @Test
    void adzunaPointsSnapOnlyWhenOffCentreAndNearALocality() {
        Place bengaluru = GAZETTEER.byId(single(resolve("Bengaluru"))).orElseThrow();
        GeoResolution centre = RESOLVER.resolve("Bangalore", new LocationHint(List.of(), bengaluru.lat(), bengaluru.lng()));
        assertTrue(centre.localityIds().isEmpty(), "Adzuna's city centroid is not an office");
        assertEquals(new GeoPoint(bengaluru.lat(), bengaluru.lng()), centre.point());
        LocalityIndex.Locality whitefield = GAZETTEER.localityMatches("whitefield", bengaluru.id()).get(0);
        // About 1 km north of Whitefield and well over 1.5 km from the centre.
        double lat = whitefield.lat() + 0.009;
        assertTrue(GeoResolverTest.haversineKm(lat, whitefield.lng(), bengaluru.lat(), bengaluru.lng()) > 3);
        GeoResolution near = RESOLVER.resolve("Bangalore", new LocationHint(List.of(), lat, whitefield.lng()));
        assertEquals(1, near.localityIds().size());
        LocalityIndex.Locality snapped = GAZETTEER.localityById(near.localityIds().get(0)).orElseThrow();
        assertTrue(GeoResolverTest.haversineKm(lat, whitefield.lng(), snapped.lat(), snapped.lng()) <= GeoResolver.SNAP_MAX_KM);
        // In the sea west of Bengaluru's localities: off-centre, but no locality within 2 km.
        GeoResolution far = RESOLVER.resolve("Bangalore", new LocationHint(List.of(), 12.80, 77.30));
        assertTrue(far.localityIds().isEmpty());
        assertTrue(GAZETTEER.nearestLocality(bengaluru.id(), 12.80, 77.30, 5).isEmpty(), "test point must be 5 km from any locality");
        GeoResolution textWins = RESOLVER.resolve("Koramangala, Bangalore", new LocationHint(List.of(), lat, whitefield.lng()));
        assertEquals(List.of("Koramangala"), localityNames(textWins), "a locality from the text is not overridden by the point");
    }

    @Test
    void localitiesAreCappedAndOnlyFromResolvedPlaces() {
        String many = String.join(" / ", List.of("Whitefield", "Koramangala", "Marathahalli", "Bellandur", "HSR Layout",
                "Electronic City", "Manyata Tech Park", "Gachibowli", "Madhapur", "Kondapur", "Kharadi", "Powai"));
        GeoResolution result = resolve(many);
        assertEquals(GeoResolver.MAX_LOCALITIES, result.localityIds().size());
        for (String id : result.localityIds()) {
            assertTrue(result.placeIds().contains(GAZETTEER.localityById(id).orElseThrow().placeId()));
        }
    }

    private static List<String> localityNames(GeoResolution result) {
        return result.localityIds().stream().map(id -> GAZETTEER.localityById(id).orElseThrow().name()).toList();
    }

    private static GeoResolution resolve(String input) { return RESOLVER.resolve(input, null); }

    private static String single(GeoResolution result) {
        assertEquals(GeoStatus.PLACE, result.status(), "expected a place");
        assertEquals(1, result.placeIds().size(), "expected exactly one place: " + result.placeIds());
        return result.placeIds().get(0);
    }

    static void assertNear(String placeId, double[] expected, double maximumKm) {
        Place place = GAZETTEER.byId(placeId).orElseThrow();
        double km = haversineKm(place.lat(), place.lng(), expected[0], expected[1]);
        assertTrue(km <= maximumKm, place.name() + " is " + Math.round(km) + " km from the expected point");
    }

    static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLng / 2), 2);
        return 6371 * 2 * Math.asin(Math.sqrt(a));
    }
}
