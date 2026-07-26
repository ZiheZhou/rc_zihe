package com.examine.domain.repository;

import com.examine.domain.model.IdempotencyRecord;
import com.examine.domain.model.IdempotencyStatus;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyRecordRepository {
    Optional<IdempotencyRecord> findByKey(String vendorKey, String idempotencyKey);
    IdempotencyRecord save(IdempotencyRecord record);
    void updateStatus(String vendorKey, String idempotencyKey, IdempotencyStatus status);
    int deleteExpired(Instant now);
}
