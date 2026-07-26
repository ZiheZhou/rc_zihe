package com.examine.infrastructure.scheduling;

import com.examine.application.DeliveryAppService;
import com.examine.domain.model.NotificationRequest;
import com.examine.domain.repository.NotificationRequestRepository;
import com.examine.infrastructure.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 拉取可投递记录并提交到 worker 池并行投递（DB 队列 + pull 模型）。
 * 锁竞争失败的记录由 tryDispatch 返回 false 自然跳过。
 */
@Component
public class DeliveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeliveryScheduler.class);

    private final NotificationRequestRepository requestRepository;
    private final DeliveryAppService deliveryAppService;
    private final ExecutorService workerPool;
    private final Clock clock;
    private final int batchSize;

    public DeliveryScheduler(NotificationRequestRepository requestRepository,
                             DeliveryAppService deliveryAppService,
                             ExecutorService notificationWorkerPool,
                             Clock clock,
                             NotificationProperties properties) {
        this.requestRepository = requestRepository;
        this.deliveryAppService = deliveryAppService;
        this.workerPool = notificationWorkerPool;
        this.clock = clock;
        this.batchSize = properties.scheduler().batchSizeOrDefault();
    }

    @Scheduled(fixedDelayString = "${notification.scheduler.fixed-delay-ms:2000}")
    public void poll() {
        List<NotificationRequest> batch = requestRepository.findPendingForDispatch(clock.instant(), batchSize);
        for (NotificationRequest request : batch) {
            workerPool.execute(() -> {
                try {
                    deliveryAppService.tryDispatch(request.getId());
                } catch (Exception e) {
                    log.error("dispatch failed for request {}", request.getId(), e);
                }
            });
        }
    }
}
