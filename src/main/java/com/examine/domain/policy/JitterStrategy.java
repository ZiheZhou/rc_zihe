package com.examine.domain.policy;

import java.time.Duration;

public interface JitterStrategy {
    Duration apply(Duration calculatedDelay);
}
