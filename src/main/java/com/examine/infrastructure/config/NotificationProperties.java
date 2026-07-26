package com.examine.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * notification.* 配置（scheduling.enabled 由 SchedulingConfig 条件注解直接消费，不在此处）。
 */
@ConfigurationProperties("notification")
public record NotificationProperties(Scheduler scheduler, Worker worker) {

    public NotificationProperties {
        if (scheduler == null) {
            scheduler = new Scheduler(null, null, null);
        }
        if (worker == null) {
            worker = new Worker(null);
        }
    }

    public record Scheduler(Integer fixedDelayMs, Integer staleRecoveryFixedDelayMs, Integer batchSize) {
        public int fixedDelayMsOrDefault() {
            return fixedDelayMs == null ? 2000 : fixedDelayMs;
        }

        public int staleRecoveryFixedDelayMsOrDefault() {
            return staleRecoveryFixedDelayMs == null ? 60000 : staleRecoveryFixedDelayMs;
        }

        public int batchSizeOrDefault() {
            return batchSize == null ? 100 : batchSize;
        }
    }

    public record Worker(Integer poolSize) {
        public int poolSizeOrDefault() {
            return poolSize == null ? 10 : poolSize;
        }
    }
}
