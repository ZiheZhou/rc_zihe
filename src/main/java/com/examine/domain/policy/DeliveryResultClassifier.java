package com.examine.domain.policy;

import com.examine.domain.model.DeliveryResult;
import com.examine.domain.model.HttpOutcome;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

public class DeliveryResultClassifier {

    public DeliveryResult classify(HttpOutcome outcome) {
        if (outcome.error() != null) {
            return classifyException(outcome.error());
        }
        return classifyStatusCode(outcome.statusCode(), outcome.headers());
    }

    private DeliveryResult classifyException(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.net.ConnectException
                    || cause instanceof java.net.http.HttpTimeoutException
                    || cause instanceof java.io.IOException) {
                return new DeliveryResult.RetryableFailure("network error: " + cause.getMessage(), Optional.empty());
            }
            cause = cause.getCause();
        }
        return new DeliveryResult.NonRetryableFailure("non-retryable error: " + error.getMessage());
    }

    private DeliveryResult classifyStatusCode(int statusCode, Map<String, String> headers) {
        if (statusCode >= 200 && statusCode < 300) {
            return new DeliveryResult.Success(statusCode);
        }
        if (statusCode == 429) {
            return new DeliveryResult.RateLimited(parseRetryAfter(headers));
        }
        if (statusCode >= 500) {
            return new DeliveryResult.RetryableFailure("server error: " + statusCode, Optional.empty());
        }
        return new DeliveryResult.NonRetryableFailure("client error: " + statusCode);
    }

    private Optional<Duration> parseRetryAfter(Map<String, String> headers) {
        String value = headers.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase("retry-after"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Duration.ofSeconds(Long.parseLong(value.trim())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
