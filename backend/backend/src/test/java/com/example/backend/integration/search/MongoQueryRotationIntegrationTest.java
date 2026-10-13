package com.example.backend.integration.search;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

@SpringBootTest
class MongoQueryRotationIntegrationTest {
    @Autowired MongoQueryRotation rotation;
    @Autowired MongoTemplate mongo;
    private final SearchQueryPlan plan = SearchQueryPlan.keywords(new String[] {"a", "b", "c", "d", "e"}, null, 1);

    @BeforeEach
    void clear() { mongo.getCollection(MongoQueryRotation.COLLECTION).deleteMany(new org.bson.Document()); }

    @Test
    void slicesWrapAndOnlyAFailureFreeCycleIsComplete() {
        QueryRotation.Slice first = rotation.next("p", plan, 2);
        assertEquals(List.of("a", "b"), keywords(first));
        assertNull(rotation.record("p", first, 2, Set.of("p:1"), false));
        QueryRotation.Slice second = rotation.next("p", plan, 2);
        assertEquals(List.of("c", "d"), keywords(second));
        assertNull(rotation.record("p", second, 2, Set.of("p:2"), true));
        QueryRotation.Slice last = rotation.next("p", plan, 2);
        assertEquals(List.of("e"), keywords(last));
        QueryRotation.CycleResult failedCycle = rotation.record("p", last, 1, Set.of("p:3"), false);
        assertFalse(failedCycle.complete());
        assertEquals(Set.of("p:1", "p:2", "p:3"), failedCycle.seenIdentities());

        QueryRotation.CycleResult clean = null;
        for (int run = 0; run < 3; run++) {
            QueryRotation.Slice slice = rotation.next("p", plan, 2);
            clean = rotation.record("p", slice, slice.groups().size(), Set.of("p:" + run), false);
        }
        assertTrue(clean.complete());
        assertEquals(Set.of("p:0", "p:1", "p:2"), clean.seenIdentities());
    }

    @Test
    void budgetCutGroupsCarryOverWithoutFailingTheCycleAndPlanChangesRestart() {
        QueryRotation.Slice slice = rotation.next("p", plan, 3);
        rotation.record("p", slice, 1, Set.of(), false);
        assertEquals(List.of("b", "c", "d"), keywords(rotation.next("p", plan, 3)));

        SearchQueryPlan changed = SearchQueryPlan.keywords(new String[] {"x", "y"}, "Pune", 1);
        assertEquals(List.of("x", "y"), keywords(rotation.next("p", changed, 5)));
        QueryRotation.Slice stale = new QueryRotation.Slice(MongoQueryRotation.hash(plan), 1, plan.groups().subList(1, 2), 5);
        assertNull(rotation.record("p", stale, 1, Set.of("ignored"), false));
        QueryRotation.Slice fresh = rotation.next("p", changed, 5);
        QueryRotation.CycleResult cycle = rotation.record("p", fresh, 2, Set.of("p:x"), false);
        assertTrue(cycle.complete());
        assertEquals(Set.of("p:x"), cycle.seenIdentities());
    }

    private static List<String> keywords(QueryRotation.Slice slice) {
        return slice.groups().stream().map(SearchQueryPlan.Group::keyword).toList();
    }
}
