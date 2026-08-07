package com.example.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {
    @Test
    void validTokenContainsOnlyRequiredIdentityClaims() {
        JwtUtil jwt = configured(15);
        String token = jwt.generateToken("user@example.test", "USER");
        assertEquals("user@example.test", jwt.extractEmail(token));
        assertEquals("USER", jwt.extractRole(token));
        assertFalse(token.contains("password"));
    }

    @Test
    void expiredAndMalformedTokensAreRejected() throws InterruptedException {
        JwtUtil jwt = configured(0);
        String expired = jwt.generateToken("user@example.test", "USER");
        Thread.sleep(5);
        assertThrows(RuntimeException.class, () -> jwt.extractEmail(expired));
        assertThrows(RuntimeException.class, () -> jwt.extractEmail("not-a-jwt"));
    }

    private JwtUtil configured(long minutes) {
        JwtUtil jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "secret", "test-only-secret-material-at-least-thirty-two-bytes");
        ReflectionTestUtils.setField(jwt, "accessTokenMinutes", minutes);
        jwt.init();
        return jwt;
    }
}
