package com.examine.api.dto;

import com.examine.domain.model.config.CircuitBreakerMode;
import com.examine.domain.model.config.CircuitBreakerSettings;
import com.examine.domain.model.config.HttpMethod;
import com.examine.domain.model.config.IdempotencyKeyLocation;
import com.examine.domain.model.config.RateLimitSettings;
import com.examine.domain.model.config.RetryPolicySettings;
import com.examine.domain.model.config.VendorConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Duration;
import java.util.Map;

public record VendorConfigRequest(
        @NotBlank String vendorKey,
        @NotBlank String endpoint,
        @NotNull HttpMethod method,
        Map<String, String> headers,
        String bodyTemplate,
        @Min(1) long timeoutMs,
        @NotNull @Valid RetryPolicyDto retryPolicy,
        @NotNull @Valid RateLimitDto rateLimit,
        @NotNull @Valid CircuitBreakerDto circuitBreaker,
        @NotNull IdempotencyKeyLocation idempotencyKeyLocation,
        @NotBlank String idempotencyKeyName) {

    public record RetryPolicyDto(@Min(1) int maxAttempts, @Min(1) long baseDelayMs, @Min(1) long maxDelayMs) {
        RetryPolicySettings toDomain() {
            return new RetryPolicySettings(maxAttempts, Duration.ofMillis(baseDelayMs), Duration.ofMillis(maxDelayMs));
        }
    }

    public record RateLimitDto(@Min(1) int qps, @Min(1) int burst) {
        RateLimitSettings toDomain() {
            return new RateLimitSettings(qps, burst);
        }
    }

    public record CircuitBreakerDto(@NotNull CircuitBreakerMode mode, @Min(1) int failureRateThreshold,
                                    @Min(1) int minCalls, @Min(1) int cooldownSeconds, @Min(1) int halfOpenMaxCalls) {
        CircuitBreakerSettings toDomain() {
            return new CircuitBreakerSettings(mode, failureRateThreshold, minCalls, cooldownSeconds, halfOpenMaxCalls);
        }
    }

    public VendorConfig toDomain() {
        return new VendorConfig(
                vendorKey, endpoint, method,
                headers == null ? Map.of() : headers,
                bodyTemplate,
                Duration.ofMillis(timeoutMs),
                retryPolicy.toDomain(),
                rateLimit.toDomain(),
                circuitBreaker.toDomain(),
                idempotencyKeyLocation,
                idempotencyKeyName);
    }
}
