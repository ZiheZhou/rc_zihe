package com.examine.domain.policy;

import com.examine.domain.model.DeliveryResult;
import com.examine.domain.model.HttpOutcome;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryResultClassifierTest {

    private final DeliveryResultClassifier classifier = new DeliveryResultClassifier();

    @Test
    void success2xx() {
        DeliveryResult result = classifier.classify(HttpOutcome.response(200, Map.of()));
        assertTrue(result instanceof DeliveryResult.Success);
        assertEquals(200, ((DeliveryResult.Success) result).statusCode());
    }

    @Test
    void rateLimited429WithRetryAfter() {
        DeliveryResult result = classifier.classify(HttpOutcome.response(429, Map.of("Retry-After", "10")));
        assertTrue(result instanceof DeliveryResult.RateLimited);
        assertEquals(Optional.of(Duration.ofSeconds(10)), ((DeliveryResult.RateLimited) result).retryAfter());
    }

    @Test
    void rateLimited429WithoutRetryAfter() {
        DeliveryResult result = classifier.classify(HttpOutcome.response(429, Map.of()));
        assertTrue(result instanceof DeliveryResult.RateLimited);
        assertEquals(Optional.empty(), ((DeliveryResult.RateLimited) result).retryAfter());
    }

    @Test
    void serverError5xxIsRetryable() {
        DeliveryResult result = classifier.classify(HttpOutcome.response(503, Map.of()));
        assertTrue(result instanceof DeliveryResult.RetryableFailure);
        assertEquals(Optional.empty(), ((DeliveryResult.RetryableFailure) result).retryAfter());
    }

    @Test
    void clientError4xxIsNonRetryable() {
        DeliveryResult result = classifier.classify(HttpOutcome.response(400, Map.of()));
        assertTrue(result instanceof DeliveryResult.NonRetryableFailure);
    }

    @Test
    void socketTimeoutIsRetryable() {
        DeliveryResult result = classifier.classify(HttpOutcome.failure(new SocketTimeoutException("timeout")));
        assertTrue(result instanceof DeliveryResult.RetryableFailure);
    }

    @Test
    void connectExceptionIsRetryable() {
        DeliveryResult result = classifier.classify(HttpOutcome.failure(new ConnectException("refused")));
        assertTrue(result instanceof DeliveryResult.RetryableFailure);
    }

    @Test
    void wrappedNetworkErrorIsRetryable() {
        RuntimeException wrapper = new RuntimeException("wrapper", new SocketTimeoutException("timeout"));
        DeliveryResult result = classifier.classify(HttpOutcome.failure(wrapper));
        assertTrue(result instanceof DeliveryResult.RetryableFailure);
    }

    @Test
    void nonRetryableException() {
        DeliveryResult result = classifier.classify(HttpOutcome.failure(new IllegalArgumentException("bad config")));
        assertTrue(result instanceof DeliveryResult.NonRetryableFailure);
    }
}
