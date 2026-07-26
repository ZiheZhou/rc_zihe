## 1. 项目基础设置

- [ ] 1.1 在 `pom.xml` 中添加 Spring Boot 3.x、Spring Data JPA、H2/PostgreSQL 驱动、Bucket4j、Resilience4j、Apache Commons Text、WireMock、Testcontainers 等依赖。
- [ ] 1.2 配置 `application.yml`：数据源、JPA、调度器、告警 Webhook、Actuator 端点、默认日志格式。
- [ ] 1.3 创建四层包结构：`api`、`application`、`domain`、`infrastructure`。
- [ ] 1.4 添加 `docker-compose.yml`（可选 PostgreSQL）与 `schema.sql`，确保 H2 与 PostgreSQL schema 兼容。

## 2. 领域模型与仓库接口

- [ ] 2.1 实现 `NotificationRequest`、`IdempotencyRecord`、`VendorConfig` 领域实体与值对象（含状态机枚举）。
- [ ] 2.2 定义 `NotificationRequestRepository` 接口，包含 `save`、`update`、`findById`、`findPendingForDispatch`、`findStaleSendingRecords`、`acquireLock`、`findDeadLetters`。
- [ ] 2.3 定义 `IdempotencyRecordRepository` 接口，包含 `findByKey`、`save`、`updateStatus`、`deleteExpired`。
- [ ] 2.4 定义 `VendorConfigRepository` 接口，包含 `findByKey`、`findAll`、`save`、`delete`。
- [ ] 2.5 实现 JPA 实体与 Repository 实现类，添加必要索引（调度索引、幂等唯一索引等）。

## 3. Vendor 配置管理

- [ ] 3.1 实现 `VendorConfig` 内存缓存，启动时加载全量配置，Admin 更新后刷新。
- [ ] 3.2 实现 `VendorConfigAppService`，提供 CRUD 用例与缓存刷新。
- [ ] 3.3 实现 Admin REST API：`POST /admin/v1/vendor-configs`、`GET /admin/v1/vendor-configs/{vendorKey}`、`PUT /admin/v1/vendor-configs/{vendorKey}`、`DELETE /admin/v1/vendor-configs/{vendorKey}`。
- [ ] 3.4 实现 `VendorRequestAssembler`，根据配置渲染 URL、headers、body，并支持 dry-run 预览。

## 4. 通知受理与幂等

- [ ] 4.1 实现 `IdempotencyService`，处理首次提交、SUCCESS、处理中、DEAD_LETTERED 四种幂等状态。
- [ ] 4.2 实现 `NotificationAcceptAppService`，编排参数校验、幂等判断、通知请求创建与事务。
- [ ] 4.3 实现 `POST /api/v1/notifications` 控制器与 DTO，返回 202/200/400/409 等状态码。
- [ ] 4.4 实现 `GET /api/v1/notifications/{requestId}` 查询接口。
- [ ] 4.5 编写受理与幂等相关的单元测试与集成测试。

## 5. 可靠投递核心

- [ ] 5.1 实现 `RetryPolicy` 策略接口与默认指数退避 + 抖动实现，支持 `Retry-After` hint。
- [ ] 5.2 实现 `DeliveryResultClassifier`，根据响应码/异常分类为 Success、RetryableFailure、RateLimited、NonRetryableFailure。
- [ ] 5.3 实现 `LeaseLockPolicy`，计算锁超时时间。
- [ ] 5.4 实现 `DeliveryWorker`：获取锁 → 限流检查 → 熔断检查 → 组装请求 → 发送 → 处理结果 → 更新状态。
- [ ] 5.5 实现 `DeliveryAppService` 或 Worker 编排层，定义单次投递的事务边界。
- [ ] 5.6 实现 Scheduler：定时扫描 PENDING 记录和锁超时的 SENDING 记录，分发给 Worker 线程池。
- [ ] 5.7 实现基于 Spring `RestClient` 的 HTTP 客户端基础设施。
- [ ] 5.8 编写投递、重试、锁超时恢复相关的单元测试与 WireMock 集成测试。

## 6. 死信队列管理

- [ ] 6.1 在 `NotificationRequestRepository` 中实现死信查询方法。
- [ ] 6.2 实现 `DeadLetterReplayAppService`，支持将 DEAD_LETTERED 记录重置为 PENDING。
- [ ] 6.3 实现 Admin REST API：`POST /admin/v1/dead-letters/{requestId}/retry`。
- [ ] 6.4 编写死信重放相关的单元测试与集成测试。

## 7. 投递弹性能力

- [ ] 7.1 集成 Bucket4j，实现按 vendor 的内存级令牌桶限流，被限流时仅推迟 `nextRetryAt`。
- [ ] 7.2 集成 Resilience4j，实现按 vendor 的熔断器（CLOSED/OPEN/HALF_OPEN）及手动模式。
- [ ] 7.3 在 `DeliveryWorker` 中接入限流与熔断判断。
- [ ] 7.4 编写限流与熔断的单元测试和集成测试（可配合 WireMock 模拟失败场景）。

## 8. 可观测性与告警

- [ ] 8.1 配置 SLF4J + MDC，在关键路径中输出包含 `requestId`、`vendorKey`、`idempotencyKey` 的结构化日志。
- [ ] 8.2 使用 Micrometer 注册自定义指标：`notification.received.total`、`notification.delivered.total`、`notification.failed.total`、`notification.dead_lettered.total`、PENDING/DLQ gauge。
- [ ] 8.3 实现告警事件模型与异步告警发送器（全局 Webhook + 日志 fallback）。
- [ ] 8.4 在 DLQ、vendor 失败率过高时触发告警，并实现告警收敛（冷却窗口）。
- [ ] 8.5 验证 `/actuator/metrics` 与日志输出符合预期。

## 9. 测试与文档

- [ ] 9.1 编写核心领域模型与策略的单元测试（状态机、重试策略、错误分类器）。
- [ ] 9.2 编写 API 层与 Application 层的集成测试，覆盖受理、查询、死信重放等场景。
- [ ] 9.3 使用 WireMock 模拟 vendor，编写端到端投递与重试测试（含 2xx/5xx/429/4xx 场景）。
- [ ] 9.4 使用 Testcontainers + PostgreSQL 验证生产数据库兼容性。
- [ ] 9.5 更新 README，包含启动方式、API 说明、设计决策摘要。
- [ ] 9.6 编写 `docs/ai-usage.md`，说明 AI 辅助点、未采纳建议及人工决策。

## 10. 最终验证

- [ ] 10.1 运行 `./mvnw test`，确保所有测试通过。
- [ ] 10.2 本地启动服务，使用 curl/HTTP 客户端验证受理、投递、重试、DLQ 全流程。
- [ ] 10.3 检查代码风格与覆盖率，清理无用代码和 TODO。
- [ ] 10.4 运行 OpenSpec 校验：`openspec validate --change notification-gateway-mvp`。
