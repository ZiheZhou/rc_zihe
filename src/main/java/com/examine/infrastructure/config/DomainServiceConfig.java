package com.examine.infrastructure.config;

import com.examine.domain.repository.IdempotencyRecordRepository;
import com.examine.domain.repository.NotificationRequestRepository;
import com.examine.domain.service.IdempotencyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * domain 层服务不带 Spring 注解，在此统一装配为 Bean。
 */
@Configuration
public class DomainServiceConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public IdempotencyService idempotencyService(
            IdempotencyRecordRepository idempotencyRecordRepository,
            NotificationRequestRepository notificationRequestRepository,
            @Value("${notification.idempotency.retention-days:7}") long retentionDays) {
        return new IdempotencyService(
                idempotencyRecordRepository, notificationRequestRepository, Duration.ofDays(retentionDays));
    }
}
