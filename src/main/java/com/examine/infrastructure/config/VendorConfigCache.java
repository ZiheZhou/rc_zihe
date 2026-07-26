package com.examine.infrastructure.config;

import com.examine.domain.model.config.VendorConfig;
import com.examine.domain.repository.VendorConfigRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VendorConfig 读缓存：投递热路径不直接查库。
 * 配置变更后调用 refresh 失效，下一次读取回源。
 */
@Component
public class VendorConfigCache {

    private final VendorConfigRepository repository;
    private final ConcurrentHashMap<String, Optional<VendorConfig>> cache = new ConcurrentHashMap<>();

    public VendorConfigCache(VendorConfigRepository repository) {
        this.repository = repository;
    }

    public Optional<VendorConfig> get(String vendorKey) {
        return cache.computeIfAbsent(vendorKey, repository::findByKey);
    }

    public void refresh(String vendorKey) {
        cache.remove(vendorKey);
    }

    public void refreshAll() {
        cache.clear();
    }
}
