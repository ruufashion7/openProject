package org.example.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitingServiceTest {

    @Test
    void inMemoryApiLimitIsPerWindowNotLifetime() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
        RateLimitingService service = new RateLimitingService(
                5, 15, 3, 1, "memory", null, mutableClock(now));

        assertTrue(service.isApiRequestAllowed("10.0.0.1"));
        assertTrue(service.isApiRequestAllowed("10.0.0.1"));
        assertTrue(service.isApiRequestAllowed("10.0.0.1"));
        assertFalse(service.isApiRequestAllowed("10.0.0.1"));

        // Still inside the same minute: further traffic must stay blocked.
        now.set(now.get().plus(Duration.ofSeconds(30)));
        assertFalse(service.isApiRequestAllowed("10.0.0.1"));

        // After the window, a new quota starts even if the user never went idle.
        now.set(now.get().plus(Duration.ofSeconds(31)));
        assertTrue(service.isApiRequestAllowed("10.0.0.1"));
        assertTrue(service.isApiRequestAllowed("10.0.0.1"));
        assertTrue(service.isApiRequestAllowed("10.0.0.1"));
        assertFalse(service.isApiRequestAllowed("10.0.0.1"));
    }

    @Test
    void apiLimitIsPerIdentifier() {
        RateLimitingService service = new RateLimitingService(
                5, 15, 1, 1, "memory", null, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

        assertTrue(service.isApiRequestAllowed("10.0.0.1"));
        assertFalse(service.isApiRequestAllowed("10.0.0.1"));
        assertTrue(service.isApiRequestAllowed("10.0.0.2"));
    }

    private static Clock mutableClock(AtomicReference<Instant> now) {
        return new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
    }
}
