package com.examine.infrastructure.persistence;

import com.examine.domain.model.Status;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "notification_request")
public class NotificationRequestEntity {

    @Id
    private String id;

    private String vendorKey;

    private String idempotencyKey;

    @Lob
    private String payload;

    @Enumerated(EnumType.STRING)
    private Status status;

    private int attemptCount;

    private Instant nextRetryAt;

    private String lockedBy;

    private Instant lockedUntil;

    private Instant createdAt;

    private Instant updatedAt;

    private Instant deliveredAt;

    private String lastError;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getVendorKey() { return vendorKey; }
    public void setVendorKey(String vendorKey) { this.vendorKey = vendorKey; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }

    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant nextRetryAt) { this.nextRetryAt = nextRetryAt; }

    public String getLockedBy() { return lockedBy; }
    public void setLockedBy(String lockedBy) { this.lockedBy = lockedBy; }

    public Instant getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(Instant lockedUntil) { this.lockedUntil = lockedUntil; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
}
