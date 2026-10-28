package com.example.backend.integration.greenhouse;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/** GEO-10E against the captured Greenhouse sample: office address text is evidence; there are no coordinates. */
class GreenhouseLocationHintTest {
    @Test
    void officeLocationsBecomeAddressText() throws Exception {
        List<GreenhouseJobDto> jobs;
        try (InputStream in = GreenhouseLocationHintTest.class.getClassLoader().getResourceAsStream("ats-samples/greenhouse.json")) {
            jobs = new ObjectMapper().readValue(in, GreenhouseJobsResponse.class).jobs();
        }
        assertEquals("Bangalore, Karnataka, India", GreenhouseJobSource.officeHint(jobs.get(0)).address());
        assertNull(GreenhouseJobSource.officeHint(jobs.get(1)), "an office without a location says nothing");
        assertNull(GreenhouseJobSource.officeHint(new GreenhouseJobDto(1L, "t", null, null, null, null)));
    }
}
