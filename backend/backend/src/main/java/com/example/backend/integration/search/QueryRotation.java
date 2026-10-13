package com.example.backend.integration.search;

import java.util.List;
import java.util.Set;

/**
 * Hands each run the next slice of a plan that is too large for one run's request allowance, and accumulates
 * the identities seen across one full rotation cycle. Lifecycle may only use a cycle's seen set when every group
 * in that cycle succeeded, so a job is never counted missing just because its query was not part of a run.
 */
public interface QueryRotation {
    /** The groups for this run, starting at the provider's cursor and wrapping at the end of the plan. */
    Slice next(String provider, SearchQueryPlan plan, int groupsPerRun);

    /**
     * Records the first {@code processedGroups} groups of the slice (those fully attempted), the identities they
     * returned, and whether any failed. Returns the finished cycle when this run reached the end of the plan.
     */
    CycleResult record(String provider, Slice slice, int processedGroups, Set<String> seenIdentities, boolean failed);

    record Slice(String planHash, int start, List<SearchQueryPlan.Group> groups, int planGroups) { }

    /** A completed cycle; {@code complete} is false when any group in the cycle failed or was skipped. */
    record CycleResult(boolean complete, Set<String> seenIdentities) { }
}
