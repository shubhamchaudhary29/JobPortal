package com.example.backend.integration.search;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SearchQueryPlanTest {
    @Test
    void keywordPlansTrimSkipBlanksAndCountRequests() {
        SearchQueryPlan plan = SearchQueryPlan.keywords(new String[] {" java developer ", "", "  ", "data analyst"}, "Pune", 3);
        assertEquals(List.of(new SearchQueryPlan.Group("java developer", "Pune", 3),
                new SearchQueryPlan.Group("data analyst", "Pune", 3)), plan.groups());
        assertEquals(6, plan.requestCount());
        assertEquals(0, new SearchQueryPlan(null).requestCount());
        assertThrows(IllegalArgumentException.class, () -> new SearchQueryPlan.Group(" ", null, 1));
        assertThrows(IllegalArgumentException.class, () -> new SearchQueryPlan.Group("java", null, 0));
        assertThrows(UnsupportedOperationException.class, () -> plan.groups().add(plan.groups().get(0)));
    }
}
