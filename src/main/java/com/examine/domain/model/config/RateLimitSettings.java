package com.examine.domain.model.config;

public record RateLimitSettings(int qps, int burst) {
}
