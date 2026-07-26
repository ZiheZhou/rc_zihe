package com.examine.application;

import com.examine.domain.model.HttpOutcome;
import com.examine.domain.model.IdempotencyStatus;
import com.examine.domain.model.NotificationRequest;
import com.examine.domain.model.Status;
import com.examine.domain.model.config.CircuitBreakerMode;
import com.examine.domain.model.config.CircuitBreakerSettings;
import com.examine.domain.model.config.HttpMethod;
import com.examine.domain.model.config.IdempotencyKeyLocation;
import com.examine.domain.model.config.RateLimitSettings;
import com.examine.domain.model.config.RetryPolicySettings;
import com.examine.domain.model.config.VendorConfig;
import com.examine.domain.policy.DeliveryResultClassifier;
import com.examine.domain.repository.IdempotencyRecordRepository;
import com.examine.domain.repository.NotificationRequestRepository;
import com.examine.domain.service.AlertService;
import com.examine.domain.service.RateLimiter;
import com.examine.domain.service.VendorCircuitBreaker;
import com.examine.domain.service.VendorRequestAssembler;
import com.examine.infrastructure.config.VendorConfigCache;
import com.examine.infrastructure.http.HttpClientAdapter;
import com.examine.infrastructure.metrics.NotificationMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryAppServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-26T10:00:00Z");

    @Mock NotificationRequestRepository requestRepository;
    @Mock IdempotencyRecordRepository idempotencyRepository;
    @Mock VendorConfigCache configCache;
    @Mock RateLimiter rateLimiter;
    @Mock VendorCircuitBreaker circuitBreaker;
    @Mock HttpClientAdapter httpClient;
    @Mock AlertService alertService;
    @Mock NotificationMetrics metrics;

    private DeliveryAppService service;

    @BeforeEach
    void setUp() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        lenient().when(txManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        service = new DeliveryAppService(
                requestRepository, idempotencyRepository, configCache, rateLimiter, circuitBreaker,
                new VendorRequestAssembler(), httpClient, new DeliveryResultClassifier(),
                alertService, metrics, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                txManager, 60_000);
    }

    private VendorConfig configWithMaxAttempts(int maxAttempts) {
        return new VendorConfig(
                "vendor-a", "https://api.vendor-a.com/notify", HttpMethod.POST, Map.of(),
                "{\"msg\":\"{{msg}}\"}", Duration.ofSeconds(30),
                new RetryPolicySettings(maxAttempts, Duration.ofSeconds(1), Duration.ofHours(1)),
                new RateLimitSettings(100, 100),
                new CircuitBreakerSettings(CircuitBreakerMode.AUTO, 50, 10, 60, 3),
                IdempotencyKeyLocation.HEADER, "Idempotency-Key");
    }

    /** 放行到 HTTP 调用前的全部前置检查 */
    private void arrangeReachHttp(NotificationRequest request, VendorConfig config) {
        when(requestRepository.acquireLock(eq(request.getId()), anyString(), any(), eq(NOW))).thenReturn(true);
        when(requestRepository.findById(request.getId())).thenReturn(Optional.of(request));
        when(configCache.get("vendor-a")).thenReturn(Optional.of(config));
        when(rateLimiter.tryAcquire("vendor-a")).thenReturn(true);
        when(circuitBreaker.allowCall("vendor-a")).thenReturn(true);
    }

    @Test
    void successMarksDeliveredAndSyncsIdempotencyAndCircuitBreaker() {
        NotificationRequest request = NotificationRequest.create("req-1", "vendor-a", "idem-1", "{\"msg\":\"hi\"}", NOW);
        arrangeReachHttp(request, configWithMaxAttempts(10));
        when(httpClient.send(any())).thenReturn(HttpOutcome.response(200, Map.of()));

        assertTrue(service.tryDispatch("req-1"));

        assertEquals(Status.SUCCESS, request.getStatus());
        assertEquals(NOW, request.getDeliveredAt());
        assertEquals(NotificationRequest.UNLOCKED, request.getLockedUntil());
        verify(requestRepository).update(request);
        verify(idempotencyRepository).updateStatus("vendor-a", "idem-1", IdempotencyStatus.SUCCESS);
        verify(circuitBreaker).onSuccess("vendor-a");
        verify(metrics).incrementDelivered();
    }

    @Test
    void retryableFailureSchedulesNextRetryWithAttemptIncremented() {
        NotificationRequest request = NotificationRequest.create("req-1", "vendor-a", "idem-1", "{}", NOW);
        arrangeReachHttp(request, configWithMaxAttempts(10));
        when(httpClient.send(any())).thenReturn(HttpOutcome.response(500, Map.of()));

        assertTrue(service.tryDispatch("req-1"));

        assertEquals(Status.FAILED, request.getStatus());
        assertEquals(1, request.getAttemptCount());
        assertTrue(request.getNextRetryAt().isAfter(NOW));
        assertTrue(request.getNextRetryAt().isBefore(NOW.plusSeconds(3))); // base 1s + jitter ≤ 2s
        verify(idempotencyRepository).updateStatus("vendor-a", "idem-1", IdempotencyStatus.FAILED);
        verify(circuitBreaker).onFailure("vendor-a");
        verify(metrics).incrementFailed();
    }

    @Test
    void exhaustedAttemptsGoesToDeadLetterWithAlert() {
        NotificationRequest request = NotificationRequest.restore("req-1", "vendor-a", "idem-1", "{}",
                Status.PENDING, 2, NOW, null, NotificationRequest.UNLOCKED, NOW, NOW, null, null);
        arrangeReachHttp(request, configWithMaxAttempts(3));
        when(httpClient.send(any())).thenReturn(HttpOutcome.response(500, Map.of()));

        assertTrue(service.tryDispatch("req-1"));

        assertEquals(Status.DEAD_LETTERED, request.getStatus());
        assertEquals(2, request.getAttemptCount()); // DLQ 不再增加 attempt
        verify(idempotencyRepository).updateStatus("vendor-a", "idem-1", IdempotencyStatus.DEAD_LETTERED);
        verify(alertService).notifyDeadLetter(eq("req-1"), eq("vendor-a"), anyString());
        verify(metrics).incrementDeadLettered();
    }

    @Test
    void nonRetryableFailureGoesStraightToDeadLetter() {
        NotificationRequest request = NotificationRequest.create("req-1", "vendor-a", "idem-1", "{}", NOW);
        arrangeReachHttp(request, configWithMaxAttempts(10));
        when(httpClient.send(any())).thenReturn(HttpOutcome.response(400, Map.of()));

        assertTrue(service.tryDispatch("req-1"));

        assertEquals(Status.DEAD_LETTERED, request.getStatus());
        assertEquals(0, request.getAttemptCount()); // 不可重试不消耗 attempt
        verify(alertService).notifyDeadLetter(eq("req-1"), eq("vendor-a"), contains("400"));
        verify(metrics).incrementDeadLettered();
    }

    @Test
    void rateLimitedUsesRetryAfterHint() {
        NotificationRequest request = NotificationRequest.create("req-1", "vendor-a", "idem-1", "{}", NOW);
        arrangeReachHttp(request, configWithMaxAttempts(10));
        when(httpClient.send(any())).thenReturn(HttpOutcome.response(429, Map.of("retry-after", "30")));

        assertTrue(service.tryDispatch("req-1"));

        assertEquals(Status.FAILED, request.getStatus());
        assertEquals(NOW.plusSeconds(30), request.getNextRetryAt());
    }

    @Test
    void rateLimiterRejectionReschedulesWithoutAttemptIncrement() {
        NotificationRequest request = NotificationRequest.create("req-1", "vendor-a", "idem-1", "{}", NOW);
        when(requestRepository.acquireLock(eq("req-1"), anyString(), any(), eq(NOW))).thenReturn(true);
        when(requestRepository.findById("req-1")).thenReturn(Optional.of(request));
        when(configCache.get("vendor-a")).thenReturn(Optional.of(configWithMaxAttempts(10)));
        when(rateLimiter.tryAcquire("vendor-a")).thenReturn(false);

        assertTrue(service.tryDispatch("req-1"));

        assertEquals(Status.PENDING, request.getStatus());
        assertEquals(0, request.getAttemptCount());
        assertEquals(NOW.plusMillis(500), request.getNextRetryAt());
        verifyNoInteractions(httpClient);
        verify(requestRepository).update(request);
    }

    @Test
    void openCircuitBreakerReschedulesByCooldown() {
        NotificationRequest request = NotificationRequest.create("req-1", "vendor-a", "idem-1", "{}", NOW);
        when(requestRepository.acquireLock(eq("req-1"), anyString(), any(), eq(NOW))).thenReturn(true);
        when(requestRepository.findById("req-1")).thenReturn(Optional.of(request));
        when(configCache.get("vendor-a")).thenReturn(Optional.of(configWithMaxAttempts(10)));
        when(rateLimiter.tryAcquire("vendor-a")).thenReturn(true);
        when(circuitBreaker.allowCall("vendor-a")).thenReturn(false);

        assertTrue(service.tryDispatch("req-1"));

        assertEquals(Status.PENDING, request.getStatus());
        assertEquals(0, request.getAttemptCount());
        assertEquals(NOW.plusSeconds(60), request.getNextRetryAt()); // cooldownSeconds=60
        verifyNoInteractions(httpClient);
    }

    @Test
    void failedLockAcquisitionReturnsFalse() {
        when(requestRepository.acquireLock(eq("req-1"), anyString(), any(), eq(NOW))).thenReturn(false);

        assertFalse(service.tryDispatch("req-1"));

        verify(requestRepository, never()).findById(anyString());
        verifyNoInteractions(httpClient, alertService);
    }

    @Test
    void missingVendorConfigGoesToDeadLetter() {
        NotificationRequest request = NotificationRequest.create("req-1", "vendor-a", "idem-1", "{}", NOW);
        when(requestRepository.acquireLock(eq("req-1"), anyString(), any(), eq(NOW))).thenReturn(true);
        when(requestRepository.findById("req-1")).thenReturn(Optional.of(request));
        when(configCache.get("vendor-a")).thenReturn(Optional.empty());

        assertTrue(service.tryDispatch("req-1"));

        assertEquals(Status.DEAD_LETTERED, request.getStatus());
        verify(alertService).notifyDeadLetter(eq("req-1"), eq("vendor-a"), contains("config"));
        verifyNoInteractions(httpClient);
    }
}
