package com.example.backend.integration.adzuna;

import com.example.backend.integration.aggregation.DistributedLeaseLock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** The same Mongo lease protects scheduled and operator Adzuna execution across instances. */
@Service
public class AdzunaIngestionCoordinator {
    private static final String LOCK = "provider-ingestion:adzuna";
    private final AdzunaService ingestion; private final DistributedLeaseLock locks; private final long leaseMs; private final long renewalMs; private final ScheduledExecutorService scheduler;
    public AdzunaIngestionCoordinator(AdzunaService ingestion, DistributedLeaseLock locks,
            @Value("${job-aggregation.lock-lease-ms:300000}") long leaseMs,
            @Value("${job-aggregation.lock-renewal-ms:60000}") long renewalMs,
            @Qualifier("ingestionLeaseScheduler") ScheduledExecutorService scheduler) { this.ingestion=ingestion; this.locks=locks; this.leaseMs=leaseMs; this.renewalMs=renewalMs; this.scheduler=scheduler; }
    public Result run() { String owner=locks.acquire(LOCK, leaseMs); if(owner==null) return new Result(null,true,false); AtomicBoolean lost=new AtomicBoolean(); ScheduledFuture<?> heartbeat=scheduler.scheduleAtFixedRate(() -> {try {if(!locks.renew(LOCK,owner,leaseMs)) lost.set(true);}catch(RuntimeException failure){lost.set(true);}},renewalMs,renewalMs,TimeUnit.MILLISECONDS); try { return new Result(ingestion.sync(() -> !lost.get()),false,lost.get()); } finally { heartbeat.cancel(false); locks.release(LOCK,owner); } }
    @Scheduled(fixedDelayString = "${adzuna.schedule-delay-ms:43200000}") void scheduledSync() { run(); }
    public record Result(AdzunaService.SyncResult sync, boolean locked, boolean leaseLost) { }
}
