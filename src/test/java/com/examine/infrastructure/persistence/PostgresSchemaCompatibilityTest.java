package com.examine.infrastructure.persistence;

import com.examine.domain.model.NotificationRequest;
import com.examine.domain.model.Status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL 兼容性验证：schema.sql 初始化 + 基本读写（含 acquireLock CAS）。
 * 无 Docker 环境自动跳过。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({EntityMappers.class, NotificationRequestRepositoryImpl.class})
@Testcontainers(disabledWithoutDocker = true)
class PostgresSchemaCompatibilityTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.sql.init.mode", () -> "always");
    }

    @Autowired
    private NotificationRequestRepositoryImpl requestRepository;

    @Test
    void schemaInitializesAndBasicReadWriteWorks() {
        Instant now = Instant.now();
        NotificationRequest request = NotificationRequest.create(
                "pg-req-1", "vendor-a", "pg-idem-1", "{}", now);
        requestRepository.save(request);

        Optional<NotificationRequest> found = requestRepository.findById("pg-req-1");
        assertTrue(found.isPresent());
        assertEquals(Status.PENDING, found.get().getStatus());

        boolean acquired = requestRepository.acquireLock(
                "pg-req-1", "worker-pg", now.plusSeconds(60), now);
        assertTrue(acquired);
        assertEquals(Status.SENDING, requestRepository.findById("pg-req-1").orElseThrow().getStatus());
    }
}
