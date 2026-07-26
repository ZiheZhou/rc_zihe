package com.examine.application;

import com.examine.domain.model.IdempotencyStatus;
import com.examine.domain.model.NotificationRequest;
import com.examine.domain.model.config.VendorConfig;
import com.examine.domain.policy.EqualJitterStrategy;
import com.examine.domain.policy.ExponentialBackoffRetryPolicy;
import com.examine.domain.policy.RetryPolicy;
import com.examine.domain.repository.IdempotencyRecordRepository;
import com.examine.domain.repository.NotificationRequestRepository;
import com.examine.domain.service.AlertService;
import com.examine.infrastructure.config.VendorConfigCache;
import com.examine.infrastructure.metrics.NotificationMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Random;

/**
 * 锁超时恢复（technical-design.md 8.1）：SENDING 且 lockedUntil 过期的记录
 * 视为投递失败一次（attemptCount+1，计算 nextRetryAt）；达上限进 DLQ + 告警。
 * 崩溃 worker 恢复后可能对同一记录二次投递，由 vendor 幂等键兜底。
 */
@Service
public class StaleLockRecoveryAppService {

    private static final Logger log = LoggerFactory.getLogger(StaleLockRecoveryAppService.class);

    private final NotificationRequestRepository requestRepository;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final VendorConfigCache configCache;
    private final AlertService alertService;
    private final NotificationMetrics metrics;
    private final Clock clock;
    private final TransactionTemplate txTemplate;

    public StaleLockRecoveryAppService(NotificationRequestRepository requestRepository,
                                       IdempotencyRecordRepository idempotencyRepository,
                                       VendorConfigCache configCache,
                                       AlertService alertService,
                                       NotificationMetrics metrics,
                                       Clock clock,
                                       PlatformTransactionManager txManager) {
        this.requestRepository = requestRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.configCache = configCache;
        this.alertService = alertService;
        this.metrics = metrics;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    public void recoverStale(int limit) {
        Instant now = clock.instant();
        for (NotificationRequest request : requestRepository.findStaleSendingRecords(now, limit)) {
            try {
                recoverOne(request, now);
            } catch (Exception e) {
                log.error("stale recovery failed for request {}", request.getId(), e);
            }
        }
    }

    private void recoverOne(NotificationRequest request, Instant now) {
        String vendorKey = request.getVendorKey();
        Optional<VendorConfig> config = configCache.get(vendorKey);
        if (config.isEmpty()) {
            deadLetter(request, now, "vendor config missing: " + vendorKey);
            return;
        }
        RetryPolicy retryPolicy = new ExponentialBackoffRetryPolicy(
                config.get().retryPolicy().baseDelay(), config.get().retryPolicy().maxDelay(),
                config.get().retryPolicy().maxAttempts(), new EqualJitterStrategy(new Random()));
        int nextAttempt = request.getAttemptCount() + 1;
        if (!retryPolicy.allowRetry(nextAttempt)) {
            deadLetter(request, now, "attempts exhausted (" + nextAttempt + ") after stale lock recovery");
            return;
        }
        Instant nextRetryAt = retryPolicy.calculateNextRetry(nextAttempt, now, Optional.empty());
        txTemplate.executeWithoutResult(tx -> {
            request.markFailed(nextRetryAt, "stale lock recovered (worker crash suspected)", now);
            requestRepository.update(request);
            idempotencyRepository.updateStatus(vendorKey, request.getIdempotencyKey(), IdempotencyStatus.FAILED);
        });
        metrics.incrementFailed();
        log.warn("stale SENDING recovered: requestId={} attempt={} nextRetryAt={}",
                request.getId(), nextAttempt, nextRetryAt);
    }

    private void deadLetter(NotificationRequest request, Instant now, String reason) {
        txTemplate.executeWithoutResult(tx -> {
            request.markDeadLettered(reason, now);
            requestRepository.update(request);
            idempotencyRepository.updateStatus(
                    request.getVendorKey(), request.getIdempotencyKey(), IdempotencyStatus.DEAD_LETTERED);
        });
        metrics.incrementDeadLettered();
        alertService.notifyDeadLetter(request.getId(), request.getVendorKey(), reason);
    }
}
