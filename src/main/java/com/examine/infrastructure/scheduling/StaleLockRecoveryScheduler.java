package com.examine.infrastructure.scheduling;

import com.examine.application.StaleLockRecoveryAppService;
import com.examine.infrastructure.config.NotificationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StaleLockRecoveryScheduler {

    private final StaleLockRecoveryAppService staleLockRecoveryAppService;
    private final int batchSize;

    public StaleLockRecoveryScheduler(StaleLockRecoveryAppService staleLockRecoveryAppService,
                                      NotificationProperties properties) {
        this.staleLockRecoveryAppService = staleLockRecoveryAppService;
        this.batchSize = properties.scheduler().batchSizeOrDefault();
    }

    @Scheduled(fixedDelayString = "${notification.scheduler.stale-recovery-fixed-delay-ms:60000}")
    public void recover() {
        staleLockRecoveryAppService.recoverStale(batchSize);
    }
}
