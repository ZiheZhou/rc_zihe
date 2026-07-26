package com.examine.infrastructure.persistence;

import com.examine.domain.model.DeliveryAttempt;
import com.examine.domain.repository.DeliveryAttemptRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public class DeliveryAttemptRepositoryImpl implements DeliveryAttemptRepository {

    private final DeliveryAttemptJpaRepository jpaRepository;

    public DeliveryAttemptRepositoryImpl(DeliveryAttemptJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public DeliveryAttempt save(DeliveryAttempt attempt) {
        DeliveryAttemptEntity entity = new DeliveryAttemptEntity();
        entity.setId(attempt.getId());
        entity.setNotificationId(attempt.getNotificationId());
        entity.setAttemptNumber(attempt.getAttemptNumber());
        entity.setStatusCode(attempt.getStatusCode());
        entity.setError(attempt.getError());
        entity.setDurationMs(attempt.getDurationMs());
        entity.setCreatedAt(attempt.getCreatedAt());
        return toDomain(jpaRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeliveryAttempt> findByNotificationId(String notificationId) {
        return jpaRepository.findByNotificationIdOrderByAttemptNumberAsc(notificationId)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private DeliveryAttempt toDomain(DeliveryAttemptEntity entity) {
        return DeliveryAttempt.restore(
                entity.getId(),
                entity.getNotificationId(),
                entity.getAttemptNumber(),
                entity.getStatusCode(),
                entity.getError(),
                entity.getDurationMs(),
                entity.getCreatedAt()
        );
    }
}
