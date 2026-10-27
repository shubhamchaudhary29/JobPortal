package com.example.backend.geo.application;

import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.job.infrastructure.JobDocument;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * A curated tech park (GEO-10D) end to end: the persistence callback resolves "Magarpatta, Pune" to it, the snapshot
 * counts it as a locality, and {@code /localities/cur-…/jobs} lists exactly the jobs it counts. Cache TTL 0.
 */
@SpringBootTest(properties = {"geo.snapshot.enabled=false", "geo.map-jobs.cache-ttl-ms=0"})
class CuratedLocalityMongoIntegrationTest {
    @Autowired MapSnapshotService snapshots;
    @Autowired MapJobService jobs;
    @Autowired Gazetteer gazetteer;
    @Autowired MongoTemplate mongo;

    @BeforeEach
    void setUp() {
        mongo.getCollection("jobs").deleteMany(new Document());
        save("m1", "Infosys", "Magarpatta, Pune");
        save("m2", "Wipro", "Pune, Magarpatta City");
        save("m3", "Wipro", "Magarpatta");
        save("p1", "Wipro", "Pune");
        save("t1", "UST", "Technopark, Thiruvananthapuram");
        snapshots.refresh();
    }

    @Test
    void curatedTechParkIsALocalityOnTheMapAndInItsList() {
        LocalityIndex.Locality magarpatta = gazetteer.localityById("cur-magarpatta").orElseThrow();
        JobDocument stored = mongo.findById("m1", JobDocument.class);
        assertEquals(List.of(magarpatta.id()), stored.getGeo().localityIds());

        MapSnapshotService.MapSnapshot snapshot = snapshots.current().orElseThrow().snapshot();
        MapSnapshotService.MapLocality counted = snapshot.localities().stream()
                .filter(locality -> locality.id().equals(magarpatta.id())).findFirst().orElseThrow();
        assertEquals(new MapSnapshotService.MapLocality(magarpatta.id(), "Magarpatta", magarpatta.placeId(), magarpatta.lat(),
                magarpatta.lng(), 3), counted);
        MapSnapshotService.MapPlace pune = snapshot.places().stream().filter(place -> place.id().equals(magarpatta.placeId()))
                .findFirst().orElseThrow();
        assertEquals(4, pune.jobs());
        assertEquals(3, pune.localityJobs());

        MapJobService.MapJobSlice listed = jobs.locality(magarpatta.id(), null, 0, 20);
        assertEquals(Set.of("m1", "m2", "m3"), listed.content().stream().map(MapJobService.MapJob::id).collect(Collectors.toSet()));
        assertEquals(listed.totalHint(), (long) listed.content().size(), "the list holds exactly the counted jobs");
        assertEquals("Magarpatta", listed.content().get(0).places().get(0).localityName());
        assertEquals(Set.of("p1"), jobs.place(magarpatta.placeId(), "unlisted", null, 0, 20).content().stream()
                .map(MapJobService.MapJob::id).collect(Collectors.toSet()));
        assertEquals(1, jobs.locality("cur-technopark", null, 0, 20).content().size());
    }

    private void save(String id, String company, String location) {
        JobDocument job = new JobDocument();
        job.setId(id);
        job.setTitle("Engineer " + id);
        job.setDescription("Build things.");
        job.setCompany(company);
        job.setLocation(location);
        job.setActive(true);
        mongo.save(job);
    }
}
