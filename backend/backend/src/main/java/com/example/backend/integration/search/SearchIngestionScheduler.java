package com.example.backend.integration.search;

import com.example.backend.integration.aggregation.SyncRunService;
import com.example.backend.shared.provider.ProviderCatalog;
import java.time.Duration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * One independent fixed-delay schedule per registered search provider, read from its catalog schedule property.
 * Only active when the global scheduling switch enables {@code @EnableScheduling}.
 */
@Component
class SearchIngestionScheduler implements SchedulingConfigurer {
    private final SearchIngestionCoordinators coordinators;
    private final Environment environment;

    SearchIngestionScheduler(SearchIngestionCoordinators coordinators, Environment environment) {
        this.coordinators = coordinators;
        this.environment = environment;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        for (ProviderCatalog.Provider provider : ProviderCatalog.ofKind(ProviderCatalog.Kind.SEARCH_API)) {
            SearchIngestionCoordinator coordinator = coordinators.all().get(provider.name());
            if (coordinator == null) continue;
            long delayMs = environment.getProperty(provider.scheduleProperty(), Long.class, provider.defaultDelayMs());
            registrar.addFixedDelayTask(() -> coordinator.run(SyncRunService.Trigger.SCHEDULED), Duration.ofMillis(delayMs));
        }
    }
}
