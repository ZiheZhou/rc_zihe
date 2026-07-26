package com.examine.api.dto;

import com.examine.domain.model.NotificationRequest;
import com.examine.domain.model.Status;

import java.time.Instant;

public record NotificationStatusResponse(
        String requestId,
        String vendorKey,
        Status status,
        int attemptCount,
        Instant nextRetryAt,
        Instant deliveredAt,
        String lastError,
        Instant createdAt,
        Instant updatedAt) {

    public static NotificationStatusResponse from(NotificationRequest request) {
        return new NotificationStatusResponse(
                request.getId(),
                request.getVendorKey(),
                request.getStatus(),
                request.getAttemptCount(),
                request.getNextRetryAt(),
                request.getDeliveredAt(),
                request.getLastError(),
                request.getCreatedAt(),
                request.getUpdatedAt());
    }
}
