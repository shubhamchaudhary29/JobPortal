package com.example.backend.geo.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.backend.geo.domain.Place;
import com.example.backend.shared.error.BadRequestException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NearbyTest {
    private static final Gazetteer GAZETTEER = new Gazetteer();
    private MapSnapshotService snapshots;
    private MapJobQueries queries;
    private MapJobService service;

    @BeforeEach
    void setUp() {
        snapshots = mock(MapSnapshotService.class);
        queries = mock(MapJobQueries.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MapJobCache cache = new MapJobCache(Clock.systemUTC(), registry, 120_000, 2_000);
        service = new MapJobService(snapshots, queries, cache, GAZETTEER, registry);
        when(queries.atPlaces(any(), any(), anyInt(), anyInt())).thenReturn(List.of());
    }

    @Test
    void coordinatesAreRoundedToTheGridBeforeUse() {
        assertEquals(12.95, MapJobService.snap(12.9716));
        assertEquals(77.6, MapJobService.snap(77.5946));
        assertEquals(-0.05, MapJobService.snap(-0.03));
        stub(places("Bengaluru"));

        MapJobService.NearbyResult first = service.nearby(12.9716, 77.5946, 25, null, 0, 20);
        MapJobService.NearbyResult second = service.nearby(12.9640, 77.6080, 25, null, 0, 20);

        assertEquals(12.95, first.centerLat());
        assertEquals(77.6, first.centerLng());
        assertEquals(first.centerLat(), second.centerLat());
        assertEquals(first.centerLng(), second.centerLng());
        // Both raw points share one rounded center and so one cache key: Mongo is queried once.
        verify(queries, times(1)).atPlaces(any(), any(), anyInt(), anyInt());
    }

    @Test
    void delhiRadiusIncludesNcrCitiesSortedByDistanceAndExcludesJaipur() {
        stub(places("Delhi", "Gurugram", "Noida", "Ghaziabad", "Jaipur"));
        Place delhi = place("Delhi");

        MapJobService.NearbyResult result = service.nearby(delhi.lat(), delhi.lng(), 40, null, 0, 20);

        // GeoNames names carry diacritics ("Ghāziābād"); compare normalized keys.
        List<String> names = result.places().stream().map(place -> Gazetteer.key(place.name())).toList();
        assertEquals("delhi", names.get(0));
        assertTrue(names.containsAll(List.of("gurugram", "noida", "ghaziabad")), names.toString());
        assertFalse(names.contains("jaipur"));
        List<Double> distances = result.places().stream().map(MapJobService.NearbyPlace::distanceKm).toList();
        assertEquals(distances.stream().sorted().toList(), distances);
        assertTrue(distances.stream().allMatch(km -> km <= 40));
    }

    @Test
    void queriesAtMostTwoHundredPlacesAndReturnsTheNearestFifty() {
        List<MapSnapshotService.MapPlace> dense = new ArrayList<>();
        for (int index = 0; index < 300; index++) {
            dense.add(new MapSnapshotService.MapPlace(String.format("%04d", index), "P" + index, null,
                    20.0 + index * 0.0001, 78.0, 1));
        }
        stub(dense);

        MapJobService.NearbyResult result = service.nearby(20.0, 78.0, 10, null, 0, 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(queries).atPlaces(ids.capture(), isNull(), eq(0), eq(20));
        assertEquals(200, ids.getValue().size());
        assertEquals(50, result.places().size());
        assertEquals("0000", result.places().get(0).id());
    }

    @Test
    void invalidCoordinatesAndRadiusAreBadRequests() {
        stub(places("Delhi"));
        assertThrows(BadRequestException.class, () -> service.nearby(91.0, 77.0, 25, null, 0, 20));
        assertThrows(BadRequestException.class, () -> service.nearby(28.0, 181.0, 25, null, 0, 20));
        assertThrows(BadRequestException.class, () -> service.nearby(28.0, 77.0, 0, null, 0, 20));
        assertThrows(BadRequestException.class, () -> service.nearby(28.0, 77.0, 101, null, 0, 20));
        assertThrows(BadRequestException.class, () -> service.nearby(Double.NaN, 77.0, 25, null, 0, 20));
        assertThrows(BadRequestException.class, () -> service.nearby(28.0, 77.0, 25, null, 50, 20));
        assertThrows(BadRequestException.class, () -> service.nearby(28.0, 77.0, 25, null, 0, 21));
        assertThrows(BadRequestException.class, () -> service.nearby(28.0, 77.0, 25, "x".repeat(101), 0, 20));
        verifyNoInteractions(queries);
    }

    @Test
    void noPlacesInRadiusIsAnEmptyResultWithoutQueryingMongo() {
        stub(places("Jaipur"));
        Place delhi = place("Delhi");

        MapJobService.NearbyResult result = service.nearby(delhi.lat(), delhi.lng(), 25, null, 0, 20);

        assertTrue(result.places().isEmpty());
        assertTrue(result.jobs().content().isEmpty());
        assertFalse(result.jobs().hasNext());
        verifyNoInteractions(queries);
    }

    @Test
    void jobsCarryTheirInRadiusPlacesWithDistances() {
        Place delhi = place("Delhi");
        Place noida = place("Noida");
        stub(places("Delhi", "Noida"));
        when(queries.atPlaces(any(), any(), anyInt(), anyInt())).thenReturn(List.of(new Document("_id", "job-1")
                .append("title", "Engineer").append("source", "adzuna")
                .append("geo", new Document("placeIds", List.of(noida.id(), delhi.id(), "elsewhere")))));

        MapJobService.NearbyResult result = service.nearby(delhi.lat(), delhi.lng(), 40, null, 0, 20);

        List<MapJobService.MapJobPlace> jobPlaces = result.jobs().content().get(0).places();
        assertEquals(List.of(delhi.id(), noida.id()), jobPlaces.stream().map(MapJobService.MapJobPlace::id).toList());
        assertTrue(jobPlaces.get(0).distanceKm() < jobPlaces.get(1).distanceKm());
    }

    private void stub(List<MapSnapshotService.MapPlace> places) {
        MapSnapshotService.MapSnapshot snapshot = new MapSnapshotService.MapSnapshot("v1", Instant.EPOCH, places.size(),
                Map.of(), places);
        when(snapshots.current()).thenReturn(Optional.of(new MapSnapshotService.Published(snapshot, new byte[0],
                new byte[0], "\"v1\"")));
    }

    private static List<MapSnapshotService.MapPlace> places(String... names) {
        List<MapSnapshotService.MapPlace> places = new ArrayList<>();
        for (String name : names) {
            Place place = place(name);
            places.add(new MapSnapshotService.MapPlace(place.id(), place.name(), null, place.lat(), place.lng(), 5));
        }
        places.sort(java.util.Comparator.comparing(MapSnapshotService.MapPlace::id));
        return places;
    }

    private static Place place(String name) {
        return GAZETTEER.primaryMatches(Gazetteer.key(name)).stream()
                .max(java.util.Comparator.comparingLong(Place::population)).orElseThrow();
    }
}
