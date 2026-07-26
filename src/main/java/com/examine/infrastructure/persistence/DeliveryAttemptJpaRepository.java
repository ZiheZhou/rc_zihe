package com.examine.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DeliveryAttemptJpaRepository extends JpaRepository<DeliveryAttemptEntity, String> {

    List<DeliveryAttemptEntity> findByNotificationIdOrderByAttemptNumberAsc(String notificationId);
}
