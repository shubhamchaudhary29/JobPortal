package com.example.backend.integration.search;

public record SearchSyncResult(int inserted, int updated, int unchanged, int rejected, int failedBatches,
                               int failedItems, int retries, long lifecycleMatched, long lifecycleModified,
                               int attemptedBatches, Outcome outcome, boolean budgetExhausted) {
    public static SearchSyncResult overlapRejected() {
        return new SearchSyncResult(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, Outcome.OVERLAP_REJECTED, false);
    }
    public enum Outcome { FULL_SUCCESS, PARTIAL_FAILURE, COMPLETE_FAILURE, OVERLAP_REJECTED }
}
