package com.examine.e2e;

import com.examine.domain.model.Status;
import com.examine.infrastructure.persistence.IdempotencyRecordJpaRepository;
import com.examine.infrastructure.persistence.NotificationRequestJpaRepository;
import com.examine.infrastructure.persistence.VendorConfigJpaRepository;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端：真实 API 提交 → 调度投递 → WireMock vendor → 状态查询/管理端重放。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("e2e")
class NotificationFlowE2ETest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private NotificationRequestJpaRepository requestJpaRepository;

    @Autowired
    private IdempotencyRecordJpaRepository idempotencyJpaRepository;

    @Autowired
    private VendorConfigJpaRepository vendorConfigJpaRepository;

    @BeforeEach
    void cleanUp() {
        requestJpaRepository.deleteAll();
        idempotencyJpaRepository.deleteAll();
        vendorConfigJpaRepository.deleteAll();
        wireMock.resetAll();
    }

    private void createVendorConfig(String vendorKey) {
        String body = """
                {
                  "vendorKey": "%s",
                  "endpoint": "%s/notify",
                  "method": "POST",
                  "headers": {"Content-Type": "application/json"},
                  "bodyTemplate": "{\\"msg\\":\\"{{msg}}\\"}",
                  "timeoutMs": 5000,
                  "retryPolicy": {"maxAttempts": 5, "baseDelayMs": 100, "maxDelayMs": 5000},
                  "rateLimit": {"qps": 1000, "burst": 1000},
                  "circuitBreaker": {"mode": "AUTO", "failureRateThreshold": 80, "minCalls": 10, "cooldownSeconds": 60, "halfOpenMaxCalls": 3},
                  "idempotencyKeyLocation": "HEADER",
                  "idempotencyKeyName": "Idempotency-Key"
                }
                """.formatted(vendorKey, wireMock.baseUrl());
        var response = restTemplate.postForEntity("/admin/v1/vendor-configs", json(body), Map.class);
        assertEquals(200, response.getStatusCode().value());
    }

    private HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> submit(String vendorKey, String idempotencyKey) {
        String body = """
                {"vendorKey":"%s","idempotencyKey":"%s","payload":{"msg":"hello"}}
                """.formatted(vendorKey, idempotencyKey);
        var response = restTemplate.postForEntity("/api/v1/notifications", json(body), Map.class);
        assertTrue(response.getStatusCode().is2xxSuccessful());
        return response.getBody();
    }

    private String statusOf(String requestId) {
        var response = restTemplate.getForEntity("/api/v1/notifications/" + requestId, Map.class);
        assertEquals(200, response.getStatusCode().value());
        return (String) response.getBody().get("status");
    }

    private void awaitStatus(String requestId, Status expected, Duration timeout) {
        await().atMost(timeout).pollInterval(Duration.ofMillis(100))
                .until(() -> statusOf(requestId).equals(expected.name()));
    }

    @Test
    void scenario1_submitThenDelivered() {
        createVendorConfig("vendor-e2e-1");
        wireMock.stubFor(post("/notify").willReturn(aResponse().withStatus(200)));

        String requestId = (String) submit("vendor-e2e-1", "idem-e2e-1").get("requestId");

        awaitStatus(requestId, Status.SUCCESS, Duration.ofSeconds(10));
        wireMock.verify(1, postRequestedFor(urlEqualTo("/notify"))
                .withHeader("Idempotency-Key", equalTo("idem-e2e-1"))
                .withRequestBody(equalToJson("{\"msg\":\"hello\"}")));
    }

    @Test
    void scenario2_duplicateSubmissionDeliveredOnlyOnce() {
        createVendorConfig("vendor-e2e-2");
        wireMock.stubFor(post("/notify").willReturn(aResponse().withStatus(200)));

        String firstId = (String) submit("vendor-e2e-2", "idem-e2e-2").get("requestId");
        Map<String, Object> second = submit("vendor-e2e-2", "idem-e2e-2");

        assertEquals(firstId, second.get("requestId"));
        awaitStatus(firstId, Status.SUCCESS, Duration.ofSeconds(10));
        wireMock.verify(1, postRequestedFor(urlEqualTo("/notify")));
    }

    @Test
    void scenario3_retryAfterServerErrorThenSuccess() {
        createVendorConfig("vendor-e2e-3");
        wireMock.stubFor(post("/notify").inScenario("flaky")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("failed-once"));
        wireMock.stubFor(post("/notify").inScenario("flaky")
                .whenScenarioStateIs("failed-once")
                .willReturn(aResponse().withStatus(200)));

        String requestId = (String) submit("vendor-e2e-3", "idem-e2e-3").get("requestId");

        awaitStatus(requestId, Status.SUCCESS, Duration.ofSeconds(15));
        wireMock.verify(2, postRequestedFor(urlEqualTo("/notify")));
    }

    @Test
    void scenario4_clientErrorGoesToDlqThenReplaySucceeds() {
        createVendorConfig("vendor-e2e-4");
        wireMock.stubFor(post("/notify").willReturn(aResponse().withStatus(400)));

        String requestId = (String) submit("vendor-e2e-4", "idem-e2e-4").get("requestId");
        awaitStatus(requestId, Status.DEAD_LETTERED, Duration.ofSeconds(10));

        // 重复提交 DLQ 中的幂等键 → 409
        var dupResponse = restTemplate.postForEntity("/api/v1/notifications",
                json("{\"vendorKey\":\"vendor-e2e-4\",\"idempotencyKey\":\"idem-e2e-4\",\"payload\":{\"msg\":\"hello\"}}"),
                Map.class);
        assertEquals(409, dupResponse.getStatusCode().value());

        // vendor 修复 → 人工重放 → 成功
        wireMock.stubFor(post("/notify").willReturn(aResponse().withStatus(200)));
        var replayResponse = restTemplate.postForEntity(
                "/admin/v1/dead-letters/" + requestId + "/retry", null, Map.class);
        assertEquals(200, replayResponse.getStatusCode().value());

        awaitStatus(requestId, Status.SUCCESS, Duration.ofSeconds(10));
        wireMock.verify(2, postRequestedFor(urlEqualTo("/notify")));
    }

    @Test
    void scenario5_rateLimitedHonorsRetryAfter() {
        createVendorConfig("vendor-e2e-5");
        wireMock.stubFor(post("/notify").inScenario("throttled")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1"))
                .willSetStateTo("throttled-once"));
        wireMock.stubFor(post("/notify").inScenario("throttled")
                .whenScenarioStateIs("throttled-once")
                .willReturn(aResponse().withStatus(200)));

        String requestId = (String) submit("vendor-e2e-5", "idem-e2e-5").get("requestId");

        awaitStatus(requestId, Status.SUCCESS, Duration.ofSeconds(15));
        wireMock.verify(2, postRequestedFor(urlEqualTo("/notify")));
    }
}
