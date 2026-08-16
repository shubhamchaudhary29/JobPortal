package com.example.backend.integration.aggregation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Shared entry point so scheduled and operator initiated runs cannot overlap. */
@Service
public class IngestionCoordinator {
    private final EmployerIngestionService ingestion;
    private final DistributedLeaseLock locks;
    private final long leaseMs;
    public IngestionCoordinator(EmployerIngestionService ingestion, DistributedLeaseLock locks,
                                @Value("${job-aggregation.lock-lease-ms:300000}") long leaseMs) {
        if (leaseMs < 1_000) throw new IllegalArgumentException("job-aggregation.lock-lease-ms must be at least 1000");
        this.ingestion = ingestion; this.locks = locks; this.leaseMs = leaseMs;
    }
    public Result run(EmployerRegistryProperties.Source source) {
        String name = "employer-ingestion:" + source;
        String owner = locks.acquire(name, leaseMs);
        if (owner == null) return new Result(null, true);
        try { return new Result(ingestion.sync(source), false); }
        finally { locks.release(name, owner); }
    }
    public record Result(EmployerIngestionService.Result sync, boolean locked) { }
}
