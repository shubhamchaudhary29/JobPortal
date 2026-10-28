package com.example.backend.integration.ats;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * GEO-10E: which location fields each ATS actually sends, from one captured response per ATS
 * ({@code src/test/resources/ats-samples/}). Only fields shown present here are mapped; Workable has no registry board
 * to sample, so nothing is mapped for it.
 */
class AtsSampleFieldsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void smartRecruitersSendsCoordinatesPostalCodeAndStreetAddress() throws IOException {
        List<String> fields = fields("smartrecruiters", "content", "location");
        assertTrue(fields.containsAll(List.of("latitude", "longitude", "postalCode", "address", "city")), fields.toString());
    }

    @Test
    void greenhouseSendsOfficeAddressTextOnly() throws IOException {
        JsonNode job = sample("greenhouse").get("jobs").get(0);
        assertEquals("Bangalore, Karnataka, India", job.get("offices").get(0).get("location").asText());
        assertNoCoordinatesOrPostalCode("greenhouse");
    }

    @Test
    void ashbyLocalityIsCityLevelAndLeverHasNoStructuredLocation() throws IOException {
        for (JsonNode job : sample("ashby").get("jobs")) {
            assertEquals(job.get("location").asText(), job.get("address").get("postalAddress").get("addressLocality").asText(),
                    "Ashby's addressLocality repeats the city, so it adds nothing");
        }
        assertNoCoordinatesOrPostalCode("ashby");
        assertNoCoordinatesOrPostalCode("lever");
        assertFalse(sample("lever").toString().contains("address"));
    }

    private static void assertNoCoordinatesOrPostalCode(String ats) throws IOException {
        String text = sample(ats).toString().toLowerCase();
        for (String field : List.of("latitude", "longitude", "\"lat\"", "\"lng\"", "postalcode", "postal_code", "zip")) {
            assertFalse(text.contains(field), ats + " has " + field);
        }
    }

    private static List<String> fields(String ats, String array, String object) throws IOException {
        List<String> names = new ArrayList<>();
        sample(ats).get(array).forEach(item -> item.get(object).fieldNames().forEachRemaining(names::add));
        return names;
    }

    static JsonNode sample(String ats) throws IOException {
        try (InputStream in = AtsSampleFieldsTest.class.getClassLoader().getResourceAsStream("ats-samples/" + ats + ".json")) {
            assertNotNull(in, ats);
            return JSON.readTree(in);
        }
    }
}
