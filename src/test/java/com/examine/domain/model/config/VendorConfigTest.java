package com.examine.domain.model.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VendorConfigTest {

    @Test
    void createVendorConfig() {
        VendorConfig config = new VendorConfig(
                "ad-platform-a",
                "https://api.example.com/track",
                HttpMethod.POST,
                Map.of("Authorization", "Bearer token"),
                "{\"userId\":\"{{userId}}\"}",
                Duration.ofSeconds(30),
                new RetryPolicySettings(10, Duration.ofSeconds(1), Duration.ofHours(1)),
                new RateLimitSettings(10, 20),
                new CircuitBreakerSettings(CircuitBreakerMode.AUTO, 50, 10, 60, 3),
                IdempotencyKeyLocation.HEADER,
                "Idempotency-Key"
        );

        assertEquals("ad-platform-a", config.vendorKey());
        assertEquals("https://api.example.com/track", config.endpoint());
        assertEquals(HttpMethod.POST, config.method());
        assertEquals(Map.of("Authorization", "Bearer token"), config.headers());
        assertEquals("{\"userId\":\"{{userId}}\"}", config.bodyTemplate());
        assertEquals(Duration.ofSeconds(30), config.timeout());
        assertEquals(10, config.retryPolicy().maxAttempts());
        assertEquals(10, config.rateLimit().qps());
        assertEquals(CircuitBreakerMode.AUTO, config.circuitBreaker().mode());
        assertEquals(IdempotencyKeyLocation.HEADER, config.idempotencyKeyLocation());
        assertEquals("Idempotency-Key", config.idempotencyKeyName());
    }

    @Test
    void createSettingsRecords() {
        RetryPolicySettings retry = new RetryPolicySettings(5, Duration.ofMillis(100), Duration.ofMinutes(10));
        assertEquals(5, retry.maxAttempts());
        assertEquals(Duration.ofMillis(100), retry.baseDelay());
        assertEquals(Duration.ofMinutes(10), retry.maxDelay());

        RateLimitSettings rate = new RateLimitSettings(2, 5);
        assertEquals(2, rate.qps());
        assertEquals(5, rate.burst());

        CircuitBreakerSettings cb = new CircuitBreakerSettings(CircuitBreakerMode.FORCE_OPEN, 60, 5, 30, 2);
        assertEquals(CircuitBreakerMode.FORCE_OPEN, cb.mode());
        assertEquals(60, cb.failureRateThreshold());
    }
}
