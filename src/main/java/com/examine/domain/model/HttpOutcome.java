package com.examine.domain.model;

import java.util.Map;

public record HttpOutcome(Integer statusCode, Map<String, String> headers, Throwable error, long durationMs) {

    public static HttpOutcome response(int statusCode, Map<String, String> headers, long durationMs) {
        return new HttpOutcome(statusCode, headers, null, durationMs);
    }

    public static HttpOutcome failure(Throwable error, long durationMs) {
        return new HttpOutcome(null, Map.of(), error, durationMs);
    }
}
