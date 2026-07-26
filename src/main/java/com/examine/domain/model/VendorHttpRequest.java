package com.examine.domain.model;

import com.examine.domain.model.config.HttpMethod;

import java.time.Duration;
import java.util.Map;

/**
 * 组装完成、可直接发给 vendor 的 HTTP 请求。
 */
public record VendorHttpRequest(String url, HttpMethod method, Map<String, String> headers,
                                String body, Duration timeout) {
}
