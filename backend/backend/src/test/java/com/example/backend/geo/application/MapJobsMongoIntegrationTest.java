package com.example.backend.geo.application;

import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.geo.domain.Place;
import com.example.backend.shared.error.BadRequestException;
import com.example.backend.shared.error.ResourceNotFoundException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Cache TTL 0: every call reaches MongoDB, so these tests exercise the real queries. */
@SpringBootTest(properties = "geo.map-jobs.cache-ttl-ms=0")
class MapJobsMongoIntegrationTest {
    @Autowired MapJobService jobs;
    @Autowired MapSnapshotService snapshots;
    @Autowired Gazetteer gazetteer;
    @Autowired MongoTemplate mongo;
    private String blr;
    private String mum;
    private String pune;
    private String del;

    @BeforeEach
    void setUp() {
        mongo.getCollection("jobs").deleteMany(new Document());
        blr = id("Bengaluru");
        mum = id("Mumbai");
        pune = id("Pune");
        del = id("Delhi");
        insert(job("A", 1, "Java Engineer", true, listing(true, "PLACE", blr), listing(false, "PLACE", mum)));
        insert(job("B", 2, "Python Developer", true, listing(true, "PLACE", blr, pune), listing(true, "PLACE", blr)));
        insert(job("C", 3, "Data Analyst", true).append("geo", geo("PLACE", del)));
        insert(job("D", 4, "Java Engineer", false, listing(true, "PLACE", blr)));
        insert(job("E", 5, "Java Engineer", true, listing(true, "PLACE", blr)).append("reconciliationTargetId", "A"));
        insert(job("F", 6, "Remote Support", true, listing(true, "REMOTE")));
        insert(job("G", 7, "Sales Lead", true, listing(true, "COUNTRY")));
        insert(job("S", 8, "Field Officer", true, listing(true, "STATE")));
        insert(job("J", 9, "Product Manager", true, listing(true, "PLACE", pune)).append("geo", geo("PLACE", mum)));
        snapshots.refresh();
    }

    @Test
    void placeQueriesSelectExactlyThePipelineDefinitionOfAJobsPlaces() {
        Map<String, Definition> definitions = pipelineDefinitions();
        for (String place : List.of(blr, mum, pune, del)) {
            Set<String> expected = new HashSet<>();
            definitions.forEach((id, definition) -> { if (definition.placeIds().contains(place)) expected.add(id); });
            assertEquals(expected, allPlaceJobIds(place), "place " + place);
        }
        assertEquals(Set.of("A", "B"), allPlaceJobIds(blr));
        assertEquals(Set.of(), allPlaceJobIds(mum), "inactive listing and stale top-level geo must not place a job");
        assertEquals(Set.of("B", "J"), allPlaceJobIds(pune));
        assertEquals(Set.of("C"), allPlaceJobIds(del));
    }

    @Test
    void unplacedQueriesMatchThePipelineRank() {
        Map<String, Definition> definitions = pipelineDefinitions();
        Map<String, Set<Integer>> kinds = Map.of("remote", Set.of(2), "india", Set.of(3, 4));
        kinds.forEach((kind, ranks) -> {
            Set<String> expected = new HashSet<>();
            definitions.forEach((id, definition) -> {
                if (definition.placeIds().isEmpty() && ranks.contains(definition.rank())) expected.add(id);
            });
            Set<String> actual = new HashSet<>();
            jobs.unplaced(kind, null, 0, 20).content().forEach(job -> actual.add(job.id()));
            assertEquals(expected, actual, kind);
        });
        assertEquals(Set.of("F"), ids(jobs.unplaced("remote", null, 0, 20)));
        assertEquals(Set.of("G", "S"), ids(jobs.unplaced("india", null, 0, 20)));
        assertEquals(1L, jobs.unplaced("remote", null, 0, 20).totalHint());
        assertEquals(2L, jobs.unplaced("india", null, 0, 20).totalHint());
        assertTrue(jobs.unplaced("remote", null, 0, 20).content().get(0).places().isEmpty());
        assertThrows(BadRequestException.class, () -> jobs.unplaced("foreign", null, 0, 20));
        assertThrows(BadRequestException.class, () -> jobs.unplaced(null, null, 0, 20));
    }

    @Test
    void phraseSearchNarrowsResultsAndWorksWithTheIndexedOr() {
        MapJobService.MapJobSlice python = jobs.place(blr, "python", 0, 20);
        assertEquals(Set.of("B"), ids(python));
        assertNull(python.totalHint());
        assertEquals(Set.of("B"), ids(jobs.place(blr, "  PYTHON developer ", 0, 20)));
        assertEquals(Set.of(), ids(jobs.place(blr, "kotlin", 0, 20)));
        assertEquals(Set.of("F"), ids(jobs.unplaced("remote", "support", 0, 20)));
        assertEquals(Set.of(), ids(jobs.unplaced("remote", "\"java\"", 0, 20)));
    }

    @Test
    void slicePagingIsStableNewestFirst() {
        MapJobService.MapJobSlice first = jobs.place(blr, null, 0, 1);
        MapJobService.MapJobSlice second = jobs.place(blr, null, 1, 1);
        assertEquals(List.of("B"), first.content().stream().map(MapJobService.MapJob::id).toList());
        assertTrue(first.hasNext());
        assertEquals(List.of("A"), second.content().stream().map(MapJobService.MapJob::id).toList());
        assertFalse(second.hasNext());
        assertEquals(2L, first.totalHint());
        MapJobService.MapJob b = first.content().get(0);
        assertEquals("Python Developer", b.title());
        assertEquals("adzuna", b.source());
        assertEquals(List.of(new MapJobService.MapJobPlace(blr, gazetteer.byId(blr).orElseThrow().name(), null)), b.places());
    }

    @Test
    void projectionNeverLoadsDescriptions() {
        List<Document> raw = mongo.getCollection("jobs").find().projection(MapJobQueries.PROJECTION).into(new ArrayList<>());
        assertFalse(raw.isEmpty());
        raw.forEach(document -> {
            assertFalse(document.containsKey("description"));
            assertFalse(document.containsKey("recruiterId"));
            assertFalse(document.containsKey("matchFeatures"));
        });
    }

    @Test
    void unknownPlaceIsNotFoundAndAKnownEmptyPlaceIsEmpty() {
        assertThrows(ResourceNotFoundException.class, () -> jobs.place("999999999", null, 0, 20));
        String chennai = id("Chennai");
        MapJobService.MapJobSlice empty = jobs.place(chennai, null, 0, 20);
        assertTrue(empty.content().isEmpty());
        assertFalse(empty.hasNext());
        assertEquals(0L, empty.totalHint());
    }

    @Test
    void nearbyReturnsPlacesAndRecentJobsWithDistances() {
        Place bengaluru = gazetteer.byId(blr).orElseThrow();
        MapJobService.NearbyResult result = jobs.nearby(bengaluru.lat(), bengaluru.lng(), 25, null, 0, 20);
        assertEquals(List.of(blr), result.places().stream().map(MapJobService.NearbyPlace::id).toList());
        assertEquals(List.of("B", "A"), result.jobs().content().stream().map(MapJobService.MapJob::id).toList());
        assertNotNull(result.jobs().content().get(0).places().get(0).distanceKm());
        assertNull(result.jobs().totalHint());
    }

    private Set<String> allPlaceJobIds(String place) {
        Set<String> found = new HashSet<>();
        for (int page = 0; page <= MapJobService.MAX_PAGE; page++) {
            MapJobService.MapJobSlice slice = jobs.place(place, null, page, 1);
            slice.content().forEach(job -> found.add(job.id()));
            if (!slice.hasNext()) break;
        }
        return found;
    }

    private Map<String, Definition> pipelineDefinitions() {
        Map<String, Definition> definitions = new HashMap<>();
        mongo.getCollection("jobs").aggregate(List.of(
                new Document("$match", GeoPipelines.visibleJobs()),
                new Document("$project", new Document("placeIds", GeoPipelines.placeIds())
                        .append("rank", GeoPipelines.bestRank())))).forEach(document -> definitions.put(
                document.getString("_id"), new Definition(new HashSet<>(document.getList("placeIds", String.class)),
                        document.getInteger("rank"))));
        return definitions;
    }

    private record Definition(Set<String> placeIds, int rank) { }

    private static Set<String> ids(MapJobService.MapJobSlice slice) {
        Set<String> ids = new HashSet<>();
        slice.content().forEach(job -> ids.add(job.id()));
        return ids;
    }

    private String id(String name) {
        List<Place> matches = gazetteer.primaryMatches(Gazetteer.key(name));
        assertEquals(1, matches.size(), name);
        return matches.get(0).id();
    }

    private void insert(Document job) { mongo.getCollection("jobs").insertOne(job); }

    private static Document job(String id, int day, String title, boolean active, Document... listings) {
        Document job = new Document("_id", id).append("title", title).append("description", "secret description " + id)
                .append("company", "Acme").append("location", "Somewhere").append("source", "adzuna")
                .append("recruiterId", null).append("active", active)
                .append("createdAt", Date.from(Instant.parse("2026-10-01T00:00:00Z").plusSeconds(86_400L * day)));
        if (listings.length > 0) job.append("sourceListings", new ArrayList<>(List.of(listings)));
        return job;
    }

    private static Document listing(boolean active, String status, String... placeIds) {
        return new Document("identity", "provider:" + String.join("-", placeIds) + status + active)
                .append("active", active).append("geo", geo(status, placeIds));
    }

    private static Document geo(String status, String... placeIds) {
        return new Document("status", status).append("placeIds", List.of(placeIds)).append("remote", false)
                .append("version", "test");
    }
}
