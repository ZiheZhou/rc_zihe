package com.examine.infrastructure.circuitbreaker;

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

class Resilience4jVendorCircuitBreakerTest {

    private InMemoryVendorConfigRepository repository;
    private VendorConfigCache cache;
    private Resilience4jVendorCircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        repository = new InMemoryVendorConfigRepository();
        cache = new VendorConfigCache(repository);
        circuitBreaker = new Resilience4jVendorCircuitBreaker(cache);
    }

    private void saveConfig(String vendorKey, CircuitBreakerSettings settings) {
        repository.save(new VendorConfig(
                vendorKey, "https://api.example.com", HttpMethod.POST, Map.of(), "{}",
                Duration.ofSeconds(30),
                new RetryPolicySettings(10, Duration.ofSeconds(1), Duration.ofHours(1)),
                new RateLimitSettings(100, 100),
                settings,
                IdempotencyKeyLocation.HEADER, "Idempotency-Key"));
        cache.refresh(vendorKey);
    }

    private CircuitBreakerSettings auto(int failureRateThreshold, int minCalls, int cooldownSeconds) {
        return new CircuitBreakerSettings(CircuitBreakerMode.AUTO, failureRateThreshold, minCalls, cooldownSeconds, 1);
    }

    @Test
    void autoModeOpensAfterFailureThresholdReached() {
        saveConfig("vendor-a", auto(50, 4, 60));

        for (int i = 0; i < 4; i++) {
            assertTrue(circuitBreaker.allowCall("vendor-a"));
            circuitBreaker.onFailure("vendor-a");
        }

        assertFalse(circuitBreaker.allowCall("vendor-a"));
    }

    @Test
    void autoModeStaysClosedBelowThreshold() {
        saveConfig("vendor-a", auto(50, 4, 60));

        circuitBreaker.allowCall("vendor-a");
        circuitBreaker.onSuccess("vendor-a");
        circuitBreaker.allowCall("vendor-a");
        circuitBreaker.onFailure("vendor-a");
        circuitBreaker.allowCall("vendor-a");
        circuitBreaker.onSuccess("vendor-a");
        circuitBreaker.allowCall("vendor-a");
        circuitBreaker.onSuccess("vendor-a");

        assertTrue(circuitBreaker.allowCall("vendor-a"));
    }

    @Test
    void autoModePermitsProbeAfterCooldown() throws InterruptedException {
        saveConfig("vendor-a", auto(50, 2, 1));

        circuitBreaker.allowCall("vendor-a");
        circuitBreaker.onFailure("vendor-a");
        circuitBreaker.allowCall("vendor-a");
        circuitBreaker.onFailure("vendor-a");
        assertFalse(circuitBreaker.allowCall("vendor-a"));

        Thread.sleep(1100); // 冷却结束 → half-open 允许探测

        assertTrue(circuitBreaker.allowCall("vendor-a"));
    }

    @Test
    void forceOpenAlwaysRejects() {
        saveConfig("vendor-a", new CircuitBreakerSettings(CircuitBreakerMode.FORCE_OPEN, 50, 4, 60, 1));

        assertFalse(circuitBreaker.allowCall("vendor-a"));
        circuitBreaker.onSuccess("vendor-a"); // 手动模式不计结果
        assertFalse(circuitBreaker.allowCall("vendor-a"));
    }

    @Test
    void forceClosedAlwaysPermits() {
        saveConfig("vendor-a", new CircuitBreakerSettings(CircuitBreakerMode.FORCE_CLOSED, 50, 4, 60, 1));

        for (int i = 0; i < 10; i++) {
            assertTrue(circuitBreaker.allowCall("vendor-a"));
            circuitBreaker.onFailure("vendor-a"); // 手动模式不计失败
        }
        assertTrue(circuitBreaker.allowCall("vendor-a"));
    }
}
