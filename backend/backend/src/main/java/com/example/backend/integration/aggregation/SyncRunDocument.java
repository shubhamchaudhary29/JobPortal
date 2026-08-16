package com.example.backend.integration.aggregation;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("sync_runs")
public class SyncRunDocument {
    @Id private String id;
    private String runId;
    private String provider;
    private String employer;
    private SyncRunService.Trigger trigger;
    private Instant startedAt;
    private Instant completedAt;
    private SyncRunService.Outcome outcome;
    private int inserted;
    private int updated;
    private int unchanged;
    private int rejected;
    private int failedItems;
    private int failedBatches;
    private int failedEmployers;
    private int attemptedBatches;
    private int attemptedEmployers;
    private long lifecycleMatched;
    private long lifecycleModified;
    private int retries;
    private String failureType;
    private String failureDetail;
    private Instant expiresAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getEmployer() { return employer; }
    public void setEmployer(String employer) { this.employer = employer; }
    public SyncRunService.Trigger getTrigger() { return trigger; }
    public void setTrigger(SyncRunService.Trigger trigger) { this.trigger = trigger; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public SyncRunService.Outcome getOutcome() { return outcome; }
    public void setOutcome(SyncRunService.Outcome outcome) { this.outcome = outcome; }
    public int getInserted() { return inserted; }
    public void setInserted(int inserted) { this.inserted = inserted; }
    public int getUpdated() { return updated; }
    public void setUpdated(int updated) { this.updated = updated; }
    public int getUnchanged() { return unchanged; }
    public void setUnchanged(int unchanged) { this.unchanged = unchanged; }
    public int getRejected() { return rejected; }
    public void setRejected(int rejected) { this.rejected = rejected; }
    public int getFailedItems() { return failedItems; }
    public void setFailedItems(int failedItems) { this.failedItems = failedItems; }
    public int getFailedBatches() { return failedBatches; }
    public void setFailedBatches(int failedBatches) { this.failedBatches = failedBatches; }
    public int getFailedEmployers() { return failedEmployers; }
    public void setFailedEmployers(int failedEmployers) { this.failedEmployers = failedEmployers; }
    public int getAttemptedBatches() { return attemptedBatches; }
    public void setAttemptedBatches(int attemptedBatches) { this.attemptedBatches = attemptedBatches; }
    public int getAttemptedEmployers() { return attemptedEmployers; }
    public void setAttemptedEmployers(int attemptedEmployers) { this.attemptedEmployers = attemptedEmployers; }
    public long getLifecycleMatched() { return lifecycleMatched; }
    public void setLifecycleMatched(long lifecycleMatched) { this.lifecycleMatched = lifecycleMatched; }
    public long getLifecycleModified() { return lifecycleModified; }
    public void setLifecycleModified(long lifecycleModified) { this.lifecycleModified = lifecycleModified; }
    public int getRetries() { return retries; }
    public void setRetries(int retries) { this.retries = retries; }
    public String getFailureType() { return failureType; }
    public void setFailureType(String failureType) { this.failureType = failureType; }
    public String getFailureDetail() { return failureDetail; }
    public void setFailureDetail(String failureDetail) { this.failureDetail = failureDetail; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
