package com.example.backend.auth.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The refresh limiter is a second {@link LoginRateLimiter} with its own settings, keyed per session rather than per IP
 * (GEO-7F), so login brute-force protection keeps its stricter IP + email limit.
 */
@Configuration
public class AuthRateLimiterConfiguration {
    @Bean
    LoginRateLimiter refreshRateLimiter(@Value("${security.refresh-rate-limit.attempts:20}") int attempts,
                                        @Value("${security.refresh-rate-limit.window-seconds:60}") long windowSeconds,
                                        @Value("${security.refresh-rate-limit.max-entries:10000}") int maxEntries) {
        return new LoginRateLimiter(attempts, windowSeconds, maxEntries, Clock.systemUTC());
    }
}
