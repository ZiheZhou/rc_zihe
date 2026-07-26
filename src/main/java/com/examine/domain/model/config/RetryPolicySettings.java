package com.examine.domain.model.config;

import java.time.Duration;

public record RetryPolicySettings(int maxAttempts, Duration baseDelay, Duration maxDelay) {
}
