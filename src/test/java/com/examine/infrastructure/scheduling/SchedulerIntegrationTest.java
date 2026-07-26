package com.examine.infrastructure.scheduling;

import com.examine.application.StaleLockRecoveryAppService;
import com.examine.domain.model.NotificationRequest;
import com.examine.domain.model.Status;
import com.examine.domain.model.config.CircuitBreakerMode;
import com.examine.domain.model.config.CircuitBreakerSettings;
import com.examine.domain.model.config.HttpMethod;
import com.examine.domain.model.config.IdempotencyKeyLocation;
import com.examine.domain.model.config.RateLimitSettings;
import com.examine.domain.model.config.RetryPolicySettings;
import com.examine.domain.model.config.VendorConfig;
import com.examine.domain.repository.NotificationRequestRepository;
import com.examine.domain.repository.VendorConfigRepository;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class SchedulerIntegrationTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Autowired
    private DeliveryScheduler deliveryScheduler;

    @Autowired
    private StaleLockRecoveryAppService staleLockRecoveryAppService;

    @Autowired
    private NotificationRequestRepository requestRepository;

    @Autowired
    private VendorConfigRepository vendorConfigRepository;

    private void saveVendorConfig(String vendorKey) {
        vendorConfigRepository.save(new VendorConfig(
                vendorKey, wireMock.baseUrl() + "/notify", HttpMethod.POST,
                Map.of("Content-Type", "application/json"),
                "{\"msg\":\"{{msg}}\"}", Duration.ofSeconds(5),
                new RetryPolicySettings(10, Duration.ofMillis(100), Duration.ofSeconds(30)),
                new RateLimitSettings(100, 100),
                new CircuitBreakerSettings(CircuitBreakerMode.AUTO, 50, 10, 60, 3),
                IdempotencyKeyLocation.HEADER, "Idempotency-Key"));
    }

    @Test
    void pollDispatchesPendingRequestToVendor() {
        saveVendorConfig("vendor-it");
        wireMock.stubFor(post("/notify").willReturn(aResponse().withStatus(200)));
        Instant now = Instant.now();
        NotificationRequest request = NotificationRequest.create(
                "req-it-1", "vendor-it", "idem-it-1", "{\"msg\":\"hi\"}", now);
        requestRepository.save(request);

        deliveryScheduler.poll();

        await().atMost(Duration.ofSeconds(10)).until(() ->
                requestRepository.findById("req-it-1")
                        .map(r -> r.getStatus() == Status.SUCCESS)
                        .orElse(false));
        wireMock.verify(postRequestedFor(urlEqualTo("/notify"))
                .withHeader("Idempotency-Key", equalTo("idem-it-1"))
                .withRequestBody(equalToJson("{\"msg\":\"hi\"}")));
    }

    @Test
    void recoverStaleMarksExpiredSendingAsFailed() {
        saveVendorConfig("vendor-it");
        Instant now = Instant.now();
        NotificationRequest request = NotificationRequest.create(
                "req-it-2", "vendor-it", "idem-it-2", "{}", now.minusSeconds(120));
        // 锁已过期（lockedUntil 在过去）
        request.markSending("dead-worker", now.minusSeconds(60), now.minusSeconds(120));
        requestRepository.save(request);

        staleLockRecoveryAppService.recoverStale(10);

        NotificationRequest recovered = requestRepository.findById("req-it-2").orElseThrow();
        assertEquals(Status.FAILED, recovered.getStatus());
        assertEquals(1, recovered.getAttemptCount());
        assertEquals(NotificationRequest.UNLOCKED, recovered.getLockedUntil());
        assertTrue(recovered.getNextRetryAt().isAfter(Instant.now().minusSeconds(1)));
    }
}
