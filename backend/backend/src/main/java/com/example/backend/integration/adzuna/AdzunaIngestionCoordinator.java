package com.example.backend.integration.adzuna;

import com.example.backend.integration.aggregation.DistributedLeaseLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.concurrent.ScheduledExecutorService;

/** The same Mongo lease protects scheduled and operator Adzuna execution across instances. */
@Service
public class AdzunaIngestionCoordinator {
    private static final String LOCK = "provider-ingestion:adzuna";
    private final AdzunaService ingestion; private final DistributedLeaseLock locks; private final long leaseMs;
    public AdzunaIngestionCoordinator(AdzunaService ingestion, DistributedLeaseLock locks,
            @Value("${job-aggregation.lock-lease-ms:300000}") long leaseMs,
            @Qualifier("ingestionLeaseScheduler") ScheduledExecutorService ignoredScheduler) { this.ingestion=ingestion; this.locks=locks; this.leaseMs=leaseMs; }
    public Result run() { String owner=locks.acquire(LOCK, leaseMs); if(owner==null) return new Result(null,true); try { return new Result(ingestion.sync(),false); } finally { locks.release(LOCK,owner); } }
    @Scheduled(fixedDelayString = "${adzuna.schedule-delay-ms:43200000}") void scheduledSync() { run(); }
    public record Result(AdzunaService.SyncResult sync, boolean locked) { }
}
