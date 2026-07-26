package com.examine.domain.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public class ExponentialBackoffRetryPolicy implements RetryPolicy {

    private final Duration baseDelay;
    private final Duration maxDelay;
    private final int maxAttempts;
    private final JitterStrategy jitterStrategy;

    public ExponentialBackoffRetryPolicy(Duration baseDelay, Duration maxDelay, int maxAttempts,
                                          JitterStrategy jitterStrategy) {
        this.baseDelay = baseDelay;
        this.maxDelay = maxDelay;
        this.maxAttempts = maxAttempts;
        this.jitterStrategy = jitterStrategy;
    }

    @Override
    public boolean allowRetry(int attemptCount) {
        return attemptCount < maxAttempts;
    }

    @Override
    public Instant calculateNextRetry(int attemptCount, Instant now, Optional<Duration> hint) {
        if (hint.isPresent()) {
            return now.plus(hint.get());
        }
        long exponent = Math.max(0, attemptCount - 1L);
        long shift = Math.min(exponent, 62);
        long exponentialDelay = safeMultiply(baseDelay.toMillis(), 1L << shift);
        long cappedDelay = Math.min(exponentialDelay, maxDelay.toMillis());
        Duration jitteredDelay = jitterStrategy.apply(Duration.ofMillis(cappedDelay));
        return now.plus(jitteredDelay);
    }

    @Override
    public int maxAttempts() {
        return maxAttempts;
    }

    private long safeMultiply(long base, long multiplier) {
        try {
            return Math.multiplyExact(base, multiplier);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }
}
