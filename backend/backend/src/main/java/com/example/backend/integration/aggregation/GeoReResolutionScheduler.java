package com.example.backend.integration.aggregation;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/** Fixed-delay geo re-resolution. Only active when the global scheduling switch enables {@code @EnableScheduling}. */
@Component
@ConditionalOnProperty(prefix = "geo.reresolution", name = "enabled", havingValue = "true", matchIfMissing = true)
class GeoReResolutionScheduler implements SchedulingConfigurer {
    private final GeoReResolutionService service;
    private final long delayMs;

    GeoReResolutionScheduler(GeoReResolutionService service, @Value("${geo.reresolution.delay-ms:600000}") long delayMs) {
        this.service = service;
        this.delayMs = delayMs;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(service::runOnce, Duration.ofMillis(delayMs));
    }
}
