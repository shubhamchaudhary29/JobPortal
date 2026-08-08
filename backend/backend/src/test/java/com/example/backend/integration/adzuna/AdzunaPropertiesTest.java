package com.example.backend.integration.adzuna;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdzunaPropertiesTest {
    @Test
    void rejects_blank_credentials_and_unsafe_batch_bounds() {
        assertThrows(IllegalArgumentException.class, () -> new AdzunaProperties(" ", "key", 1, 1, 1, 1, 1, 1, 1, 1, "java"));
        assertThrows(IllegalArgumentException.class, () -> new AdzunaProperties("id", "key", 1, 1, 6, 1, 1, 1, 1, 1, "java"));
        assertThrows(IllegalArgumentException.class, () -> new AdzunaProperties("id", "key", 1, 1, 1, 1, 1, 1, 11, 1, "java"));
    }
}
