package com.example.backend.integration.jooble;

import com.example.backend.integration.adzuna.AdzunaJobStore;
import com.example.backend.integration.aggregation.DistributedLeaseLock;
import com.example.backend.integration.aggregation.ImportedJobLifecycleService;
import com.example.backend.integration.aggregation.SyncRunService;
import com.example.backend.integration.reliability.ProviderCircuitBreaker;
import com.example.backend.integration.reliability.ProviderHttpClient;
import com.example.backend.integration.reliability.ProviderReliabilityProperties;
import com.example.backend.integration.reliability.ProviderRequestLimiter;
import com.example.backend.integration.search.ProviderRequestBudget;
import com.example.backend.integration.search.SearchCoordinatorSource;
import com.example.backend.integration.search.SearchIngestionCoordinator;
import com.example.backend.integration.search.SearchProviderIngestionService;
import java.time.Clock;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers Jooble with the search framework only when {@code JOOBLE_API_KEY} is configured. */
@Configuration
class JoobleConfiguration {
    @Bean
    SearchCoordinatorSource joobleCoordinator(JoobleProperties properties, ProviderReliabilityProperties reliability,
            ProviderHttpClient http, ProviderCircuitBreaker circuit, ProviderRequestLimiter limiter,
            AdzunaJobStore store, ImportedJobLifecycleService lifecycle, ProviderRequestBudget budget, Clock clock,
            DistributedLeaseLock locks, @Value("${job-aggregation.lock-lease-ms:300000}") long leaseMs,
            @Value("${job-aggregation.lock-renewal-ms:60000}") long renewalMs,
            @Qualifier("ingestionLeaseScheduler") ScheduledExecutorService scheduler, SyncRunService runs) {
        if (!properties.enabled()) return () -> null;
        JoobleSearchProvider provider = new JoobleSearchProvider(properties, reliability, http, circuit, limiter);
        SearchProviderIngestionService engine = new SearchProviderIngestionService(store, lifecycle, budget, clock);
        SearchIngestionCoordinator coordinator = new SearchIngestionCoordinator(provider.name(),
                leaseValid -> engine.sync(provider, leaseValid), locks, leaseMs, renewalMs, scheduler, runs);
        return () -> coordinator;
    }
}
