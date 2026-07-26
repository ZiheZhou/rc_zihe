package com.examine.domain.service;

import com.examine.domain.model.AcceptResult;
import com.examine.domain.model.IdempotencyRecord;
import com.examine.domain.model.NotificationRequest;
import com.examine.domain.model.Status;
import com.examine.domain.repository.IdempotencyRecordRepository;
import com.examine.domain.repository.NotificationRequestRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 幂等受理领域服务（technical-design.md 8.3）：
 * <ul>
 *   <li>vendorKey + idempotencyKey 不存在 → 同事务创建 NotificationRequest + IdempotencyRecord，返回 Accepted</li>
 *   <li>已 SUCCESS → Duplicate（不重复投递）</li>
 *   <li>处理中（PENDING/FAILED/SENDING）→ Duplicate（携带通知当前状态）</li>
 *   <li>已 DEAD_LETTERED → DeadLettered（API 层映射为 409，需人工重放）</li>
 * </ul>
 * 事务边界由 application 层保证，本服务只编排两次写。
 */
public class IdempotencyService {

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final NotificationRequestRepository notificationRequestRepository;
    private final Duration retention;

    public IdempotencyService(IdempotencyRecordRepository idempotencyRecordRepository,
                              NotificationRequestRepository notificationRequestRepository,
                              Duration retention) {
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.notificationRequestRepository = notificationRequestRepository;
        this.retention = retention;
    }

    public AcceptResult accept(String vendorKey, String idempotencyKey, String payload, Instant now) {
        return idempotencyRecordRepository.findByKey(vendorKey, idempotencyKey)
                .map(this::toDuplicateResult)
                .orElseGet(() -> createNew(vendorKey, idempotencyKey, payload, now));
    }

    private AcceptResult toDuplicateResult(IdempotencyRecord record) {
        return switch (record.getStatus()) {
            case SUCCESS -> new AcceptResult.Duplicate(record.getRequestId(), Status.SUCCESS);
            case DEAD_LETTERED -> new AcceptResult.DeadLettered(record.getRequestId());
            case PENDING, FAILED -> new AcceptResult.Duplicate(
                    record.getRequestId(), currentStatusOf(record.getRequestId()));
        };
    }

    private Status currentStatusOf(String requestId) {
        return notificationRequestRepository.findById(requestId)
                .map(NotificationRequest::getStatus)
                .orElse(Status.PENDING);
    }

    private AcceptResult createNew(String vendorKey, String idempotencyKey, String payload, Instant now) {
        String requestId = UUID.randomUUID().toString();
        NotificationRequest request = NotificationRequest.create(requestId, vendorKey, idempotencyKey, payload, now);
        IdempotencyRecord record = IdempotencyRecord.create(
                UUID.randomUUID().toString(), vendorKey, idempotencyKey, requestId, now, retention);
        notificationRequestRepository.save(request);
        idempotencyRecordRepository.save(record);
        return new AcceptResult.Accepted(requestId);
    }
}
