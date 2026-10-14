package com.example.backend.geo.application;

import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.geo.domain.Place;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

@SpringBootTest
class MapSnapshotMongoIntegrationTest {
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
    }

    @Test
    void countsEachVisibleJobOncePerPlaceFromActiveListings() {
        insert(job("A", true, listing(true, "PLACE", blr), listing(false, "PLACE", mum)));
        insert(job("B", true, listing(true, "PLACE", blr, pune), listing(true, "PLACE", blr)));
        insert(job("C", true).append("geo", geo("PLACE", del)));
        insert(job("D", false, listing(true, "PLACE", blr)));
        insert(job("E", true, listing(true, "PLACE", blr)).append("reconciliationTargetId", "A"));
        insert(job("F", true, listing(true, "REMOTE")));
        insert(job("G", true, listing(true, "COUNTRY")));
        insert(job("H", true, listing(true, "PLACE", "999999999")));
        insert(job("I", true));

        snapshots.refresh();

        MapSnapshotService.MapSnapshot snapshot = snapshots.current().orElseThrow().snapshot();
        Map<String, Long> jobsByPlace = new java.util.HashMap<>();
        snapshot.places().forEach(place -> jobsByPlace.put(place.id(), place.jobs()));
        assertAll(
                () -> assertEquals(2L, jobsByPlace.get(blr)),
                () -> assertEquals(1L, jobsByPlace.get(pune)),
                () -> assertEquals(1L, jobsByPlace.get(del)),
                () -> assertFalse(jobsByPlace.containsKey(mum), "inactive listing must not place a job"),
                () -> assertFalse(jobsByPlace.containsKey("999999999"), "unknown place ids are dropped"),
                () -> assertEquals(3, snapshot.places().size()),
                () -> assertEquals(3, snapshot.placedJobs()),
                () -> assertEquals(1L, snapshot.unplaced().get("REMOTE")),
                () -> assertEquals(1L, snapshot.unplaced().get("COUNTRY")),
                () -> assertEquals(1L, snapshot.unplaced().get("UNPROCESSED")),
                () -> assertEquals(0L, snapshot.unplaced().get("PLACE")),
                () -> assertEquals(List.of("PLACE", "REMOTE", "STATE", "COUNTRY", "FOREIGN", "UNRESOLVED", "EMPTY",
                        "UNPROCESSED"), List.copyOf(snapshot.unplaced().keySet())),
                () -> assertEquals(snapshot.places().stream().map(MapSnapshotService.MapPlace::id).sorted().toList(),
                        snapshot.places().stream().map(MapSnapshotService.MapPlace::id).toList()),
                () -> assertEquals(16, snapshot.version().length()));

        MapSnapshotService.MapPlace bengaluru = snapshot.places().stream().filter(p -> p.id().equals(blr)).findFirst().orElseThrow();
        Place expected = gazetteer.byId(blr).orElseThrow();
        assertEquals(expected.name(), bengaluru.name());
        assertEquals("Karnataka", bengaluru.state());
        assertEquals(expected.lat(), bengaluru.lat());
        assertEquals(expected.lng(), bengaluru.lng());
    }

    @Test
    void unchangedDataKeepsVersionAndPublishedInstance() {
        insert(job("A", true, listing(true, "PLACE", blr)));
        snapshots.refresh();
        MapSnapshotService.Published first = snapshots.current().orElseThrow();

        snapshots.refresh();
        assertSame(first, snapshots.current().orElseThrow());

        insert(job("B", true, listing(true, "PLACE", pune)));
        snapshots.refresh();
        assertNotEquals(first.snapshot().version(), snapshots.current().orElseThrow().snapshot().version());
    }

    private String id(String name) {
        List<Place> matches = gazetteer.primaryMatches(Gazetteer.key(name));
        assertEquals(1, matches.size(), name);
        return matches.get(0).id();
    }

    private void insert(Document job) { mongo.getCollection("jobs").insertOne(job); }

    private static Document job(String id, boolean active, Document... listings) {
        Document job = new Document("_id", id).append("title", "Engineer").append("active", active);
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
