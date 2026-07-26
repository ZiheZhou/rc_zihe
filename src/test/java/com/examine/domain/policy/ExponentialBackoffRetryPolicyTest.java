package com.examine.domain.policy;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ExponentialBackoffRetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-07-26T10:00:00Z");
    private static final Duration BASE = Duration.ofSeconds(1);
    private static final Duration MAX = Duration.ofMinutes(10);

    private final JitterStrategy jitter = new EqualJitterStrategy(new Random(42));
    private final RetryPolicy policy = new ExponentialBackoffRetryPolicy(BASE, MAX, 10, jitter);

    @Test
    void hintTakesPrecedenceOverBackoff() {
        Duration hint = Duration.ofSeconds(30);
        Instant next = policy.calculateNextRetry(1, NOW, Optional.of(hint));
        assertEquals(NOW.plus(hint), next);
    }

    @Test
    void firstRetryDelayWithinEqualJitterRange() {
        Instant next = policy.calculateNextRetry(1, NOW, Optional.empty());
        Duration actual = Duration.between(NOW, next);
        // equal jitter: [base/2, base]
        assertTrue(actual.compareTo(BASE.dividedBy(2)) >= 0,
                "actual " + actual + " should be >= " + BASE.dividedBy(2));
        assertTrue(actual.compareTo(BASE) <= 0,
                "actual " + actual + " should be <= " + BASE);
    }

    @Test
    void thirdRetryDelayWithinExpectedRange() {
        // attemptCount=3 -> exponent=2 -> base*4
        Duration expectedBase = BASE.multipliedBy(4);
        Instant next = policy.calculateNextRetry(3, NOW, Optional.empty());
        Duration actual = Duration.between(NOW, next);
        assertTrue(actual.compareTo(expectedBase.dividedBy(2)) >= 0);
        assertTrue(actual.compareTo(expectedBase) <= 0);
    }

    @Test
    void delayCappedAtMaxDelay() {
        // attemptCount=20 should hit cap
        Instant next = policy.calculateNextRetry(20, NOW, Optional.empty());
        Duration actual = Duration.between(NOW, next);
        assertTrue(actual.compareTo(MAX) <= 0,
                "actual " + actual + " should be <= " + MAX);
    }

    @Test
    void allowRetryRespectsMaxAttempts() {
        assertTrue(policy.allowRetry(0));
        assertTrue(policy.allowRetry(9));
        assertFalse(policy.allowRetry(10));
        assertFalse(policy.allowRetry(11));
    }

    @Test
    void maxAttemptsReturnsConfiguredValue() {
        assertEquals(10, policy.maxAttempts());
    }
}
