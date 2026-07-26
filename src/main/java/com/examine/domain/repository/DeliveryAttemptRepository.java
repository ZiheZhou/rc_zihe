package com.examine.domain.repository;

import com.examine.domain.model.DeliveryAttempt;

import java.util.List;

public interface DeliveryAttemptRepository {

    DeliveryAttempt save(DeliveryAttempt attempt);

    List<DeliveryAttempt> findByNotificationId(String notificationId);
}
