package com.examine.domain.model;

import java.util.Map;

public record HttpOutcome(Integer statusCode, Map<String, String> headers, Throwable error) {

    public static HttpOutcome response(int statusCode, Map<String, String> headers) {
        return new HttpOutcome(statusCode, headers, null);
    }

    public static HttpOutcome failure(Throwable error) {
        return new HttpOutcome(null, Map.of(), error);
    }
}
