package com.examine.infrastructure.persistence;

import com.examine.domain.model.Status;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface NotificationRequestJpaRepository extends JpaRepository<NotificationRequestEntity, String> {

    @Query("select n from NotificationRequestEntity n where n.status in (:statuses) and n.nextRetryAt <= :now and n.lockedUntil <= :now order by n.nextRetryAt asc")
    List<NotificationRequestEntity> findDispatchable(@Param("statuses") List<Status> statuses,
                                                       @Param("now") Instant now,
                                                       Pageable pageable);

    @Query("select n from NotificationRequestEntity n where n.status = :sending and n.lockedUntil <= :now")
    List<NotificationRequestEntity> findStaleSending(@Param("sending") Status sending,
                                                       @Param("now") Instant now,
                                                       Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update NotificationRequestEntity n set n.status = :sending, n.lockedBy = :workerId, n.lockedUntil = :lockedUntil, n.updatedAt = :now where n.id = :id and n.status in (:acquirableStatuses) and n.nextRetryAt <= :now and n.lockedUntil <= :now")
    int acquireLock(@Param("id") String id,
                    @Param("workerId") String workerId,
                    @Param("lockedUntil") Instant lockedUntil,
                    @Param("now") Instant now,
                    @Param("sending") Status sending,
                    @Param("acquirableStatuses") List<Status> acquirableStatuses);

    long countByStatus(Status status);

    List<NotificationRequestEntity> findByStatusOrderByUpdatedAtDesc(Status status, Pageable pageable);
}
