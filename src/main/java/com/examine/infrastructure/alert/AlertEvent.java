package com.examine.infrastructure.alert;

import java.time.Instant;

/**
 * 告警事件模型（OpenSpec observability-alerting）。
 */
public record AlertEvent(
        String type,
        String vendorKey,
        String requestId,
        Instant timestamp,
        String errorSummary,
        String suggestedAction) {

    public static final String TYPE_DEAD_LETTER = "DEAD_LETTER";
    public static final String TYPE_VENDOR_UNHEALTHY = "VENDOR_UNHEALTHY";

    public static AlertEvent deadLetter(String requestId, String vendorKey, String reason, Instant now) {
        return new AlertEvent(TYPE_DEAD_LETTER, vendorKey, requestId, now, reason,
                "检查 vendor 返回与 payload 后通过 /admin/v1/dead-letters/{id}/retry 重放");
    }

    public static AlertEvent vendorUnhealthy(String vendorKey, String summary, Instant now) {
        return new AlertEvent(TYPE_VENDOR_UNHEALTHY, vendorKey, null, now, summary,
                "检查 vendor 服务健康度；必要时调整熔断/限流配置");
    }
}
