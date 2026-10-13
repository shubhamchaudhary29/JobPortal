package com.example.backend.integration.search;

/** A bean that exposes the shared coordinator for one search provider. */
public interface SearchCoordinatorSource {
    SearchIngestionCoordinator searchCoordinator();
}
