package com.example.backend.integration.aggregation;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class EmployerIngestionScheduler {
    private final IngestionCoordinator coordinator;
    EmployerIngestionScheduler(IngestionCoordinator coordinator) { this.coordinator = coordinator; }
    @Scheduled(fixedDelayString = "${job-aggregation.scheduling.greenhouse-delay-ms:21600000}") void greenhouse() { run(EmployerRegistryProperties.Source.GREENHOUSE); }
    @Scheduled(fixedDelayString = "${job-aggregation.scheduling.lever-delay-ms:21600000}") void lever() { run(EmployerRegistryProperties.Source.LEVER); }
    void run(EmployerRegistryProperties.Source source) {
        coordinator.run(source, SyncRunService.Trigger.SCHEDULED);
    }
}
