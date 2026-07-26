package com.examine.domain.model;

import java.time.Instant;

public class NotificationRequest {

    public static final Instant UNLOCKED = Instant.EPOCH;

    private String id;
    private String vendorKey;
    private String idempotencyKey;
    private String payload;
    private Status status;
    private int attemptCount;
    private Instant nextRetryAt;
    private String lockedBy;
    private Instant lockedUntil;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deliveredAt;
    private String lastError;

    private NotificationRequest(String id, String vendorKey, String idempotencyKey, String payload,
                                Status status, int attemptCount, Instant nextRetryAt, String lockedBy,
                                Instant lockedUntil, Instant createdAt, Instant updatedAt, Instant deliveredAt,
                                String lastError) {
        this.id = id;
        this.vendorKey = vendorKey;
        this.idempotencyKey = idempotencyKey;
        this.payload = payload;
        this.status = status;
        this.attemptCount = attemptCount;
        this.nextRetryAt = nextRetryAt;
        this.lockedBy = lockedBy;
        this.lockedUntil = lockedUntil;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.deliveredAt = deliveredAt;
        this.lastError = lastError;
    }

    public static NotificationRequest create(String id, String vendorKey, String idempotencyKey, String payload, Instant now) {
        return new NotificationRequest(id, vendorKey, idempotencyKey, payload,
                Status.PENDING, 0, now, null, UNLOCKED, now, now, null, null);
    }

    public static NotificationRequest restore(String id, String vendorKey, String idempotencyKey, String payload,
                                               Status status, int attemptCount, Instant nextRetryAt, String lockedBy,
                                               Instant lockedUntil, Instant createdAt, Instant updatedAt,
                                               Instant deliveredAt, String lastError) {
        return new NotificationRequest(id, vendorKey, idempotencyKey, payload,
                status, attemptCount, nextRetryAt, lockedBy, lockedUntil,
                createdAt, updatedAt, deliveredAt, lastError);
    }

    public void markSending(String workerId, Instant lockedUntil, Instant now) {
        this.status = Status.SENDING;
        this.lockedBy = workerId;
        this.lockedUntil = lockedUntil;
        this.updatedAt = now;
    }

    public void markSuccess(Instant now) {
        this.status = Status.SUCCESS;
        this.deliveredAt = now;
        releaseLock(now);
    }

    public void markFailed(Instant nextRetryAt, String error, Instant now) {
        this.status = Status.FAILED;
        this.attemptCount++;
        this.nextRetryAt = nextRetryAt;
        this.lastError = error;
        releaseLock(now);
    }

    public void markDeadLettered(String error, Instant now) {
        this.status = Status.DEAD_LETTERED;
        this.lastError = error;
        releaseLock(now);
    }

    public void reschedule(Instant nextRetryAt, Instant now) {
        this.status = Status.PENDING;
        this.nextRetryAt = nextRetryAt;
        releaseLock(now);
    }

    public void replay(Instant now) {
        this.status = Status.PENDING;
        this.nextRetryAt = now;
        releaseLock(now);
    }

    private void releaseLock(Instant now) {
        this.lockedBy = null;
        this.lockedUntil = UNLOCKED;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getVendorKey() { return vendorKey; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getPayload() { return payload; }
    public Status getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public String getLockedBy() { return lockedBy; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public String getLastError() { return lastError; }
}
