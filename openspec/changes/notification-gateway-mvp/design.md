## Context

本变更基于 RightCapital AI Coding 作业需求：企业内部业务系统需要在关键事件发生时调用外部供应商 HTTP(S) API 进行通知，但业务系统不关注 vendor 返回值，只要求通知被稳定、可靠地送达。当前项目已产出两份参考文档：`docs/design.md`（架构设计）与 `docs/technical-design.md`（技术设计）。本变更将把这些设计收敛为可运行的 MVP 实现。

约束条件：
- 使用 Java + Spring Boot 生态（用户为 Java 后端开发者）。
- 优先最小可行实现（MVP），避免引入分布式消息队列、Redis、KMS 等额外依赖。
- 代码需体现清晰的领域分层与工程取舍逻辑。

## Goals / Non-Goals

**Goals：**
- 实现统一的 HTTP 入口接收通知请求，并立即返回 202 Accepted。
- 实现基于关系型数据库的通知队列、状态机、幂等控制、租约锁和重试调度。
- 实现 vendor 配置的动态管理，支持按 vendor 组装请求。
- 实现按 vendor 的限流、熔断和死信队列。
- 实现基础可观测性（日志、指标、DLQ 告警）。
- 提供单元测试、集成测试和一键本地启动能力。

**Non-Goals：**
- 不实现 vendor 返回值的业务处理。
- 不实现跨系统的 exactly-once 投递保证。
- 不引入 Kafka/RocketMQ/Redis 等外部中间件。
- 不实现企业级密钥管理或多租户。
- 不实现复杂工作流编排或 Saga。

## Decisions

### 1. 投递语义：At-least-once
- **选择**：采用至少一次投递语义。
- **理由**：跨系统 HTTP 调用无法经济地保证 exactly-once；业务方可通过幂等键与自身幂等设计消化重复。
- **替代方案**：exactly-once 需要 vendor 侧配合两阶段提交或事务消息，复杂度过高，不采纳。

### 2. 队列实现：DB 队列 + Scheduler 拉取
- **选择**：使用 `notification_request` 表作为队列，配合 Spring `@Scheduled` 定时扫描。
- **理由**：状态机、幂等、重试调度天然适合关系型存储；MVP 流量和复杂度不需要 Kafka/RabbitMQ；降低部署成本。
- **替代方案**：消息队列（Kafka/RabbitMQ）在吞吐和延迟上更优，但引入额外运维复杂度，留作演进项。

### 3. 并发控制：数据库租约锁
- **选择**：每条记录维护 `locked_by` 和 `locked_until`，worker 通过原子 UPDATE 获取锁。
- **理由**：无需 Redis/ZooKeeper 等分布式锁中间件；worker 崩溃后锁自然过期，其他 worker 可接管。
- **替代方案**：Redis RedLock 或基于消息队列的分区消费，多实例场景下更优雅，但 MVP 单实例优先。

### 4. 技术栈
- **框架**：Spring Boot 3.x + Java 21。
- **数据访问**：Spring Data JPA。
- **数据库**：H2（默认）+ 兼容 PostgreSQL。
- **HTTP 客户端**：Spring 6 `RestClient`。
- **限流**：Bucket4j。
- **熔断**：Resilience4j。
- **模板**：Apache Commons Text `StringSubstitutor`。
- **测试**：JUnit 5 + Mockito + Testcontainers + WireMock。

### 5. 代码分层
- **选择**：四层架构 `api → application → domain → infrastructure`，domain 层不依赖框架。
- **理由**：领域模型、状态机、重试策略等核心规则独立于框架，便于测试和演进。

### 6. 幂等设计
- **选择**：业务方提供 `idempotencyKey`，系统维护 `vendorKey + idempotencyKey` 到最终状态的映射。
- **理由**：业务方最清楚哪些通知代表同一业务事件；系统只保证本地去重和重试透传。

## Risks / Trade-offs

- **[Risk]** DB 队列轮询在高并发下可能成为瓶颈 → **Mitigation**：MVP 阶段流量可控；未来可演进为 Kafka + DB 幂等。
- **[Risk]** 内存级限流/熔断在单实例有效，多实例部署时不共享状态 → **Mitigation**：MVP 按单实例设计；多实例时引入 Redis 作为后续演进。
- **[Risk]** 幂等记录与通知请求状态更新可能出现不一致 → **Mitigation**：两者在同一事务中更新，并设计以 `NotificationRequest` 状态为 fallback 的补偿查询。
- **[Risk]** 模板渲染失败（如缺失字段）会导致直接进入 DLQ → **Mitigation**：MVP 采用简单占位符；Admin API 提供 dry-run 预览。

## Migration Plan

- 新增 Maven 依赖（Spring Boot、JPA、Bucket4j、Resilience4j、测试库等）。
- 新增数据库 schema 与索引（H2 自动建表，PostgreSQL 通过 `schema.sql` 或 Flyway）。
- 新增 `application.yml` 与默认 vendor 配置示例。
- 本地启动：`./mvnw spring-boot:run`。
- 可选：通过 `docker-compose.yml` 启动 PostgreSQL 验证生产兼容性。
- 回滚：删除新增代码与表即可恢复；MVP 无在线数据迁移。

## Open Questions

1. 是否需要通过 Flyway 管理 schema 版本？MVP 阶段 H2 自动建表足够，PostgreSQL 可用 `schema.sql`。
2. 告警通道是否只需要全局 Webhook + 日志 fallback？MVP 采用此方案。
3. 是否需要在第一版就提供管理后台 UI？不，仅提供 Admin REST API。
