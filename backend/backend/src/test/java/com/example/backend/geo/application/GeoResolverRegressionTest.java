package com.example.backend.geo.application;

import com.example.backend.job.domain.GeoResolution;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GEO-9A must not change any label that resolved before it. {@code resolver-regression-v3.tsv} was recorded with the
 * parser-version-3 resolver for every input of {@link GeoResolverTest} plus the GEO-9 "must not change" labels; this
 * test asserts the current resolver still gives the same status, places, localities and remote flag. Site labels are
 * new in GEO-9 and deliberately not compared.
 *
 * <p>{@code resolver-regression-v4.tsv} (GEO-10) was recorded the same way with parser version 4, before any GEO-10
 * change, over those inputs plus the geo9 goldens and labels with PIN codes, office words and tech corridors.
 *
 * <p>To re-record (only on purpose): {@code -Dgeo.regression.record=<inputs file>} writes
 * {@code -Dgeo.regression.out=<fixture>} (default the v3 fixture) from one input per line.
 */
class GeoResolverRegressionTest {
    static final String FIXTURE = "geo-test/resolver-regression-v3.tsv";
    static final List<String> FIXTURES = List.of(FIXTURE, "geo-test/resolver-regression-v4.tsv");
    private static final GeoResolver RESOLVER = new GeoResolver(new Gazetteer());

    @Test
    void labelsThatResolvedBeforeGeo9AreUnchanged() throws IOException {
        String inputs = System.getProperty("geo.regression.record");
        if (inputs != null) {
            record(Path.of(inputs), System.getProperty("geo.regression.out", FIXTURE));
            return;
        }
        List<String> failures = new ArrayList<>();
        for (String fixture : FIXTURES) {
            int checked = 0;
            for (String line : fixture(fixture)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String input = line.substring(0, line.indexOf('\t'));
                String actual = row(input);
                checked++;
                if (!actual.equals(line)) failures.add(fixture + ": expected " + line + "\n  actual " + actual);
            }
            assertTrue(checked >= 70, fixture + " has too few rows: " + checked);
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    private static String row(String input) {
        GeoResolution result = RESOLVER.resolve(input, null);
        return String.join("\t", input, result.status().name(), String.join(",", result.placeIds()),
                String.join(",", result.localityIds()), String.valueOf(result.remote()));
    }

    private static List<String> fixture(String fixture) throws IOException {
        try (InputStream in = GeoResolverRegressionTest.class.getClassLoader().getResourceAsStream(fixture)) {
            assertNotNull(in, fixture + " is missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }

    private static void record(Path inputs, String fixture) throws IOException {
        List<String> lines = new ArrayList<>(List.of("# Recorded with resolver " + RESOLVER.version()
                + " (before " + (fixture.equals(FIXTURE) ? "GEO-9" : "GEO-10") + "). input\tstatus\tplaceIds\tlocalityIds\tremote"));
        for (String input : Files.readAllLines(inputs, StandardCharsets.UTF_8)) {
            if (!input.isBlank()) lines.add(row(input));
        }
        Files.write(Path.of("src/test/resources", fixture), lines, StandardCharsets.UTF_8);
    }
}
