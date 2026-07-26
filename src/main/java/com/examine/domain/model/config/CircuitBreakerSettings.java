package com.examine.domain.model.config;

public record CircuitBreakerSettings(CircuitBreakerMode mode, int failureRateThreshold, int minCalls,
                                      int cooldownSeconds, int halfOpenMaxCalls) {
}
