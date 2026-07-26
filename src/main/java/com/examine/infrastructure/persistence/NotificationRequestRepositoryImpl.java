package com.examine.infrastructure.persistence;

import com.examine.domain.model.NotificationRequest;
import com.examine.domain.model.Status;
import com.examine.domain.repository.NotificationRequestRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class NotificationRequestRepositoryImpl implements NotificationRequestRepository {

    private final NotificationRequestJpaRepository jpaRepository;
    private final EntityMappers mappers;

    public NotificationRequestRepositoryImpl(NotificationRequestJpaRepository jpaRepository, EntityMappers mappers) {
        this.jpaRepository = jpaRepository;
        this.mappers = mappers;
    }

    @Override
    @Transactional
    public NotificationRequest save(NotificationRequest request) {
        NotificationRequestEntity entity = mappers.toEntity(request);
        return mappers.toDomain(jpaRepository.save(entity));
    }

    @Override
    @Transactional
    public NotificationRequest update(NotificationRequest request) {
        NotificationRequestEntity entity = mappers.toEntity(request);
        return mappers.toDomain(jpaRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<NotificationRequest> findById(String id) {
        return jpaRepository.findById(id).map(mappers::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationRequest> findPendingForDispatch(Instant now, int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        return jpaRepository.findDispatchable(List.of(Status.PENDING, Status.FAILED), now, pageable)
                .stream()
                .map(mappers::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationRequest> findStaleSendingRecords(Instant now, int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        return jpaRepository.findStaleSending(Status.SENDING, now, pageable)
                .stream()
                .map(mappers::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public boolean acquireLock(String id, String workerId, Instant lockedUntil, Instant now) {
        int updated = jpaRepository.acquireLock(
                id, workerId, lockedUntil, now, Status.SENDING, List.of(Status.PENDING, Status.FAILED));
        return updated == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationRequest> findByStatus(Status status, int limit) {
        Pageable pageable = PageRequest.of(0, limit);
        return jpaRepository.findByStatusOrderByUpdatedAtDesc(status, pageable)
                .stream()
                .map(mappers::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByStatus(Status status) {
        return jpaRepository.countByStatus(status);
    }
}
