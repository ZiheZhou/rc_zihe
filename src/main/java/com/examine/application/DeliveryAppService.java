package com.examine.application;

import com.examine.domain.model.DeliveryResult;
import com.examine.domain.model.HttpOutcome;
import com.examine.domain.model.IdempotencyStatus;
import com.examine.domain.model.NotificationRequest;
import com.examine.domain.model.VendorHttpRequest;
import com.examine.domain.model.config.VendorConfig;
import com.examine.domain.policy.DeliveryResultClassifier;
import com.examine.domain.policy.EqualJitterStrategy;
import com.examine.domain.policy.ExponentialBackoffRetryPolicy;
import com.examine.domain.policy.RetryPolicy;
import com.examine.domain.repository.IdempotencyRecordRepository;
import com.examine.domain.repository.NotificationRequestRepository;
import com.examine.domain.service.AlertService;
import com.examine.domain.service.RateLimiter;
import com.examine.domain.service.VendorCircuitBreaker;
import com.examine.domain.service.VendorRequestAssembler;
import com.examine.infrastructure.config.VendorConfigCache;
import com.examine.infrastructure.http.HttpClientAdapter;
import com.examine.infrastructure.metrics.NotificationMetrics;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * 投递编排（technical-design.md §7）：租约锁 → 限流 → 熔断 → 组装 → HTTP（事务外）→ 分类 → 落库。
 * 限流/熔断延迟：回 PENDING，只改 nextRetryAt，不增加 attemptCount。
 * 可重试失败：markFailed（attempt+1）；达到 maxAttempts 或不可重试 → DLQ + 幂等同步 + 告警。
 * 多写操作通过 TransactionTemplate 保证原子性（HTTP 调用在事务外）。
 */
@Service
public class DeliveryAppService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryAppService.class);
    private static final Duration RATE_LIMIT_RESCHEDULE_DELAY = Duration.ofMillis(500);

    private final NotificationRequestRepository requestRepository;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final VendorConfigCache configCache;
    private final RateLimiter rateLimiter;
    private final VendorCircuitBreaker circuitBreaker;
    private final VendorRequestAssembler assembler;
    private final HttpClientAdapter httpClient;
    private final DeliveryResultClassifier classifier;
    private final AlertService alertService;
    private final NotificationMetrics metrics;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate txTemplate;
    private final Duration leaseDuration;
    private final String workerId = "worker-" + UUID.randomUUID();

    public DeliveryAppService(NotificationRequestRepository requestRepository,
                              IdempotencyRecordRepository idempotencyRepository,
                              VendorConfigCache configCache,
                              RateLimiter rateLimiter,
                              VendorCircuitBreaker circuitBreaker,
                              VendorRequestAssembler assembler,
                              HttpClientAdapter httpClient,
                              DeliveryResultClassifier classifier,
                              AlertService alertService,
                              NotificationMetrics metrics,
                              ObjectMapper objectMapper,
                              Clock clock,
                              PlatformTransactionManager txManager,
                              @Value("${notification.lease-duration-ms:60000}") long leaseDurationMs) {
        this.requestRepository = requestRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.configCache = configCache;
        this.rateLimiter = rateLimiter;
        this.circuitBreaker = circuitBreaker;
        this.assembler = assembler;
        this.httpClient = httpClient;
        this.classifier = classifier;
        this.alertService = alertService;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(txManager);
        this.leaseDuration = Duration.ofMillis(leaseDurationMs);
    }

    /**
     * 尝试投递一条通知。返回 false 表示锁被其他 worker 持有（正常竞争，调用方跳过即可）。
     */
    public boolean tryDispatch(String requestId) {
        Instant now = clock.instant();
        if (!requestRepository.acquireLock(requestId, workerId, now.plus(leaseDuration), now)) {
            return false;
        }
        Optional<NotificationRequest> found = requestRepository.findById(requestId);
        if (found.isEmpty()) {
            log.warn("locked request {} not found, skipping", requestId);
            return false;
        }
        NotificationRequest request = found.get();
        String vendorKey = request.getVendorKey();

        Optional<VendorConfig> configOpt = configCache.get(vendorKey);
        if (configOpt.isEmpty()) {
            persistDeadLetter(request, "vendor config missing: " + vendorKey);
            return true;
        }
        VendorConfig config = configOpt.get();

        if (!rateLimiter.tryAcquire(vendorKey)) {
            reschedule(request, now.plus(RATE_LIMIT_RESCHEDULE_DELAY), "rate limited locally");
            return true;
        }
        if (!circuitBreaker.allowCall(vendorKey)) {
            reschedule(request, now.plusSeconds(config.circuitBreaker().cooldownSeconds()),
                    "circuit breaker open");
            return true;
        }

        // HTTP 调用在事务外
        VendorHttpRequest vendorRequest = assembler.assemble(
                request.getId(), request.getIdempotencyKey(), parsePayload(request.getPayload()), config);
        HttpOutcome outcome = httpClient.send(vendorRequest);
        DeliveryResult result = classifier.classify(outcome);

        switch (result) {
            case DeliveryResult.Success success -> persistSuccess(request);
            case DeliveryResult.RateLimited rateLimited ->
                    persistRetryOrDeadLetter(request, config, rateLimited.retryAfter(),
                            "vendor rate limited (429)");
            case DeliveryResult.RetryableFailure failure ->
                    persistRetryOrDeadLetter(request, config, failure.retryAfter(), failure.reason());
            case DeliveryResult.NonRetryableFailure failure -> {
                circuitBreaker.onFailure(vendorKey);
                persistDeadLetter(request, failure.reason());
            }
        }
        return true;
    }

    private void reschedule(NotificationRequest request, Instant nextRetryAt, String reason) {
        txTemplate.executeWithoutResult(tx -> {
            request.reschedule(nextRetryAt, clock.instant());
            requestRepository.update(request);
        });
        log.debug("request {} rescheduled to {} ({})", request.getId(), nextRetryAt, reason);
    }

    private void persistSuccess(NotificationRequest request) {
        txTemplate.executeWithoutResult(tx -> {
            request.markSuccess(clock.instant());
            requestRepository.update(request);
            idempotencyRepository.updateStatus(
                    request.getVendorKey(), request.getIdempotencyKey(), IdempotencyStatus.SUCCESS);
        });
        circuitBreaker.onSuccess(request.getVendorKey());
        metrics.incrementDelivered();
        logEvent("NOTIFICATION_DELIVERED", request, null);
    }

    private void persistRetryOrDeadLetter(NotificationRequest request, VendorConfig config,
                                          Optional<Duration> retryAfterHint, String reason) {
        RetryPolicy retryPolicy = new ExponentialBackoffRetryPolicy(
                config.retryPolicy().baseDelay(), config.retryPolicy().maxDelay(),
                config.retryPolicy().maxAttempts(), new EqualJitterStrategy(new Random()));
        int nextAttempt = request.getAttemptCount() + 1;
        circuitBreaker.onFailure(request.getVendorKey());
        if (!retryPolicy.allowRetry(nextAttempt)) {
            persistDeadLetter(request, "attempts exhausted (" + nextAttempt + "): " + reason);
            return;
        }
        Instant nextRetryAt = retryPolicy.calculateNextRetry(nextAttempt, clock.instant(), retryAfterHint);
        txTemplate.executeWithoutResult(tx -> {
            request.markFailed(nextRetryAt, reason, clock.instant());
            requestRepository.update(request);
            idempotencyRepository.updateStatus(
                    request.getVendorKey(), request.getIdempotencyKey(), IdempotencyStatus.FAILED);
        });
        metrics.incrementFailed();
    }

    private void persistDeadLetter(NotificationRequest request, String reason) {
        txTemplate.executeWithoutResult(tx -> {
            request.markDeadLettered(reason, clock.instant());
            requestRepository.update(request);
            idempotencyRepository.updateStatus(
                    request.getVendorKey(), request.getIdempotencyKey(), IdempotencyStatus.DEAD_LETTERED);
        });
        metrics.incrementDeadLettered();
        logEvent("NOTIFICATION_DEAD_LETTERED", request, reason);
        alertService.notifyDeadLetter(request.getId(), request.getVendorKey(), reason);
    }

    private Map<String, Object> parsePayload(String payload) {
        try {
            return objectMapper.readValue(payload, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("request {} payload is not valid JSON object, using empty map", payload, e);
            return Map.of();
        }
    }

    private void logEvent(String event, NotificationRequest request, String error) {
        try {
            MDC.put("event", event);
            MDC.put("requestId", request.getId());
            MDC.put("vendorKey", request.getVendorKey());
            MDC.put("idempotencyKey", request.getIdempotencyKey());
            if (error == null) {
                log.info("{}", event);
            } else {
                log.warn("{} error={}", event, error);
            }
        } finally {
            MDC.clear();
        }
    }
}
