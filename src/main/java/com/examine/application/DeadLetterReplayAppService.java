package com.examine.application;

import com.examine.api.dto.NotificationResponse;
import com.examine.api.dto.NotificationStatusResponse;
import com.examine.domain.model.IdempotencyStatus;
import com.examine.domain.model.NotificationRequest;
import com.examine.domain.model.Status;
import com.examine.domain.repository.IdempotencyRecordRepository;
import com.examine.domain.repository.NotificationRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * DLQ 人工重放：仅 DEAD_LETTERED 可重放 → 回 PENDING 等待重新投递，
 * 幂等记录同步回 PENDING（重放成功后会被标记 SUCCESS；重放期间重复提交按处理中去重）。
 */
@Service
public class DeadLetterReplayAppService {

    private final NotificationRequestRepository requestRepository;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final Clock clock;

    public DeadLetterReplayAppService(NotificationRequestRepository requestRepository,
                                      IdempotencyRecordRepository idempotencyRepository,
                                      Clock clock) {
        this.requestRepository = requestRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.clock = clock;
    }

    @Transactional
    public NotificationResponse replay(String requestId) {
        NotificationRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new NotificationNotFoundException(requestId));
        if (request.getStatus() != Status.DEAD_LETTERED) {
            throw new IllegalStateException(
                    "only DEAD_LETTERED can be replayed, current: " + request.getStatus());
        }
        request.replay(clock.instant());
        requestRepository.update(request);
        idempotencyRepository.updateStatus(
                request.getVendorKey(), request.getIdempotencyKey(), IdempotencyStatus.PENDING);
        return new NotificationResponse(request.getId(), request.getStatus());
    }

    @Transactional(readOnly = true)
    public List<NotificationStatusResponse> listDeadLetters(int limit) {
        return requestRepository.findByStatus(Status.DEAD_LETTERED, limit).stream()
                .map(NotificationStatusResponse::from)
                .toList();
    }
}
