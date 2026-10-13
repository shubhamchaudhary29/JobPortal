package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** The test classpath shadows the production properties, so bind the shipped registry explicitly. */
class ProductionEmployerRegistryTest {
    @Test
    void shippedRegistryBindsAndPassesRegistryValidation() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(Path.of("src/main/resources/application.properties"))) {
            properties.load(input);
        }
        Map<String, String> registry = new HashMap<>();
        properties.stringPropertyNames().stream().filter(key -> key.startsWith("job-aggregation.employers["))
                .forEach(key -> registry.put(key, properties.getProperty(key)));

        EmployerRegistryProperties bound = new Binder(new MapConfigurationPropertySource(registry))
                .bind("job-aggregation", EmployerRegistryProperties.class).get();

        assertEquals(registry.size() / 4, bound.employers().size());
        assertTrue(bound.employers().stream().allMatch(employer -> employer.source().provider().employerScoped()));
        assertTrue(bound.employers().stream().noneMatch(employer -> employer.enabled()
                && employer.source() == EmployerRegistryProperties.Source.WORKABLE),
                "Workable boards stay disabled until its terms are confirmed");
    }
}
