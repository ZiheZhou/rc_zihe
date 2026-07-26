package com.examine.infrastructure.persistence;

import com.examine.domain.model.config.VendorConfig;
import com.examine.domain.repository.VendorConfigRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class VendorConfigRepositoryImpl implements VendorConfigRepository {

    private final VendorConfigJpaRepository jpaRepository;
    private final EntityMappers mappers;

    public VendorConfigRepositoryImpl(VendorConfigJpaRepository jpaRepository, EntityMappers mappers) {
        this.jpaRepository = jpaRepository;
        this.mappers = mappers;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<VendorConfig> findByKey(String vendorKey) {
        return jpaRepository.findById(vendorKey).map(mappers::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByKey(String vendorKey) {
        return jpaRepository.existsById(vendorKey);
    }

    @Override
    @Transactional(readOnly = true)
    public List<VendorConfig> findAll() {
        return jpaRepository.findAll().stream()
                .map(mappers::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public VendorConfig save(VendorConfig config) {
        VendorConfigEntity entity = mappers.toEntity(config);
        Instant now = Instant.now();
        if (entity.getCreatedAt() == null) {
            entity.setCreatedAt(now);
        }
        entity.setUpdatedAt(now);
        return mappers.toDomain(jpaRepository.save(entity));
    }

    @Override
    @Transactional
    public void delete(String vendorKey) {
        jpaRepository.deleteById(vendorKey);
    }
}
