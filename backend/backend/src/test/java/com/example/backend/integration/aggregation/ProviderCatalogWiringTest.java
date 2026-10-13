package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.*;

import com.example.backend.integration.jobs.JobSource;
import com.example.backend.integration.reliability.ProviderReliabilityProperties;
import com.example.backend.shared.provider.ProviderCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Adding a provider must only need a JobSource bean, a catalog entry, and configuration. */
@SpringBootTest
class ProviderCatalogWiringTest {
    @Autowired EmployerIngestionService ingestion;
    @Autowired ProviderReliabilityProperties reliability;
    @Autowired List<JobSource> sources;

    @Test
    void registrySourcesAndCatalogAtsProvidersCorrespondOneToOne() {
        Set<String> registry = Arrays.stream(EmployerRegistryProperties.Source.values())
                .map(Enum::name).collect(Collectors.toSet());
        Set<String> catalog = ProviderCatalog.ofKind(ProviderCatalog.Kind.ATS_BOARD).stream()
                .map(ProviderCatalog.Provider::registrySource).collect(Collectors.toSet());
        assertEquals(catalog, registry);
        for (EmployerRegistryProperties.Source source : EmployerRegistryProperties.Source.values()) {
            assertEquals(source.name(), source.provider().registrySource());
        }
    }

    @Test
    void everyAtsProviderHasExactlyOneJobSourceBeanAndResolves() {
        for (ProviderCatalog.Provider provider : ProviderCatalog.ofKind(ProviderCatalog.Kind.ATS_BOARD)) {
            assertEquals(1, sources.stream().filter(source -> provider.name().equals(source.sourceName())).count(),
                    provider.name());
        }
        assertTrue(sources.stream().noneMatch(source -> ProviderCatalog.find(source.sourceName()).isEmpty()),
                "every JobSource bean must be a catalog provider");
        for (EmployerRegistryProperties.Source source : EmployerRegistryProperties.Source.values()) {
            assertTrue(ingestion.supports(source), source.name());
            assertTrue(reliability.baseUrl(source.provider().name()).startsWith("https://"), source.name());
        }
        assertThrows(IllegalArgumentException.class, () -> reliability.baseUrl("unknown"));
    }

    @Test
    void registryValidationScriptCoversEveryAtsProvider() throws Exception {
        String script = Files.readString(Path.of("scripts/validate-employer-registry.sh"));
        for (ProviderCatalog.Provider provider : ProviderCatalog.ofKind(ProviderCatalog.Kind.ATS_BOARD)) {
            String source = provider.registrySource();
            for (String table : List.of("provider_base", "provider_path", "provider_shape", "provider_items",
                    "provider_count")) {
                int start = script.indexOf("declare -A " + table + "=(");
                assertTrue(start >= 0, table);
                String body = script.substring(start, script.indexOf("\n)", start));
                assertTrue(body.contains("[" + source + "]="), table + " lacks " + source);
            }
            assertTrue(script.contains("[" + source + "]=" + provider.defaultBaseUrl()), source);
        }
    }

    @Test
    void duplicateJobSourcesForOneProviderAreRejected() {
        JobSource first = org.mockito.Mockito.mock(JobSource.class);
        JobSource second = org.mockito.Mockito.mock(JobSource.class);
        org.mockito.Mockito.when(first.sourceName()).thenReturn("lever");
        org.mockito.Mockito.when(second.sourceName()).thenReturn("LEVER");
        assertThrows(IllegalStateException.class, () -> new EmployerIngestionService(List.of(first, second),
                null, new EmployerRegistryProperties(List.of()), null));
    }
}
