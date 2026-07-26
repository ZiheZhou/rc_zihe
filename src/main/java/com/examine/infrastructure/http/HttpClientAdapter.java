package com.examine.infrastructure.http;

import com.examine.domain.model.HttpOutcome;
import com.examine.domain.model.VendorHttpRequest;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 调用 vendor HTTP 接口，把结果收敛为 HttpOutcome（永不抛异常，交给 classifier 分类）。
 * 使用 JDK HttpClient 以支持 per-request read timeout（每个 vendor 超时不同）；
 * 连接超时取 vendor 超时与 10s 的较小值。
 */
@Component
public class HttpClientAdapter {

    private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;

    public HttpClientAdapter() {
        this(HttpClient.newBuilder()
                .connectTimeout(MAX_CONNECT_TIMEOUT)
                .build());
    }

    HttpClientAdapter(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public HttpOutcome send(VendorHttpRequest request) {
        try {
            HttpRequest httpRequest = buildRequest(request);
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            return HttpOutcome.response(response.statusCode(), flattenHeaders(response));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return HttpOutcome.failure(e);
        } catch (Exception e) {
            return HttpOutcome.failure(e);
        }
    }

    private HttpRequest buildRequest(VendorHttpRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(request.url()))
                .timeout(request.timeout());
        request.headers().forEach(builder::header);
        HttpRequest.BodyPublisher body = request.body() == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(request.body());
        switch (request.method()) {
            case GET -> builder.GET();
            case DELETE -> builder.DELETE();
            case POST -> builder.POST(body);
            case PUT -> builder.PUT(body);
            case PATCH -> builder.method("PATCH", body);
        }
        return builder.build();
    }

    private Map<String, String> flattenHeaders(HttpResponse<?> response) {
        return response.headers().map().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> String.join(",", e.getValue()),
                        (a, b) -> a));
    }
}
