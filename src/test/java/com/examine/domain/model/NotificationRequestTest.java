package com.examine.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class NotificationRequestTest {

    private static final Instant NOW = Instant.parse("2026-07-26T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-07-26T10:05:00Z");
    private static final Instant NEXT_RETRY = Instant.parse("2026-07-26T10:01:00Z");

    @Test
    void createProducesPendingWithAttemptCountZeroAndUnlockedLock() {
        NotificationRequest request = NotificationRequest.create(
                "id-1", "vendor-a", "idem-1", "payload-1", NOW);

        assertEquals("id-1", request.getId());
        assertEquals("vendor-a", request.getVendorKey());
        assertEquals("idem-1", request.getIdempotencyKey());
        assertEquals("payload-1", request.getPayload());
        assertEquals(Status.PENDING, request.getStatus());
        assertEquals(0, request.getAttemptCount());
        assertEquals(NOW, request.getNextRetryAt());
        assertNull(request.getLockedBy());
        assertEquals(NotificationRequest.UNLOCKED, request.getLockedUntil());
        assertEquals(NOW, request.getCreatedAt());
        assertEquals(NOW, request.getUpdatedAt());
        assertNull(request.getDeliveredAt());
        assertNull(request.getLastError());
    }

    @Test
    void markSendingSetsSendingWorkerIdAndLockedUntil() {
        NotificationRequest request = NotificationRequest.create(
                "id-1", "vendor-a", "idem-1", "payload-1", NOW);

        request.markSending("worker-1", LATER, LATER);

        assertEquals(Status.SENDING, request.getStatus());
        assertEquals("worker-1", request.getLockedBy());
        assertEquals(LATER, request.getLockedUntil());
        assertEquals(LATER, request.getUpdatedAt());
    }

    @Test
    void markSuccessSetsSuccessDeliveredAtAndReleasesLock() {
        NotificationRequest request = NotificationRequest.create(
                "id-1", "vendor-a", "idem-1", "payload-1", NOW);
        request.markSending("worker-1", LATER, NOW);

        request.markSuccess(LATER);

        assertEquals(Status.SUCCESS, request.getStatus());
        assertEquals(LATER, request.getDeliveredAt());
        assertNull(request.getLockedBy());
        assertEquals(NotificationRequest.UNLOCKED, request.getLockedUntil());
        assertEquals(LATER, request.getUpdatedAt());
    }

    @Test
    void markFailedIncrementsAttemptCountSetsNextRetryAtAndErrorAndReleasesLock() {
        NotificationRequest request = NotificationRequest.create(
                "id-1", "vendor-a", "idem-1", "payload-1", NOW);
        request.markSending("worker-1", LATER, NOW);

        request.markFailed(NEXT_RETRY, "timeout", LATER);

        assertEquals(Status.FAILED, request.getStatus());
        assertEquals(1, request.getAttemptCount());
        assertEquals(NEXT_RETRY, request.getNextRetryAt());
        assertEquals("timeout", request.getLastError());
        assertNull(request.getLockedBy());
        assertEquals(NotificationRequest.UNLOCKED, request.getLockedUntil());
        assertEquals(LATER, request.getUpdatedAt());
    }

    @Test
    void markDeadLetteredDoesNotIncrementAttemptCountAndReleasesLock() {
        NotificationRequest request = NotificationRequest.create(
                "id-1", "vendor-a", "idem-1", "payload-1", NOW);
        request.markSending("worker-1", LATER, NOW);
        request.markFailed(NEXT_RETRY, "timeout", NOW);
        int attemptsBeforeDlq = request.getAttemptCount();

        request.markDeadLettered("max retries exceeded", LATER);

        assertEquals(Status.DEAD_LETTERED, request.getStatus());
        assertEquals(attemptsBeforeDlq, request.getAttemptCount());
        assertEquals("max retries exceeded", request.getLastError());
        assertNull(request.getLockedBy());
        assertEquals(NotificationRequest.UNLOCKED, request.getLockedUntil());
        assertEquals(LATER, request.getUpdatedAt());
    }

    @Test
    void rescheduleGoesToPendingSetsNextRetryAtKeepsAttemptCountAndReleasesLock() {
        NotificationRequest request = NotificationRequest.create(
                "id-1", "vendor-a", "idem-1", "payload-1", NOW);
        request.markSending("worker-1", LATER, NOW);
        request.markFailed(NEXT_RETRY, "timeout", NOW);
        int attemptsBeforeReschedule = request.getAttemptCount();

        Instant newRetryAt = Instant.parse("2026-07-26T10:10:00Z");
        request.reschedule(newRetryAt, LATER);

        assertEquals(Status.PENDING, request.getStatus());
        assertEquals(attemptsBeforeReschedule, request.getAttemptCount());
        assertEquals(newRetryAt, request.getNextRetryAt());
        assertNull(request.getLockedBy());
        assertEquals(NotificationRequest.UNLOCKED, request.getLockedUntil());
        assertEquals(LATER, request.getUpdatedAt());
    }

    @Test
    void replayGoesToPendingWithNextRetryAtNow() {
        NotificationRequest request = NotificationRequest.create(
                "id-1", "vendor-a", "idem-1", "payload-1", NOW);
        request.markSending("worker-1", LATER, NOW);
        request.markDeadLettered("max retries exceeded", NOW);

        request.replay(LATER);

        assertEquals(Status.PENDING, request.getStatus());
        assertEquals(LATER, request.getNextRetryAt());
        assertNull(request.getLockedBy());
        assertEquals(NotificationRequest.UNLOCKED, request.getLockedUntil());
        assertEquals(LATER, request.getUpdatedAt());
    }

    @Test
    void restoreRecreatesFullState() {
        NotificationRequest request = NotificationRequest.restore(
                "id-1", "vendor-a", "idem-1", "payload-1",
                Status.FAILED, 3, NEXT_RETRY, "worker-1", LATER,
                NOW, NOW, null, "error");

        assertEquals("id-1", request.getId());
        assertEquals("vendor-a", request.getVendorKey());
        assertEquals("idem-1", request.getIdempotencyKey());
        assertEquals("payload-1", request.getPayload());
        assertEquals(Status.FAILED, request.getStatus());
        assertEquals(3, request.getAttemptCount());
        assertEquals(NEXT_RETRY, request.getNextRetryAt());
        assertEquals("worker-1", request.getLockedBy());
        assertEquals(LATER, request.getLockedUntil());
        assertEquals(NOW, request.getCreatedAt());
        assertEquals(NOW, request.getUpdatedAt());
        assertNull(request.getDeliveredAt());
        assertEquals("error", request.getLastError());
    }

    @Test
    void multipleFailedAttemptsIncrementAttemptCount() {
        NotificationRequest request = NotificationRequest.create(
                "id-1", "vendor-a", "idem-1", "payload-1", NOW);

        request.markSending("worker-1", LATER, NOW);
        request.markFailed(NEXT_RETRY, "error-1", NOW);
        assertEquals(1, request.getAttemptCount());

        request.markSending("worker-1", LATER, NOW);
        request.markFailed(NEXT_RETRY, "error-2", NOW);
        assertEquals(2, request.getAttemptCount());

        request.markSending("worker-1", LATER, NOW);
        request.markFailed(NEXT_RETRY, "error-3", NOW);
        assertEquals(3, request.getAttemptCount());
    }
}
