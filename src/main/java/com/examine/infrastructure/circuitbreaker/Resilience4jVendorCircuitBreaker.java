package com.examine.infrastructure.circuitbreaker;

import com.examine.domain.model.config.CircuitBreakerMode;
import com.examine.domain.model.config.CircuitBreakerSettings;
import com.examine.domain.model.config.VendorConfig;
import com.examine.domain.service.VendorCircuitBreaker;
import com.examine.infrastructure.config.VendorConfigCache;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * per-vendor 熔断器（Resilience4j）。
 * AUTO：走 Resilience4j 状态机（closed → open → half-open）；
 * FORCE_OPEN：恒拒绝（不计结果）；FORCE_CLOSED：恒放行（不计结果）。
 * 配置变更时自动重建熔断器实例。
 */
@Component
public class Resilience4jVendorCircuitBreaker implements VendorCircuitBreaker {

    private final VendorConfigCache configCache;
    private final ConcurrentHashMap<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CircuitBreakerSettings> breakerSettings = new ConcurrentHashMap<>();

    public Resilience4jVendorCircuitBreaker(VendorConfigCache configCache) {
        this.configCache = configCache;
    }

    @Override
    public boolean allowCall(String vendorKey) {
        CircuitBreakerSettings settings = settingsOf(vendorKey);
        if (settings == null) {
            return true;
        }
        return switch (settings.mode()) {
            case FORCE_OPEN -> false;
            case FORCE_CLOSED -> true;
            case AUTO -> breakerFor(vendorKey, settings).tryAcquirePermission();
        };
    }

    @Override
    public void onSuccess(String vendorKey) {
        CircuitBreakerSettings settings = settingsOf(vendorKey);
        if (settings != null && settings.mode() == CircuitBreakerMode.AUTO) {
            breakerFor(vendorKey, settings).onSuccess(0, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public void onFailure(String vendorKey) {
        CircuitBreakerSettings settings = settingsOf(vendorKey);
        if (settings != null && settings.mode() == CircuitBreakerMode.AUTO) {
            breakerFor(vendorKey, settings).onError(0, java.util.concurrent.TimeUnit.NANOSECONDS, new RuntimeException("vendor call failed"));
        }
    }

    private CircuitBreakerSettings settingsOf(String vendorKey) {
        Optional<VendorConfig> config = configCache.get(vendorKey);
        return config.map(VendorConfig::circuitBreaker).orElse(null);
    }

    private CircuitBreaker breakerFor(String vendorKey, CircuitBreakerSettings settings) {
        return breakers.compute(vendorKey, (key, existing) -> {
            if (existing == null || !settings.equals(breakerSettings.get(key))) {
                breakerSettings.put(key, settings);
                return CircuitBreaker.of(key, toResilience4jConfig(settings));
            }
            return existing;
        });
    }

    private CircuitBreakerConfig toResilience4jConfig(CircuitBreakerSettings settings) {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(settings.failureRateThreshold())
                .minimumNumberOfCalls(settings.minCalls())
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(settings.minCalls())
                .waitDurationInOpenState(Duration.ofSeconds(settings.cooldownSeconds()))
                .permittedNumberOfCallsInHalfOpenState(settings.halfOpenMaxCalls())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
    }
}
