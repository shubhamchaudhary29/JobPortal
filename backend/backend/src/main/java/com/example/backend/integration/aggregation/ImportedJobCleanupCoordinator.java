package com.example.backend.integration.aggregation;

import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ImportedJobCleanupCoordinator {
    static final String LOCK_NAME = "maintenance:imported-job-retention";
    private final ImportedJobCleanupService cleanup;
    private final DistributedLeaseLock locks;
    private final long leaseMs;

    public ImportedJobCleanupCoordinator(ImportedJobCleanupService cleanup, DistributedLeaseLock locks,
            @Value("${job-aggregation.cleanup.lock-lease-ms:300000}") long leaseMs) {
        if (leaseMs < 1_000) throw new IllegalArgumentException("cleanup lease must be at least 1000ms");
        this.cleanup = cleanup;
        this.locks = locks;
        this.leaseMs = leaseMs;
    }

    public Result run() {
        String owner = locks.acquire(LOCK_NAME, leaseMs);
        if (owner == null) return new Result(null, true);
        try {
            return new Result(cleanup.cleanup(LocalDateTime.now()), false);
        } finally {
            locks.release(LOCK_NAME, owner);
        }
    }

    @Scheduled(cron = "${job-aggregation.cleanup.cron:0 30 2 * * *}")
    void scheduledCleanup() { run(); }

    public record Result(ImportedJobCleanupService.Result cleanup, boolean locked) { }
}
