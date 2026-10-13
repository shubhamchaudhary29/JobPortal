package com.example.backend.integration.workable;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class WorkableJobSourceTest {
    @Test
    void locationUsesCityStateCountryOrRemoteForTelecommutingJobs() {
        assertAll(
                () -> assertEquals("Pune, Maharashtra, India", WorkableJobSource.location(job(false, "Pune", "Maharashtra", "India"))),
                () -> assertEquals("Delhi, India", WorkableJobSource.location(job(null, "Delhi", "Delhi", "India"))),
                () -> assertEquals("India", WorkableJobSource.location(job(false, " ", null, "India"))),
                () -> assertEquals("Remote", WorkableJobSource.location(job(true, "Paris", null, "France"))),
                () -> assertNull(WorkableJobSource.location(job(false, null, "", null))));
    }

    private WorkableJobDto job(Boolean remote, String city, String state, String country) {
        return new WorkableJobDto("X", "Engineer", null, null, remote, city, state, country, null, null, null);
    }
}
