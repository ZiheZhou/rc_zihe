package com.examine.api.dto;

import com.examine.domain.model.DeliveryAttempt;
import com.examine.domain.model.NotificationRequest;
import com.examine.domain.model.Status;

import java.time.Instant;
import java.util.List;

public record NotificationStatusResponse(
        String requestId,
        String vendorKey,
        Status status,
        int attemptCount,
        Instant nextRetryAt,
        Instant deliveredAt,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        List<AttemptSummary> attempts) {

    public record AttemptSummary(int number, Integer statusCode, String error, long durationMs, Instant at) {}

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
                request.getUpdatedAt(),
                List.of());
    }

    public static NotificationStatusResponse from(NotificationRequest request, List<DeliveryAttempt> attempts) {
        return new NotificationStatusResponse(
                request.getId(),
                request.getVendorKey(),
                request.getStatus(),
                request.getAttemptCount(),
                request.getNextRetryAt(),
                request.getDeliveredAt(),
                request.getLastError(),
                request.getCreatedAt(),
                request.getUpdatedAt(),
                attempts.stream()
                        .map(a -> new AttemptSummary(
                                a.getAttemptNumber(), a.getStatusCode(), a.getError(),
                                a.getDurationMs(), a.getCreatedAt()))
                        .toList());
    }
}
