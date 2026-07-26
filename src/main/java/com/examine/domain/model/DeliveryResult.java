package com.examine.domain.model;

import java.time.Duration;
import java.util.Optional;

public sealed interface DeliveryResult {

    record Success(int statusCode) implements DeliveryResult {}

    record RetryableFailure(String reason, Optional<Duration> retryAfter) implements DeliveryResult {}

    record NonRetryableFailure(String reason) implements DeliveryResult {}

    record RateLimited(Optional<Duration> retryAfter) implements DeliveryResult {}
}
