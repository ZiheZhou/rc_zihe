package com.examine.domain.service;

import com.examine.domain.model.AcceptResult;
import com.examine.domain.model.IdempotencyRecord;
import com.examine.domain.model.IdempotencyStatus;
import com.examine.domain.model.NotificationRequest;
import com.examine.domain.model.Status;
import com.examine.domain.repository.IdempotencyRecordRepository;
import com.examine.domain.repository.NotificationRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class IdempotencyServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-26T10:00:00Z");
    private static final Duration RETENTION = Duration.ofDays(7);

    private InMemoryIdempotencyRecordRepository idempotencyRepo;
    private InMemoryNotificationRequestRepository requestRepo;
    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        idempotencyRepo = new InMemoryIdempotencyRecordRepository();
        requestRepo = new InMemoryNotificationRequestRepository();
        service = new IdempotencyService(idempotencyRepo, requestRepo, RETENTION);
    }

    @Test
    void acceptNewKeyCreatesBothRecordsAndReturnsAccepted() {
        AcceptResult result = service.accept("vendor-a", "idem-1", "{\"msg\":\"hi\"}", NOW);

        assertInstanceOf(AcceptResult.Accepted.class, result);
        String requestId = ((AcceptResult.Accepted) result).requestId();
        assertNotNull(requestId);

        Optional<NotificationRequest> request = requestRepo.findById(requestId);
        assertTrue(request.isPresent());
        assertEquals(Status.PENDING, request.get().getStatus());
        assertEquals("vendor-a", request.get().getVendorKey());
        assertEquals("idem-1", request.get().getIdempotencyKey());

        Optional<IdempotencyRecord> record = idempotencyRepo.findByKey("vendor-a", "idem-1");
        assertTrue(record.isPresent());
        assertEquals(IdempotencyStatus.PENDING, record.get().getStatus());
        assertEquals(requestId, record.get().getRequestId());
        assertEquals(NOW.plus(RETENTION), record.get().getExpiresAt());
    }

    @Test
    void acceptDuplicateOfSucceededReturnsDuplicateWithSuccessStatus() {
        AcceptResult first = service.accept("vendor-a", "idem-1", "{}", NOW);
        String requestId = ((AcceptResult.Accepted) first).requestId();
        NotificationRequest request = requestRepo.findById(requestId).orElseThrow();
        request.markSending("worker-1", NOW.plusSeconds(60), NOW);
        request.markSuccess(NOW.plusSeconds(5));
        idempotencyRepo.findByKey("vendor-a", "idem-1").orElseThrow().markSuccess();

        AcceptResult second = service.accept("vendor-a", "idem-1", "{}", NOW.plusSeconds(10));

        assertEquals(new AcceptResult.Duplicate(requestId, Status.SUCCESS), second);
        assertEquals(1, requestRepo.countAll());
    }

    @Test
    void acceptDuplicateInProgressReturnsDuplicateWithCurrentStatus() {
        AcceptResult first = service.accept("vendor-a", "idem-1", "{}", NOW);
        String requestId = ((AcceptResult.Accepted) first).requestId();

        AcceptResult second = service.accept("vendor-a", "idem-1", "{}", NOW.plusSeconds(1));

        assertEquals(new AcceptResult.Duplicate(requestId, Status.PENDING), second);
        assertEquals(1, requestRepo.countAll());
    }

    @Test
    void acceptDuplicateOfDeadLetteredReturnsDeadLettered() {
        AcceptResult first = service.accept("vendor-a", "idem-1", "{}", NOW);
        String requestId = ((AcceptResult.Accepted) first).requestId();
        NotificationRequest request = requestRepo.findById(requestId).orElseThrow();
        request.markDeadLettered("vendor returned 400", NOW.plusSeconds(3));
        idempotencyRepo.findByKey("vendor-a", "idem-1").orElseThrow().markDeadLettered();

        AcceptResult second = service.accept("vendor-a", "idem-1", "{}", NOW.plusSeconds(10));

        assertEquals(new AcceptResult.DeadLettered(requestId), second);
        assertEquals(1, requestRepo.countAll());
    }

    @Test
    void sameKeyUnderDifferentVendorIsIndependent() {
        AcceptResult first = service.accept("vendor-a", "idem-1", "{}", NOW);
        AcceptResult second = service.accept("vendor-b", "idem-1", "{}", NOW);

        assertInstanceOf(AcceptResult.Accepted.class, second);
        assertNotEquals(((AcceptResult.Accepted) first).requestId(),
                ((AcceptResult.Accepted) second).requestId());
    }

    // ---- in-memory fakes ----

    static class InMemoryIdempotencyRecordRepository implements IdempotencyRecordRepository {
        private final Map<String, IdempotencyRecord> store = new HashMap<>();

        private static String key(String vendorKey, String idempotencyKey) {
            return vendorKey + "::" + idempotencyKey;
        }

        @Override
        public Optional<IdempotencyRecord> findByKey(String vendorKey, String idempotencyKey) {
            return Optional.ofNullable(store.get(key(vendorKey, idempotencyKey)));
        }

        @Override
        public IdempotencyRecord save(IdempotencyRecord record) {
            store.put(key(record.getVendorKey(), record.getIdempotencyKey()), record);
            return record;
        }

        @Override
        public void updateStatus(String vendorKey, String idempotencyKey, IdempotencyStatus status) {
            IdempotencyRecord existing = store.get(key(vendorKey, idempotencyKey));
            if (existing == null) return;
            switch (status) {
                case SUCCESS -> existing.markSuccess();
                case FAILED -> existing.markFailed();
                case DEAD_LETTERED -> existing.markDeadLettered();
                case PENDING -> { /* no transition back to PENDING */ }
            }
        }

        @Override
        public int deleteExpired(Instant now) {
            List<String> expired = store.values().stream()
                    .filter(r -> r.getExpiresAt().isBefore(now))
                    .map(r -> key(r.getVendorKey(), r.getIdempotencyKey()))
                    .toList();
            expired.forEach(store::remove);
            return expired.size();
        }
    }

    static class InMemoryNotificationRequestRepository implements NotificationRequestRepository {
        private final Map<String, NotificationRequest> store = new HashMap<>();

        @Override
        public NotificationRequest save(NotificationRequest request) {
            store.put(request.getId(), request);
            return request;
        }

        @Override
        public NotificationRequest update(NotificationRequest request) {
            store.put(request.getId(), request);
            return request;
        }

        @Override
        public Optional<NotificationRequest> findById(String id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<NotificationRequest> findPendingForDispatch(Instant now, int limit) {
            return store.values().stream()
                    .filter(r -> (r.getStatus() == Status.PENDING || r.getStatus() == Status.FAILED)
                            && !r.getNextRetryAt().isAfter(now)
                            && !r.getLockedUntil().isAfter(now))
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<NotificationRequest> findStaleSendingRecords(Instant now, int limit) {
            return store.values().stream()
                    .filter(r -> r.getStatus() == Status.SENDING && !r.getLockedUntil().isAfter(now))
                    .limit(limit)
                    .toList();
        }

        @Override
        public boolean acquireLock(String id, String workerId, Instant lockedUntil, Instant now) {
            NotificationRequest r = store.get(id);
            if (r == null) return false;
            boolean acquirable = (r.getStatus() == Status.PENDING || r.getStatus() == Status.FAILED)
                    && !r.getNextRetryAt().isAfter(now)
                    && !r.getLockedUntil().isAfter(now);
            if (!acquirable) return false;
            r.markSending(workerId, lockedUntil, now);
            return true;
        }

        @Override
        public List<NotificationRequest> findByStatus(Status status, int limit) {
            return store.values().stream().filter(r -> r.getStatus() == status).limit(limit).toList();
        }

        @Override
        public long countByStatus(Status status) {
            return store.values().stream().filter(r -> r.getStatus() == status).count();
        }

        long countAll() {
            return store.size();
        }
    }
}
