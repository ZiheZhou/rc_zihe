package com.examine.domain.model;

import java.time.Duration;
import java.time.Instant;

public class IdempotencyRecord {

    private String id;
    private String vendorKey;
    private String idempotencyKey;
    private String requestId;
    private IdempotencyStatus status;
    private Instant createdAt;
    private Instant expiresAt;

    private IdempotencyRecord(String id, String vendorKey, String idempotencyKey, String requestId,
                              IdempotencyStatus status, Instant createdAt, Instant expiresAt) {
        this.id = id;
        this.vendorKey = vendorKey;
        this.idempotencyKey = idempotencyKey;
        this.requestId = requestId;
        this.status = status;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public static IdempotencyRecord create(String id, String vendorKey, String idempotencyKey, String requestId,
                                            Instant now, Duration retention) {
        return new IdempotencyRecord(id, vendorKey, idempotencyKey, requestId,
                IdempotencyStatus.PENDING, now, now.plus(retention));
    }

    public static IdempotencyRecord restore(String id, String vendorKey, String idempotencyKey, String requestId,
                                             IdempotencyStatus status, Instant createdAt, Instant expiresAt) {
        return new IdempotencyRecord(id, vendorKey, idempotencyKey, requestId, status, createdAt, expiresAt);
    }

    public void markSuccess() {
        this.status = IdempotencyStatus.SUCCESS;
    }

    public void markFailed() {
        this.status = IdempotencyStatus.FAILED;
    }

    public void markDeadLettered() {
        this.status = IdempotencyStatus.DEAD_LETTERED;
    }

    public String getId() { return id; }
    public String getVendorKey() { return vendorKey; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestId() { return requestId; }
    public IdempotencyStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
