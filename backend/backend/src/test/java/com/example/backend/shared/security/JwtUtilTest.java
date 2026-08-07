package com.example.backend.shared.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {
    private static final String SECRET = "test-only-secret-material-at-least-thirty-two-bytes";

    @Test
    void validTokenContainsOnlyRequiredClaims() {
        JwtUtil jwt = new JwtUtil(SECRET, 15);
        String token = jwt.generateToken("user@example.test", "USER");
        assertEquals("user@example.test", jwt.extractEmail(token));
        assertEquals("USER", jwt.extractRole(token));
        assertFalse(token.contains("password"));
    }

    @Test
    void expiredAndMalformedTokensAreRejected() throws InterruptedException {
        JwtUtil jwt = new JwtUtil(SECRET, 0);
        String token = jwt.generateToken("user@example.test", "USER");
        Thread.sleep(5);
        assertThrows(RuntimeException.class, () -> jwt.extractEmail(token));
        assertThrows(RuntimeException.class, () -> jwt.extractEmail("not-a-jwt"));
    }
}
