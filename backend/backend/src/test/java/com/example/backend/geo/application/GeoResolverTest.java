package com.example.backend.geo.application;

import com.example.backend.geo.domain.Place;
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
    }

    @ParameterizedTest
    @ValueSource(strings = {"Remote", "Remote - India", "Work from home", "Remote - United States"})
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
