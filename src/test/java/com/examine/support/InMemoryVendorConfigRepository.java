package com.examine.support;

import com.examine.domain.model.config.VendorConfig;
import com.examine.domain.repository.VendorConfigRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryVendorConfigRepository implements VendorConfigRepository {

    private final ConcurrentHashMap<String, VendorConfig> store = new ConcurrentHashMap<>();

    @Override
    public Optional<VendorConfig> findByKey(String vendorKey) {
        return Optional.ofNullable(store.get(vendorKey));
    }

    @Override
    public boolean existsByKey(String vendorKey) {
        return store.containsKey(vendorKey);
    }

    @Override
    public List<VendorConfig> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public VendorConfig save(VendorConfig config) {
        store.put(config.vendorKey(), config);
        return config;
    }

    @Override
    public void delete(String vendorKey) {
        store.remove(vendorKey);
    }
}
