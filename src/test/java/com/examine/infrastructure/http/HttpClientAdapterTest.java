package com.examine.infrastructure.http;

import com.examine.domain.model.HttpOutcome;
import com.examine.domain.model.VendorHttpRequest;
import com.examine.domain.model.config.HttpMethod;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

class HttpClientAdapterTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private final HttpClientAdapter adapter = new HttpClientAdapter();

    private VendorHttpRequest postRequest(String path, Duration timeout) {
        return new VendorHttpRequest(
                wireMock.baseUrl() + path,
                HttpMethod.POST,
                Map.of("Content-Type", "application/json", "Idempotency-Key", "idem-1"),
                "{\"msg\":\"hi\"}",
                timeout);
    }

    @Test
    void successfulPostReturnsStatusAndHeaders() {
        wireMock.stubFor(post("/ok").willReturn(aResponse()
                .withStatus(200)
                .withHeader("Retry-After", "5")
                .withBody("done")));

        HttpOutcome outcome = adapter.send(postRequest("/ok", Duration.ofSeconds(5)));

        assertEquals(200, outcome.statusCode());
        assertNull(outcome.error());
        assertEquals("5", outcome.headers().get("retry-after"));
    }

    @Test
    void serverErrorReturnsStatusWithoutException() {
        wireMock.stubFor(post("/err").willReturn(aResponse().withStatus(500)));

        HttpOutcome outcome = adapter.send(postRequest("/err", Duration.ofSeconds(5)));

        assertEquals(500, outcome.statusCode());
        assertNull(outcome.error());
    }

    @Test
    void slowResponseProducesTimeoutFailure() {
        wireMock.stubFor(post("/slow").willReturn(aResponse()
                .withStatus(200)
                .withFixedDelay(3000)));

        HttpOutcome outcome = adapter.send(postRequest("/slow", Duration.ofMillis(500)));

        assertNull(outcome.statusCode());
        assertNotNull(outcome.error());
    }

    @Test
    void unreachableHostProducesFailure() {
        VendorHttpRequest request = new VendorHttpRequest(
                "http://localhost:1/unreachable", HttpMethod.POST,
                Map.of(), "{}", Duration.ofSeconds(2));

        HttpOutcome outcome = adapter.send(request);

        assertNull(outcome.statusCode());
        assertNotNull(outcome.error());
    }

    @Test
    void sendsConfiguredMethodHeadersAndBody() {
        wireMock.stubFor(put("/verify").willReturn(aResponse().withStatus(204)));

        VendorHttpRequest request = new VendorHttpRequest(
                wireMock.baseUrl() + "/verify", HttpMethod.PUT,
                Map.of("X-Custom", "v1"), "{\"a\":1}", Duration.ofSeconds(5));
        HttpOutcome outcome = adapter.send(request);

        assertEquals(204, outcome.statusCode());
        wireMock.verify(putRequestedFor(urlEqualTo("/verify"))
                .withHeader("X-Custom", equalTo("v1"))
                .withRequestBody(equalToJson("{\"a\":1}")));
    }
}
