package com.examine.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class IdempotencyRecordTest {

    private static final Instant NOW = Instant.parse("2026-07-26T10:00:00Z");

    @Test
    void createProducesPendingWithExpiresAt() {
        IdempotencyRecord record = IdempotencyRecord.create(
                "id-1", "vendor-a", "idem-1", "req-1", NOW, Duration.ofDays(7));

        assertEquals("id-1", record.getId());
        assertEquals("vendor-a", record.getVendorKey());
        assertEquals("idem-1", record.getIdempotencyKey());
        assertEquals("req-1", record.getRequestId());
        assertEquals(IdempotencyStatus.PENDING, record.getStatus());
        assertEquals(NOW, record.getCreatedAt());
        assertEquals(NOW.plus(Duration.ofDays(7)), record.getExpiresAt());
    }

    @Test
    void markSuccessTransitionsToSuccess() {
        IdempotencyRecord record = IdempotencyRecord.create(
                "id-1", "vendor-a", "idem-1", "req-1", NOW, Duration.ofDays(7));

        record.markSuccess();

        assertEquals(IdempotencyStatus.SUCCESS, record.getStatus());
    }

    @Test
    void markFailedTransitionsToFailed() {
        IdempotencyRecord record = IdempotencyRecord.create(
                "id-1", "vendor-a", "idem-1", "req-1", NOW, Duration.ofDays(7));

        record.markFailed();

        assertEquals(IdempotencyStatus.FAILED, record.getStatus());
    }

    @Test
    void markDeadLetteredTransitionsToDeadLettered() {
        IdempotencyRecord record = IdempotencyRecord.create(
                "id-1", "vendor-a", "idem-1", "req-1", NOW, Duration.ofDays(7));

        record.markDeadLettered();

        assertEquals(IdempotencyStatus.DEAD_LETTERED, record.getStatus());
    }

    @Test
    void restoreRecreatesFullState() {
        Instant expires = NOW.plus(Duration.ofDays(1));
        IdempotencyRecord record = IdempotencyRecord.restore(
                "id-1", "vendor-a", "idem-1", "req-1",
                IdempotencyStatus.SUCCESS, NOW, expires);

        assertEquals(IdempotencyStatus.SUCCESS, record.getStatus());
        assertEquals(NOW, record.getCreatedAt());
        assertEquals(expires, record.getExpiresAt());
    }
}
