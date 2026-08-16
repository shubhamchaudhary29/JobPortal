package com.example.backend.integration.aggregation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "job-aggregation.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
class EmployerIngestionScheduler {
    private final EmployerIngestionService ingestion; private final DistributedLeaseLock locks;
    EmployerIngestionScheduler(EmployerIngestionService ingestion, DistributedLeaseLock locks) { this.ingestion = ingestion; this.locks = locks; }
    @Scheduled(fixedDelayString = "${job-aggregation.scheduling.greenhouse-delay-ms:21600000}") void greenhouse() { run(EmployerRegistryProperties.Source.GREENHOUSE); }
    @Scheduled(fixedDelayString = "${job-aggregation.scheduling.lever-delay-ms:21600000}") void lever() { run(EmployerRegistryProperties.Source.LEVER); }
    void run(EmployerRegistryProperties.Source source) { String name="employer-ingestion:"+source; String owner=locks.acquire(name, 300_000); if(owner==null)return; try { ingestion.sync(source); } finally { locks.release(name,owner); } }
}
