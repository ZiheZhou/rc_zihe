package com.examine.domain.policy;

import java.time.Duration;
import java.util.Random;

public class EqualJitterStrategy implements JitterStrategy {

    private final Random random;

    public EqualJitterStrategy(Random random) {
        this.random = random;
    }

    @Override
    public Duration apply(Duration calculatedDelay) {
        long half = calculatedDelay.toMillis() / 2;
        long jittered = half + random.nextLong(half + 1);
        return Duration.ofMillis(jittered);
    }
}
