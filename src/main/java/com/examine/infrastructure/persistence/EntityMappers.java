package com.examine.infrastructure.persistence;

import com.examine.domain.model.IdempotencyRecord;
import com.examine.domain.model.IdempotencyStatus;
import com.examine.domain.model.NotificationRequest;
import com.examine.domain.model.Status;
import com.examine.domain.model.config.CircuitBreakerMode;
import com.examine.domain.model.config.CircuitBreakerSettings;
import com.examine.domain.model.config.HttpMethod;
import com.examine.domain.model.config.IdempotencyKeyLocation;
import com.examine.domain.model.config.RateLimitSettings;
import com.examine.domain.model.config.RetryPolicySettings;
import com.examine.domain.model.config.VendorConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

@Component
public class EntityMappers {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {};

    public NotificationRequest toDomain(NotificationRequestEntity entity) {
        return NotificationRequest.restore(
                entity.getId(),
                entity.getVendorKey(),
                entity.getIdempotencyKey(),
                entity.getPayload(),
                entity.getStatus(),
                entity.getAttemptCount(),
                entity.getNextRetryAt(),
                entity.getLockedBy(),
                entity.getLockedUntil(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeliveredAt(),
                entity.getLastError()
        );
    }

    public NotificationRequestEntity toEntity(NotificationRequest request) {
        NotificationRequestEntity entity = new NotificationRequestEntity();
        entity.setId(request.getId());
        entity.setVendorKey(request.getVendorKey());
        entity.setIdempotencyKey(request.getIdempotencyKey());
        entity.setPayload(request.getPayload());
        entity.setStatus(request.getStatus());
        entity.setAttemptCount(request.getAttemptCount());
        entity.setNextRetryAt(request.getNextRetryAt());
        entity.setLockedBy(request.getLockedBy());
        entity.setLockedUntil(request.getLockedUntil());
        entity.setCreatedAt(request.getCreatedAt());
        entity.setUpdatedAt(request.getUpdatedAt());
        entity.setDeliveredAt(request.getDeliveredAt());
        entity.setLastError(request.getLastError());
        return entity;
    }

    public IdempotencyRecord toDomain(IdempotencyRecordEntity entity) {
        return IdempotencyRecord.restore(
                entity.getId(),
                entity.getVendorKey(),
                entity.getIdempotencyKey(),
                entity.getRequestId(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getExpiresAt()
        );
    }

    public IdempotencyRecordEntity toEntity(IdempotencyRecord record) {
        IdempotencyRecordEntity entity = new IdempotencyRecordEntity();
        entity.setId(record.getId());
        entity.setVendorKey(record.getVendorKey());
        entity.setIdempotencyKey(record.getIdempotencyKey());
        entity.setRequestId(record.getRequestId());
        entity.setStatus(record.getStatus());
        entity.setCreatedAt(record.getCreatedAt());
        entity.setExpiresAt(record.getExpiresAt());
        return entity;
    }

    public VendorConfig toDomain(VendorConfigEntity entity) {
        return new VendorConfig(
                entity.getVendorKey(),
                entity.getEndpoint(),
                HttpMethod.valueOf(entity.getHttpMethod()),
                readJson(entity.getHeaders(), STRING_MAP_TYPE),
                entity.getBodyTemplate(),
                Duration.ofMillis(entity.getTimeoutMs()),
                readJson(entity.getRetryPolicy(), RetryPolicySettings.class),
                readJson(entity.getRateLimit(), RateLimitSettings.class),
                readJson(entity.getCircuitBreaker(), CircuitBreakerSettings.class),
                IdempotencyKeyLocation.valueOf(entity.getIdempotencyKeyLocation()),
                entity.getIdempotencyKeyName()
        );
    }

    public VendorConfigEntity toEntity(VendorConfig config) {
        VendorConfigEntity entity = new VendorConfigEntity();
        entity.setVendorKey(config.vendorKey());
        entity.setEndpoint(config.endpoint());
        entity.setHttpMethod(config.method().name());
        entity.setHeaders(writeJson(config.headers()));
        entity.setBodyTemplate(config.bodyTemplate());
        entity.setTimeoutMs((int) config.timeout().toMillis());
        entity.setRetryPolicy(writeJson(config.retryPolicy()));
        entity.setRateLimit(writeJson(config.rateLimit()));
        entity.setCircuitBreaker(writeJson(config.circuitBreaker()));
        entity.setIdempotencyKeyLocation(config.idempotencyKeyLocation().name());
        entity.setIdempotencyKeyName(config.idempotencyKeyName());
        return entity;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to serialize to JSON: " + value, e);
        }
    }

    private <T> T readJson(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to deserialize JSON: " + json, e);
        }
    }

    private <T> T readJson(String json, TypeReference<T> typeReference) {
        try {
            return objectMapper.readValue(json, typeReference);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to deserialize JSON: " + json, e);
        }
    }
}
