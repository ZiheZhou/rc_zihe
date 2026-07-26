package com.examine.domain.repository;

import com.examine.domain.model.NotificationRequest;
import com.examine.domain.model.Status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface NotificationRequestRepository {
    NotificationRequest save(NotificationRequest request);
    NotificationRequest update(NotificationRequest request);
    Optional<NotificationRequest> findById(String id);
    List<NotificationRequest> findPendingForDispatch(Instant now, int limit);
    List<NotificationRequest> findStaleSendingRecords(Instant now, int limit);
    boolean acquireLock(String id, String workerId, Instant lockedUntil, Instant now);
    List<NotificationRequest> findByStatus(Status status, int limit);
    long countByStatus(Status status);
}
