package com.examine.infrastructure.persistence;

import com.examine.domain.model.IdempotencyRecord;
import com.examine.domain.model.IdempotencyStatus;
import com.examine.domain.model.NotificationRequest;
import com.examine.domain.model.Status;
import com.examine.domain.model.config.CircuitBreakerMode;
import com.examine.domain.model.config.CircuitBreakerSettings;
import com.examine.domain.model.config.HttpMethod;
import com.examine.domain.model.config.IdempotencyKeyLocation;
import com.examine.domain.model.config.RateLimitSettings;
import com.examine.domain.model.config.RetryPolicySettings;
import com.examine.domain.model.config.VendorConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Import({EntityMappers.class, NotificationRequestRepositoryImpl.class,
        IdempotencyRecordRepositoryImpl.class, VendorConfigRepositoryImpl.class})
class RepositoryImplTest {

    @Autowired
    private NotificationRequestRepositoryImpl notificationRequestRepository;

    @Autowired
    private IdempotencyRecordRepositoryImpl idempotencyRecordRepository;

    @Autowired
    private VendorConfigRepositoryImpl vendorConfigRepository;

    private static final Instant NOW = Instant.parse("2026-07-26T10:00:00Z");

    @Test
    void saveAndFindNotificationRequest() {
        NotificationRequest request = NotificationRequest.create(
                "req-1", "vendor-a", "idem-1", "{}", NOW);

        notificationRequestRepository.save(request);
        Optional<NotificationRequest> found = notificationRequestRepository.findById("req-1");

        assertTrue(found.isPresent());
        assertEquals(Status.PENDING, found.get().getStatus());
    }

    @Test
    void acquireLockAtomically() {
        NotificationRequest request = NotificationRequest.create(
                "req-2", "vendor-a", "idem-2", "{}", NOW);
        notificationRequestRepository.save(request);

        boolean acquired = notificationRequestRepository.acquireLock(
                "req-2", "worker-1", NOW.plusSeconds(60), NOW);

        assertTrue(acquired);
        Optional<NotificationRequest> found = notificationRequestRepository.findById("req-2");
        assertEquals(Status.SENDING, found.get().getStatus());
        assertEquals("worker-1", found.get().getLockedBy());

        // second acquire should fail
        boolean acquiredAgain = notificationRequestRepository.acquireLock(
                "req-2", "worker-2", NOW.plusSeconds(60), NOW);
        assertFalse(acquiredAgain);
    }

    @Test
    void findPendingForDispatchExcludesFutureRetry() {
        NotificationRequest past = NotificationRequest.create(
                "req-past", "vendor-a", "idem-past", "{}", NOW.minusSeconds(10));
        past.reschedule(NOW.minusSeconds(5), NOW.minusSeconds(10));
        notificationRequestRepository.save(past);

        NotificationRequest future = NotificationRequest.create(
                "req-future", "vendor-a", "idem-future", "{}", NOW);
        future.reschedule(NOW.plusSeconds(60), NOW);
        notificationRequestRepository.save(future);

        List<NotificationRequest> dispatchable = notificationRequestRepository.findPendingForDispatch(NOW, 10);

        assertEquals(1, dispatchable.size());
        assertEquals("req-past", dispatchable.get(0).getId());
    }

    @Test
    void findStaleSendingRecords() {
        NotificationRequest request = NotificationRequest.create(
                "req-stale", "vendor-a", "idem-stale", "{}", NOW.minusSeconds(120));
        request.markSending("worker-1", NOW.minusSeconds(60), NOW.minusSeconds(120));
        notificationRequestRepository.save(request);

        List<NotificationRequest> stale = notificationRequestRepository.findStaleSendingRecords(NOW, 10);

        assertEquals(1, stale.size());
        assertEquals("req-stale", stale.get(0).getId());
    }

    @Test
    void saveAndFindIdempotencyRecord() {
        IdempotencyRecord record = IdempotencyRecord.create(
                "idem-id", "vendor-a", "idem-key", "req-1", NOW, Duration.ofDays(7));
        idempotencyRecordRepository.save(record);

        Optional<IdempotencyRecord> found = idempotencyRecordRepository.findByKey("vendor-a", "idem-key");
        assertTrue(found.isPresent());
        assertEquals(IdempotencyStatus.PENDING, found.get().getStatus());
    }

    @Test
    void updateIdempotencyStatus() {
        IdempotencyRecord record = IdempotencyRecord.create(
                "idem-id", "vendor-a", "idem-key", "req-1", NOW, Duration.ofDays(7));
        idempotencyRecordRepository.save(record);

        idempotencyRecordRepository.updateStatus("vendor-a", "idem-key", IdempotencyStatus.SUCCESS);

        Optional<IdempotencyRecord> found = idempotencyRecordRepository.findByKey("vendor-a", "idem-key");
        assertEquals(IdempotencyStatus.SUCCESS, found.get().getStatus());
    }

    @Test
    void saveAndFindVendorConfig() {
        VendorConfig config = sampleConfig("vendor-a");
        vendorConfigRepository.save(config);

        Optional<VendorConfig> found = vendorConfigRepository.findByKey("vendor-a");
        assertTrue(found.isPresent());
        assertEquals(HttpMethod.POST, found.get().method());
        assertEquals(10, found.get().retryPolicy().maxAttempts());
        assertEquals(Map.of("Authorization", "Bearer token"), found.get().headers());
        assertEquals(CircuitBreakerMode.AUTO, found.get().circuitBreaker().mode());
    }

    @Test
    void existsByKey() {
        vendorConfigRepository.save(sampleConfig("vendor-b"));
        assertTrue(vendorConfigRepository.existsByKey("vendor-b"));
        assertFalse(vendorConfigRepository.existsByKey("vendor-missing"));
    }

    private VendorConfig sampleConfig(String vendorKey) {
        return new VendorConfig(
                vendorKey,
                "https://api.example.com/" + vendorKey,
                HttpMethod.POST,
                Map.of("Authorization", "Bearer token"),
                "{\"userId\":\"{{userId}}\"}",
                Duration.ofSeconds(30),
                new RetryPolicySettings(10, Duration.ofSeconds(1), Duration.ofHours(1)),
                new RateLimitSettings(10, 20),
                new CircuitBreakerSettings(CircuitBreakerMode.AUTO, 50, 10, 60, 3),
                IdempotencyKeyLocation.HEADER,
                "Idempotency-Key"
        );
    }
}
