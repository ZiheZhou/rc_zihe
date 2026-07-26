package com.examine.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyRecordJpaRepository extends JpaRepository<IdempotencyRecordEntity, String> {
    Optional<IdempotencyRecordEntity> findByVendorKeyAndIdempotencyKey(String vendorKey, String idempotencyKey);

    int deleteByExpiresAtBefore(Instant now);
}
