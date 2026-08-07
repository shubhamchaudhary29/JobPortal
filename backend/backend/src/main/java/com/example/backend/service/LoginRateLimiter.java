package com.example.backend.service;

import com.example.backend.exception.RateLimitException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class LoginRateLimiter {
    private final int limit;
    private final long windowSeconds;
    private final int maxEntries;
    private final Clock clock;
    private final Map<String, AttemptWindow> attempts = new LinkedHashMap<>();

    @Autowired
    public LoginRateLimiter(@Value("${security.login-rate-limit.attempts:5}") int limit,
                            @Value("${security.login-rate-limit.window-seconds:60}") long windowSeconds,
                            @Value("${security.login-rate-limit.max-entries:10000}") int maxEntries) {
        this(limit, windowSeconds, maxEntries, Clock.systemUTC());
    }

    LoginRateLimiter(int limit, long windowSeconds, int maxEntries, Clock clock) {
        this.limit = limit;
        this.windowSeconds = windowSeconds;
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    public synchronized void check(String key) {
        Instant now = clock.instant();
        attempts.entrySet().removeIf(entry -> entry.getValue().started.plusSeconds(windowSeconds).isBefore(now));
        AttemptWindow window = attempts.computeIfAbsent(key, ignored -> new AttemptWindow(now));
        if (window.count >= limit) {
            long retry = Math.max(1, window.started.plusSeconds(windowSeconds).getEpochSecond() - now.getEpochSecond());
            throw new RateLimitException(retry);
        }
        window.count++;
        while (attempts.size() > maxEntries) {
            Iterator<String> iterator = attempts.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    public synchronized void reset(String key) { attempts.remove(key); }

    private static final class AttemptWindow {
        private final Instant started;
        private int count;
        private AttemptWindow(Instant started) { this.started = started; }
    }
}
