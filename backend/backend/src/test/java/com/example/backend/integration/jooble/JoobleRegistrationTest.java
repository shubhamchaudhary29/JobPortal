package com.example.backend.integration.jooble;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.backend.integration.search.SearchIngestionCoordinators;
import com.example.backend.shared.security.JwtUtil;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

class JoobleRegistrationTest {
    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    class WithoutAKey {
        @Autowired SearchIngestionCoordinators coordinators;
        @Autowired MockMvc mvc;
        @Autowired JwtUtil jwt;

        @Test
        void joobleIsDisabledUnavailableAndCannotBeSynchronized() throws Exception {
            assertTrue(coordinators.find("jooble").isEmpty());
            assertTrue(coordinators.find("adzuna").isPresent());
            String admin = "Bearer " + jwt.generateToken("admin@example.com", "ADMIN");
            mvc.perform(get("/api/v1/admin/ingestion/providers").header(HttpHeaders.AUTHORIZATION, admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.name == 'jooble')].available").value(false))
                    .andExpect(jsonPath("$[?(@.name == 'adzuna')].available").value(true))
                    .andExpect(jsonPath("$[?(@.name == 'greenhouse')].available").value(true));
            mvc.perform(post("/api/v1/admin/ingestion/jooble/sync").header(HttpHeaders.AUTHORIZATION, admin))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("Jooble is not configured"));
        }
    }

    @Nested
    @SpringBootTest(properties = "jooble.api-key=test-only-jooble-key-0001")
    class WithAKey {
        @Autowired SearchIngestionCoordinators coordinators;
        @Autowired JoobleProperties properties;

        @Test
        void joobleRegistersAScheduledCoordinatorWithTheIndiaDefaults() {
            assertEquals("jooble", coordinators.find("jooble").orElseThrow().provider());
            assertTrue(properties.enabled());
            assertEquals("https://in.jooble.org/api", properties.baseUrl());
            assertEquals("India", properties.location());
            assertEquals(100, properties.monthlyRequestBudget());
            assertFalse(properties.toString().contains("test-only-jooble-key-0001"));
        }
    }

    @Test
    void malformedKeysAndUnsafeBaseUrlsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new JoobleProperties("key with spaces", "https://in.jooble.org/api", "a", "India", 1, 20, 100));
        assertThrows(IllegalArgumentException.class,
                () -> new JoobleProperties("", "file:///etc/passwd", "a", "India", 1, 20, 100));
        assertThrows(IllegalArgumentException.class,
                () -> new JoobleProperties("", "https://in.jooble.org/api", "a", "India", 1, 20, 0));
        assertFalse(new JoobleProperties(" ", "https://in.jooble.org/api", "a", "India", 1, 20, 100).enabled());
    }
}
