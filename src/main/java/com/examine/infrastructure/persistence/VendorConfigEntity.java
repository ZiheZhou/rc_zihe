package com.examine.infrastructure.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "vendor_config")
public class VendorConfigEntity {

    @Id
    private String vendorKey;

    private String endpoint;

    private String httpMethod;

    @Lob
    private String headers;

    @Lob
    private String bodyTemplate;

    private int timeoutMs;

    @Lob
    private String retryPolicy;

    @Lob
    private String rateLimit;

    @Lob
    private String circuitBreaker;

    private String idempotencyKeyLocation;

    private String idempotencyKeyName;

    private Instant createdAt;

    private Instant updatedAt;

    public String getVendorKey() { return vendorKey; }
    public void setVendorKey(String vendorKey) { this.vendorKey = vendorKey; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }

    public String getHeaders() { return headers; }
    public void setHeaders(String headers) { this.headers = headers; }

    public String getBodyTemplate() { return bodyTemplate; }
    public void setBodyTemplate(String bodyTemplate) { this.bodyTemplate = bodyTemplate; }

    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }

    public String getRetryPolicy() { return retryPolicy; }
    public void setRetryPolicy(String retryPolicy) { this.retryPolicy = retryPolicy; }

    public String getRateLimit() { return rateLimit; }
    public void setRateLimit(String rateLimit) { this.rateLimit = rateLimit; }

    public String getCircuitBreaker() { return circuitBreaker; }
    public void setCircuitBreaker(String circuitBreaker) { this.circuitBreaker = circuitBreaker; }

    public String getIdempotencyKeyLocation() { return idempotencyKeyLocation; }
    public void setIdempotencyKeyLocation(String idempotencyKeyLocation) { this.idempotencyKeyLocation = idempotencyKeyLocation; }

    public String getIdempotencyKeyName() { return idempotencyKeyName; }
    public void setIdempotencyKeyName(String idempotencyKeyName) { this.idempotencyKeyName = idempotencyKeyName; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
