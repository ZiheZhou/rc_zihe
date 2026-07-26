package com.examine.domain.repository;

import com.examine.domain.model.config.VendorConfig;

import java.util.List;
import java.util.Optional;

public interface VendorConfigRepository {
    Optional<VendorConfig> findByKey(String vendorKey);
    boolean existsByKey(String vendorKey);
    List<VendorConfig> findAll();
    VendorConfig save(VendorConfig config);
    void delete(String vendorKey);
}
