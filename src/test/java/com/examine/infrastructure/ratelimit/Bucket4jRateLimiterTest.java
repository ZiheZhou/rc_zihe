package com.examine.infrastructure.ratelimit;

import com.examine.domain.model.config.CircuitBreakerMode;
import com.examine.domain.model.config.CircuitBreakerSettings;
import com.examine.domain.model.config.HttpMethod;
import com.examine.domain.model.config.IdempotencyKeyLocation;
import com.examine.domain.model.config.RateLimitSettings;
import com.examine.domain.model.config.RetryPolicySettings;
import com.examine.domain.model.config.VendorConfig;
import com.examine.infrastructure.config.VendorConfigCache;
import com.examine.support.InMemoryVendorConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Bucket4jRateLimiterTest {

    private InMemoryVendorConfigRepository repository;
    private VendorConfigCache cache;
    private Bucket4jRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        repository = new InMemoryVendorConfigRepository();
        cache = new VendorConfigCache(repository);
        rateLimiter = new Bucket4jRateLimiter(cache);
    }

    private void saveConfig(String vendorKey, int qps, int burst) {
        repository.save(new VendorConfig(
                vendorKey, "https://api.example.com", HttpMethod.POST, Map.of(), "{}",
                Duration.ofSeconds(30),
                new RetryPolicySettings(10, Duration.ofSeconds(1), Duration.ofHours(1)),
                new RateLimitSettings(qps, burst),
                new CircuitBreakerSettings(CircuitBreakerMode.AUTO, 50, 10, 60, 3),
                IdempotencyKeyLocation.HEADER, "Idempotency-Key"));
        cache.refresh(vendorKey);
    }

    @Test
    void burstExhaustionRejectsThirdCall() {
        saveConfig("vendor-a", 2, 2);

        assertTrue(rateLimiter.tryAcquire("vendor-a"));
        assertTrue(rateLimiter.tryAcquire("vendor-a"));
        assertFalse(rateLimiter.tryAcquire("vendor-a"));
    }

    @Test
    void tokensRefillOverTime() throws InterruptedException {
        saveConfig("vendor-a", 2, 2);
        rateLimiter.tryAcquire("vendor-a");
        rateLimiter.tryAcquire("vendor-a");
        assertFalse(rateLimiter.tryAcquire("vendor-a"));

        Thread.sleep(1100); // refillGreedy(2, 1s)

        assertTrue(rateLimiter.tryAcquire("vendor-a"));
    }

    @Test
    void configChangeRebuildsBucket() {
        saveConfig("vendor-a", 2, 2);
        rateLimiter.tryAcquire("vendor-a");
        rateLimiter.tryAcquire("vendor-a");
        assertFalse(rateLimiter.tryAcquire("vendor-a"));

        saveConfig("vendor-a", 100, 100); // 调大额度 → 桶重建

        assertTrue(rateLimiter.tryAcquire("vendor-a"));
    }

    @Test
    void vendorsAreIsolated() {
        saveConfig("vendor-a", 1, 1);
        saveConfig("vendor-b", 1, 1);

        assertTrue(rateLimiter.tryAcquire("vendor-a"));
        assertFalse(rateLimiter.tryAcquire("vendor-a"));
        assertTrue(rateLimiter.tryAcquire("vendor-b"));
    }

    @Test
    void unknownVendorIsNotLimited() {
        assertTrue(rateLimiter.tryAcquire("vendor-unknown"));
    }
}
