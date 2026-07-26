package com.examine.domain.service;

/**
 * 告警出口（全局 webhook + ERROR 日志兜底）。实现必须异步、不阻塞投递主流程。
 */
public interface AlertService {

    /**
     * 通知进入死信队列，需要人工介入。
     */
    void notifyDeadLetter(String requestId, String vendorKey, String reason);

    /**
     * vendor 侧持续失败（如熔断器打开），提示检查 vendor 健康状况。
     */
    void notifyVendorUnhealthy(String vendorKey, String summary);
}
