package com.examine.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VendorConfigJpaRepository extends JpaRepository<VendorConfigEntity, String> {
}
