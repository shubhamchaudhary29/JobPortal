package com.example.backend.geo.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.MongoCollection;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

class MapSnapshotServiceTest {
    private static final Gazetteer GAZETTEER = new Gazetteer();
    private MongoTemplate mongo;
    private MongoCollection<Document> collection;
    private AggregateIterable<Document> aggregate;
    private SimpleMeterRegistry registry;
    private MutableClock clock;
    private MapSnapshotService service;
    private String blr;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mongo = mock(MongoTemplate.class);
        collection = mock(MongoCollection.class);
        aggregate = mock(AggregateIterable.class);
        when(mongo.getCollection("jobs")).thenReturn(collection);
        when(collection.aggregate(anyList())).thenReturn(aggregate);
        when(aggregate.maxTime(anyLong(), any(TimeUnit.class))).thenReturn(aggregate);
        registry = new SimpleMeterRegistry();
        clock = new MutableClock(Instant.parse("2026-10-14T00:00:00Z"));
        service = new MapSnapshotService(mongo, GAZETTEER, registry, clock, false, 300_000);
        blr = GAZETTEER.primaryMatches(Gazetteer.key("Bengaluru")).get(0).id();
    }

    @Test
    void readsAfterARefreshNeverTouchMongo() {
        when(aggregate.first()).thenReturn(facets(placed(List.of(blr), 4)));
        service.refresh();
        clearInvocations(mongo, collection, aggregate);

        for (int read = 0; read < 1_000; read++) assertTrue(service.current().isPresent());

        verifyNoMoreInteractions(mongo, collection, aggregate);
        assertEquals(4, service.current().orElseThrow().snapshot().placedJobs());
    }

    @Test
    void identicalOutputKeepsTheSamePublishedInstanceAndTimestamp() {
        when(aggregate.first()).thenReturn(facets(placed(List.of(blr), 4)), facets(placed(List.of(blr), 4)));
        service.refresh();
        MapSnapshotService.Published first = service.current().orElseThrow();
        clock.now = clock.now.plusSeconds(300);

        service.refresh();

        MapSnapshotService.Published second = service.current().orElseThrow();
        assertSame(first, second);
        assertEquals(Instant.parse("2026-10-14T00:00:00Z"), second.snapshot().updatedAt());
        assertEquals("\"" + first.snapshot().version() + "\"", first.etag());
    }

    @Test
    void changedOutputPublishesANewVersionAndTimestamp() {
        when(aggregate.first()).thenReturn(facets(placed(List.of(blr), 4)), facets(placed(List.of(blr), 5)));
        service.refresh();
        MapSnapshotService.Published first = service.current().orElseThrow();
        clock.now = clock.now.plusSeconds(300);

        service.refresh();

        MapSnapshotService.Published second = service.current().orElseThrow();
        assertNotEquals(first.snapshot().version(), second.snapshot().version());
        assertEquals(clock.now, second.snapshot().updatedAt());
    }

    @Test
    void failedBuildKeepsServingTheLastGoodSnapshot() {
        when(aggregate.first()).thenReturn(facets(placed(List.of(blr), 4)))
                .thenThrow(new IllegalStateException("operation exceeded time limit"));
        service.refresh();
        MapSnapshotService.Published good = service.current().orElseThrow();

        assertDoesNotThrow(service::refresh);

        assertSame(good, service.current().orElseThrow());
        assertEquals(1.0, registry.counter("jobportal.geo.snapshot.build.failures").count());
    }

    @Test
    void failedFirstBuildLeavesNoSnapshot() {
        when(aggregate.first()).thenThrow(new IllegalStateException("down"));

        service.refresh();

        assertTrue(service.current().isEmpty());
        assertEquals(1.0, registry.counter("jobportal.geo.snapshot.build.failures").count());
    }

    @Test
    void metricsDescribeThePublishedSnapshot() {
        when(aggregate.first()).thenReturn(facets(placed(List.of(blr), 4)));
        service.refresh();
        clock.now = clock.now.plusSeconds(30);

        assertEquals(1.0, registry.get("jobportal.geo.snapshot.places").gauge().value());
        assertEquals(4.0, registry.get("jobportal.geo.snapshot.placed.jobs").gauge().value());
        assertEquals(30.0, registry.get("jobportal.geo.snapshot.age.seconds").gauge().value());
        assertEquals(1, registry.get("jobportal.geo.snapshot.build").timer().count());
    }

    private static Document facets(Document... placed) {
        return new Document("placed", List.of(placed)).append("unplaced", List.of(new Document("_id", 2).append("count", 3)));
    }

    private static Document placed(List<String> ids, int count) {
        return new Document("_id", ids).append("count", count);
    }

    private static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
