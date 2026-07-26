package com.examine.application;

import com.examine.domain.model.AcceptResult;
import com.examine.domain.repository.VendorConfigRepository;
import com.examine.domain.service.IdempotencyService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;

/**
 * 受理用例：校验 vendor 存在 → 委托 IdempotencyService 幂等受理。
 * 两表（NotificationRequest + IdempotencyRecord）在同一事务内写入。
 */
@Service
public class NotificationAcceptAppService {

    private final VendorConfigRepository vendorConfigRepository;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public NotificationAcceptAppService(VendorConfigRepository vendorConfigRepository,
                                        IdempotencyService idempotencyService,
                                        ObjectMapper objectMapper,
                                        Clock clock) {
        this.vendorConfigRepository = vendorConfigRepository;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public AcceptResult accept(String vendorKey, String idempotencyKey, Map<String, Object> payload) {
        if (!vendorConfigRepository.existsByKey(vendorKey)) {
            throw new VendorNotFoundException(vendorKey);
        }
        return idempotencyService.accept(vendorKey, idempotencyKey, toJson(payload), clock.instant());
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("payload is not JSON-serializable", e);
        }
    }
}
