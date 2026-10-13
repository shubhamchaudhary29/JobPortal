package com.example.backend.integration.aggregation;

import java.util.List;

/** The employer boards that ATS ingestion reads; Mongo is the live registry and properties are its seed. */
public interface EmployerRegistry {
    List<EmployerRegistryProperties.Employer> employers();
}
