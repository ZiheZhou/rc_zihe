package com.examine.domain.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface RetryPolicy {
    boolean allowRetry(int attemptCount);
    Instant calculateNextRetry(int attemptCount, Instant now, Optional<Duration> hint);
    int maxAttempts();
}
