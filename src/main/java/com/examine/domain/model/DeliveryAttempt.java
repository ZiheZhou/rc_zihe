package com.examine.domain.model;

import java.time.Instant;

/**
 * A single delivery attempt record — persisted independently so that
 * every retry's status code, error, and duration are traceable for ops.
 */
public class DeliveryAttempt {

    private String id;
    private String notificationId;
    private int attemptNumber;
    private Integer statusCode;
    private String error;
    private long durationMs;
    private Instant createdAt;

    private DeliveryAttempt(String id, String notificationId, int attemptNumber,
                            Integer statusCode, String error, long durationMs, Instant createdAt) {
        this.id = id;
        this.notificationId = notificationId;
        this.attemptNumber = attemptNumber;
        this.statusCode = statusCode;
        this.error = error;
        this.durationMs = durationMs;
        this.createdAt = createdAt;
    }

    public static DeliveryAttempt create(String id, String notificationId, int attemptNumber,
                                         Integer statusCode, String error, long durationMs, Instant now) {
        return new DeliveryAttempt(id, notificationId, attemptNumber, statusCode, error, durationMs, now);
    }

    public static DeliveryAttempt restore(String id, String notificationId, int attemptNumber,
                                          Integer statusCode, String error, long durationMs, Instant createdAt) {
        return new DeliveryAttempt(id, notificationId, attemptNumber, statusCode, error, durationMs, createdAt);
    }

    public String getId() { return id; }
    public String getNotificationId() { return notificationId; }
    public int getAttemptNumber() { return attemptNumber; }
    public Integer getStatusCode() { return statusCode; }
    public String getError() { return error; }
    public long getDurationMs() { return durationMs; }
    public Instant getCreatedAt() { return createdAt; }
}
