package com.examine.application;

import com.examine.domain.model.NotificationRequest;
import com.examine.domain.repository.NotificationRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 锁超时恢复：SENDING 且 lockedUntil 过期的记录，释放锁并重置为 PENDING。
 * <p>
 * 不递增 attemptCount（崩溃 worker 的 HTTP 结果未知），不标记为失败。
 * 下一个 worker 获取锁后执行实际重试，由投递路径完成 attempt 计数。
 * <p>
 * {@code @Version} 乐观锁保证恢复操作不与 worker 的并发写回冲突——
 * 后写的一方抛出 OptimisticLockingFailureException，记录日志后跳过。
 */
@Service
public class StaleLockRecoveryAppService {

    private static final Logger log = LoggerFactory.getLogger(StaleLockRecoveryAppService.class);

    private final NotificationRequestRepository requestRepository;
    private final Clock clock;
    private final TransactionTemplate txTemplate;
    private final Duration maxStaleAge;

    public StaleLockRecoveryAppService(NotificationRequestRepository requestRepository,
                                       Clock clock,
                                       PlatformTransactionManager txManager,
                                       @Value("${notification.stale-lock-max-age-hours:24}") int maxStaleAgeHours) {
        this.requestRepository = requestRepository;
        this.clock = clock;
        this.txTemplate = new TransactionTemplate(txManager);
        this.maxStaleAge = Duration.ofHours(maxStaleAgeHours);
    }

    public void recoverStale(int limit) {
        Instant now = clock.instant();
        for (NotificationRequest request : requestRepository.findStaleSendingRecords(now, limit)) {
            try {
                recoverOne(request, now);
            } catch (OptimisticLockingFailureException e) {
                log.info("stale lock recovery skipped for request {}: worker write-back won the race", request.getId());
            } catch (Exception e) {
                log.error("stale recovery failed for request {}", request.getId(), e);
            }
        }
    }

    private void recoverOne(NotificationRequest request, Instant now) {
        if (Duration.between(request.getCreatedAt(), now).compareTo(maxStaleAge) > 0) {
            txTemplate.executeWithoutResult(tx -> {
                request.markDeadLettered(
                        "stale lock recovery max age exceeded (" + maxStaleAge.toHours() + "h)", now);
                requestRepository.update(request);
            });
            log.error("stale lock recovery ESCAPE HATCH: request {} dead-lettered after exceeding max age {}h",
                    request.getId(), maxStaleAge.toHours());
            return;
        }
        txTemplate.executeWithoutResult(tx -> {
            request.releaseStaleLock(now);
            requestRepository.update(request);
        });
        log.info("stale SENDING lock released for request {} (attempts preserved at {})",
                request.getId(), request.getAttemptCount());
    }
}
