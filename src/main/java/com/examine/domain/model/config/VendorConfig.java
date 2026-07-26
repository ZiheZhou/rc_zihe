package com.examine.domain.model.config;

import java.time.Duration;
import java.util.Map;

public record VendorConfig(
        String vendorKey,
        String endpoint,
        HttpMethod method,
        Map<String, String> headers,
        String bodyTemplate,
        Duration timeout,
        RetryPolicySettings retryPolicy,
        RateLimitSettings rateLimit,
        CircuitBreakerSettings circuitBreaker,
        IdempotencyKeyLocation idempotencyKeyLocation,
        String idempotencyKeyName
) {
}
