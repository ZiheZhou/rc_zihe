package com.examine.infrastructure.persistence;

import com.examine.domain.model.IdempotencyStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecordEntity {

    @Id
    private String id;

    private String vendorKey;

    private String idempotencyKey;

    private String requestId;

    @Enumerated(EnumType.STRING)
    private IdempotencyStatus status;

    private Instant createdAt;

    private Instant expiresAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getVendorKey() { return vendorKey; }
    public void setVendorKey(String vendorKey) { this.vendorKey = vendorKey; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public IdempotencyStatus getStatus() { return status; }
    public void setStatus(IdempotencyStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
