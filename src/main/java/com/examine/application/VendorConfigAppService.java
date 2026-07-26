package com.examine.application;

import com.examine.api.dto.VendorConfigRequest;
import com.examine.domain.model.VendorHttpRequest;
import com.examine.domain.model.config.VendorConfig;
import com.examine.domain.repository.VendorConfigRepository;
import com.examine.domain.service.VendorRequestAssembler;
import com.examine.infrastructure.config.VendorConfigCache;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * VendorConfig 管理：CRUD + dry-run 预览。
 * 写操作后失效缓存，下一次读取回源（限流/熔断器按 settings 变化自动重建）。
 */
@Service
public class VendorConfigAppService {

    private final VendorConfigRepository repository;
    private final VendorConfigCache cache;
    private final VendorRequestAssembler assembler;

    public VendorConfigAppService(VendorConfigRepository repository,
                                  VendorConfigCache cache,
                                  VendorRequestAssembler assembler) {
        this.repository = repository;
        this.cache = cache;
        this.assembler = assembler;
    }

    @Transactional
    public VendorConfig create(VendorConfigRequest request) {
        VendorConfig saved = repository.save(request.toDomain());
        cache.refresh(saved.vendorKey());
        return saved;
    }

    @Transactional
    public VendorConfig update(String vendorKey, VendorConfigRequest request) {
        if (!repository.existsByKey(vendorKey)) {
            throw new VendorConfigNotFoundException(vendorKey);
        }
        VendorConfig saved = repository.save(request.toDomain());
        cache.refresh(vendorKey);
        return saved;
    }

    @Transactional
    public void delete(String vendorKey) {
        repository.delete(vendorKey);
        cache.refresh(vendorKey);
    }

    @Transactional(readOnly = true)
    public Optional<VendorConfig> findByKey(String vendorKey) {
        return repository.findByKey(vendorKey);
    }

    @Transactional(readOnly = true)
    public List<VendorConfig> findAll() {
        return repository.findAll();
    }

    /**
     * dry-run 预览：用示例 payload 渲染模板，返回将发往 vendor 的请求，不触发真实 HTTP。
     */
    @Transactional(readOnly = true)
    public VendorHttpRequest preview(String vendorKey, Map<String, Object> payload) {
        VendorConfig config = repository.findByKey(vendorKey)
                .orElseThrow(() -> new VendorConfigNotFoundException(vendorKey));
        return assembler.assemble("preview-request-id", "preview-idempotency-key", payload, config, 1);
    }
}
