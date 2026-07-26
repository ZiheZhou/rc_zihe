package com.examine.infrastructure.alert;

import com.examine.domain.service.AlertService;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.Executor;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class WebhookAlertServiceTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private static final Instant NOW = Instant.parse("2026-07-26T10:00:00Z");
    private final Executor directExecutor = Runnable::run;

    private WebhookAlertService service(String url, Duration cooldown) {
        return new WebhookAlertService(url, cooldown, Clock.fixed(NOW, ZoneOffset.UTC), directExecutor);
    }

    @Test
    void sendsWebhookPostWithAlertPayload() {
        wireMock.stubFor(post("/alert").willReturn(aResponse().withStatus(200)));
        WebhookAlertService service = service(wireMock.baseUrl() + "/alert", Duration.ofMinutes(5));

        service.notifyDeadLetter("req-1", "vendor-a", "vendor returned 400");

        wireMock.verify(postRequestedFor(urlEqualTo("/alert"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(matchingJsonPath("$.type", equalTo("DEAD_LETTER")))
                .withRequestBody(matchingJsonPath("$.vendorKey", equalTo("vendor-a")))
                .withRequestBody(matchingJsonPath("$.requestId", equalTo("req-1")))
                .withRequestBody(matchingJsonPath("$.errorSummary", equalTo("vendor returned 400")))
                .withRequestBody(matchingJsonPath("$.suggestedAction")));
    }

    @Test
    void blankUrlFallsBackToErrorLogWithoutThrowing() {
        WebhookAlertService service = service("", Duration.ofMinutes(5));

        assertDoesNotThrow(() -> service.notifyDeadLetter("req-1", "vendor-a", "boom"));
        assertDoesNotThrow(() -> service.notifyVendorUnhealthy("vendor-a", "circuit opened"));
    }

    @Test
    void sameTypeAndVendorWithinCooldownIsConverged() {
        wireMock.stubFor(post("/alert").willReturn(aResponse().withStatus(200)));
        WebhookAlertService service = service(wireMock.baseUrl() + "/alert", Duration.ofMinutes(5));

        service.notifyDeadLetter("req-1", "vendor-a", "err1");
        service.notifyDeadLetter("req-2", "vendor-a", "err2"); // 同 type+vendor，收敛

        wireMock.verify(1, postRequestedFor(urlEqualTo("/alert")));
    }

    @Test
    void differentEventTypesAreNotConvergedTogether() {
        wireMock.stubFor(post("/alert").willReturn(aResponse().withStatus(200)));
        WebhookAlertService service = service(wireMock.baseUrl() + "/alert", Duration.ofMinutes(5));

        service.notifyDeadLetter("req-1", "vendor-a", "err");
        service.notifyVendorUnhealthy("vendor-a", "circuit opened"); // 不同 type，不收敛

        wireMock.verify(2, postRequestedFor(urlEqualTo("/alert")));
    }

    @Test
    void sendsAgainAfterCooldownElapsed() {
        wireMock.stubFor(post("/alert").willReturn(aResponse().withStatus(200)));
        MutableClock clock = new MutableClock(NOW);
        WebhookAlertService service = new WebhookAlertService(
                wireMock.baseUrl() + "/alert", Duration.ofMinutes(5), clock, directExecutor);

        service.notifyDeadLetter("req-1", "vendor-a", "err1");
        clock.advance(Duration.ofMinutes(6));
        service.notifyDeadLetter("req-2", "vendor-a", "err2");

        wireMock.verify(2, postRequestedFor(urlEqualTo("/alert")));
    }

    @Test
    void webhookFailureDoesNotPropagate() {
        wireMock.stubFor(post("/alert").willReturn(aResponse().withStatus(500)));
        WebhookAlertService service = service(wireMock.baseUrl() + "/alert", Duration.ofMinutes(5));

        assertDoesNotThrow(() -> service.notifyDeadLetter("req-1", "vendor-a", "err"));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
