package com.example.backend.integration.search;

import com.example.backend.integration.aggregation.SyncRunService;
import com.example.backend.shared.provider.ProviderCatalog;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Coordinators for every registered search provider, keyed by catalog name. */
@Service
public class SearchIngestionCoordinators {
    private final Map<String, SearchIngestionCoordinator> coordinators;

    public SearchIngestionCoordinators(List<SearchCoordinatorSource> sources) {
        Map<String, SearchIngestionCoordinator> byName = new LinkedHashMap<>();
        for (SearchCoordinatorSource source : sources) {
            SearchIngestionCoordinator coordinator = source.searchCoordinator();
            if (coordinator == null) continue;
            ProviderCatalog.Provider provider = ProviderCatalog.find(coordinator.provider())
                    .filter(candidate -> candidate.kind() == ProviderCatalog.Kind.SEARCH_API)
                    .orElseThrow(() -> new IllegalStateException("Search coordinator is not a catalog search API"));
            if (byName.putIfAbsent(provider.name(), coordinator) != null) {
                throw new IllegalStateException("Duplicate search coordinator for " + provider.name());
            }
        }
        this.coordinators = Collections.unmodifiableMap(byName);
    }

    public Optional<SearchIngestionCoordinator> find(String provider) {
        return ProviderCatalog.find(provider).map(found -> coordinators.get(found.name()));
    }

    public Map<String, SearchIngestionCoordinator> all() { return coordinators; }

    public SearchIngestionCoordinator.Result run(String provider, SyncRunService.Trigger trigger) {
        return find(provider).orElseThrow(() -> new IllegalArgumentException("Unknown search provider")).run(trigger);
    }
}
