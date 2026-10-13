package com.example.backend.integration.aggregation;

import com.example.backend.shared.provider.ProviderCatalog;
import java.time.Duration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * One independent fixed-delay schedule per ATS provider in the catalog, read from the provider's schedule
 * property. Only registered when the global scheduling switch enables {@code @EnableScheduling}.
 */
@Component
class EmployerIngestionScheduler implements SchedulingConfigurer {
    private final IngestionCoordinator coordinator;
    private final Environment environment;
    EmployerIngestionScheduler(IngestionCoordinator coordinator, Environment environment) {
        this.coordinator = coordinator;
        this.environment = environment;
    }
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        for (ProviderCatalog.Provider provider : ProviderCatalog.ofKind(ProviderCatalog.Kind.ATS_BOARD)) {
            var source = EmployerRegistryProperties.Source.valueOf(provider.registrySource());
            long delayMs = environment.getProperty(provider.scheduleProperty(), Long.class, provider.defaultDelayMs());
            registrar.addFixedDelayTask(() -> run(source), Duration.ofMillis(delayMs));
        }
    }
    void run(EmployerRegistryProperties.Source source) {
        coordinator.run(source, SyncRunService.Trigger.SCHEDULED);
    }
}
