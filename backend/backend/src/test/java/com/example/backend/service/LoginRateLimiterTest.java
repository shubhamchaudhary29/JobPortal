package com.example.backend.service;

import com.example.backend.exception.RateLimitException;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;

class LoginRateLimiterTest {
    @Test
    void rejectsAttemptsBeyondConfiguredLimit() {
        LoginRateLimiter limiter = new LoginRateLimiter(2, 60, 10, Clock.systemUTC());
        limiter.check("ip:account");
        limiter.check("ip:account");
        assertThrows(RateLimitException.class, () -> limiter.check("ip:account"));
    }

    @Test
    void successfulAuthenticationCanResetBucket() {
        LoginRateLimiter limiter = new LoginRateLimiter(1, 60, 10, Clock.systemUTC());
        limiter.check("key");
        limiter.reset("key");
        assertDoesNotThrow(() -> limiter.check("key"));
    }
}
