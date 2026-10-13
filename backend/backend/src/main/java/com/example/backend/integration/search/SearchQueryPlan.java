package com.example.backend.integration.search;

import java.util.ArrayList;
import java.util.List;

/**
 * Ordered keyword × location groups, each fetched page by page. A failed page ends its group, so later pages of
 * the same query are never requested after a failure.
 */
public record SearchQueryPlan(List<Group> groups) {
    public SearchQueryPlan {
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    /** Every keyword (trimmed, blanks skipped) at one optional location with the same page count. */
    public static SearchQueryPlan keywords(String[] keywords, String location, int pages) {
        List<Group> groups = new ArrayList<>();
        for (String raw : keywords) {
            String keyword = raw == null ? "" : raw.trim();
            if (!keyword.isEmpty()) groups.add(new Group(keyword, location, pages));
        }
        return new SearchQueryPlan(groups);
    }

    /** Upper bound of first-attempt requests in one full run. */
    public int requestCount() {
        return groups.stream().mapToInt(Group::pages).sum();
    }

    public record Group(String keyword, String location, int pages) {
        public Group {
            if (keyword == null || keyword.isBlank()) throw new IllegalArgumentException("keyword is required");
            if (pages < 1) throw new IllegalArgumentException("pages must be positive");
        }
    }
}
