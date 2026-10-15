package com.example.backend.integration.aggregation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.backend.shared.text.CompanyNames;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Moving company normalization to {@link CompanyNames} (GEO-6B) must not change any fingerprint. The fixture was
 * captured from {@link JobFingerprint} before the move (commit 48eafaa): its company key and full fingerprint for
 * every suffix and alias in company-aliases.json plus unicode, punctuation and blank names.
 */
class CompanyNamesFingerprintFixtureTest {
    private static final String TITLE = "Backend Engineer";
    private static final String LOCATION = "Bengaluru, Karnataka";

    @Test
    void companyKeysAndFingerprintsAreByteIdentical() throws Exception {
        List<JsonNode> rows = fixture();
        assertTrue(rows.size() >= 50, "fixture covers at least 50 company strings");
        for (JsonNode row : rows) {
            String company = row.get("company").asText();
            assertEquals(row.get("key").asText(), CompanyNames.key(company), company);
            assertEquals(row.get("key").asText(), JobFingerprint.company(company), company);
            assertEquals(row.get("fingerprint").asText(), JobFingerprint.of(company, TITLE, LOCATION), company);
        }
        assertEquals(2, JobFingerprint.VERSION);
    }

    @Test
    void fixtureCoversEverySuffixAndAlias() throws Exception {
        List<String> companies = fixture().stream().map(row -> row.get("company").asText().toLowerCase()).toList();
        JsonNode table;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("aggregation/company-aliases.json")) {
            table = new ObjectMapper().readTree(input);
        }
        table.get("suffixes").forEach(suffix -> assertTrue(
                companies.stream().anyMatch(company -> company.endsWith(" " + suffix.asText())), suffix.asText()));
        table.get("aliases").fields().forEachRemaining(alias -> {
            assertTrue(companies.contains(alias.getKey()), alias.getKey());
            assertTrue(companies.contains(alias.getValue().asText()), alias.getValue().asText());
        });
    }

    private List<JsonNode> fixture() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("shared/text/company-key-fixture.json")) {
            List<JsonNode> rows = new ArrayList<>();
            new ObjectMapper().readTree(input).forEach(rows::add);
            return rows;
        }
    }
}
