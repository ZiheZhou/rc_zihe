package com.examine.infrastructure.persistence;

import com.examine.domain.model.IdempotencyRecord;
import com.examine.domain.model.IdempotencyStatus;
import com.examine.domain.repository.IdempotencyRecordRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Repository
public class IdempotencyRecordRepositoryImpl implements IdempotencyRecordRepository {

    private final IdempotencyRecordJpaRepository jpaRepository;
    private final EntityMappers mappers;

    public IdempotencyRecordRepositoryImpl(IdempotencyRecordJpaRepository jpaRepository, EntityMappers mappers) {
        this.jpaRepository = jpaRepository;
        this.mappers = mappers;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IdempotencyRecord> findByKey(String vendorKey, String idempotencyKey) {
        return jpaRepository.findByVendorKeyAndIdempotencyKey(vendorKey, idempotencyKey)
                .map(mappers::toDomain);
    }

    @Override
    @Transactional
    public IdempotencyRecord save(IdempotencyRecord record) {
        return mappers.toDomain(jpaRepository.save(mappers.toEntity(record)));
    }

    @Override
    @Transactional
    public void updateStatus(String vendorKey, String idempotencyKey, IdempotencyStatus status) {
        jpaRepository.findByVendorKeyAndIdempotencyKey(vendorKey, idempotencyKey)
                .ifPresent(entity -> {
                    entity.setStatus(status);
                    jpaRepository.save(entity);
                });
    }

    @Override
    @Transactional
    public int deleteExpired(Instant now) {
        return jpaRepository.deleteByExpiresAtBefore(now);
    }
}
