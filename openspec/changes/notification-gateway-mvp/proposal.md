## Why

企业内部多个业务系统需要在关键事件发生时调用外部供应商的 HTTP(S) API 进行通知，但不同供应商的请求地址、Header、Body 格式各不相同，且业务系统只关心通知能否被稳定可靠地送达。当前没有统一的出站通知投递服务，业务系统需要自行处理超时、重试、死信等复杂问题。因此需要构建一个内部 **Notification Gateway** 服务，统一接收、持久化并可靠投递外部 HTTP 通知请求。

## What Changes

- 新增 Notification Gateway 核心服务，提供统一的 HTTP API 接收业务系统的通知请求。
- 引入基于数据库的持久化通知队列，支持异步调度、指数退避重试、死信队列和人工重放。
- 引入 `vendorKey` 维度的供应商配置管理，支持运行时新增/修改 vendor 而无需发版。
- 引入幂等机制，基于业务方提供的 `idempotencyKey` 防止重复受理和重复投递。
- 引入租约锁（lease lock）机制，保证多 worker / 多实例环境下不会并发处理同一条通知。
- 引入按 vendor 的出站限流和熔断机制，保护下游供应商并减少无效投递。
- 引入基础可观测性：结构化日志、Micrometer 指标、DLQ 告警。

## Capabilities

### New Capabilities

- `notification-acceptance`: 接收业务系统提交的通知请求，校验参数，写入持久化队列，并基于幂等键返回已接受/已存在/死信等状态。
- `reliable-delivery`: 通过 Scheduler + Worker 模型异步投递通知，支持指数退避重试、错误分类、死信队列和人工重放。
- `vendor-config-management`: 管理每个 vendor 的 endpoint、method、headers、body 模板、超时、重试策略、限流与幂等键位置等配置。
- `delivery-resilience`: 提供按 vendor 的令牌桶限流、熔断器（CLOSED/OPEN/HALF_OPEN）以及锁超时恢复能力。
- `observability-alerting`: 暴露通知生命周期日志、Micrometer 指标，并在通知进入 DLQ 或 vendor 失败率过高时触发异步告警。

### Modified Capabilities

- 无（本项目为从零构建的 MVP，未修改既有能力）。

## Impact

- 新增 `api`、`application`、`domain`、`infrastructure` 四层 Java 包结构。
- 新增数据库表：`notification_request`、`idempotency_record`、`vendor_config`。
- 新增对外 REST API：`POST /api/v1/notifications`、`GET /api/v1/notifications/{requestId}`。
- 新增 Admin REST API：死信重放、vendor 配置 CRUD。
- 新增 Spring Boot 启动配置、`application.yml`、Docker Compose（可选 PostgreSQL）。
- 引入 Bucket4j、Resilience4j、WireMock、Testcontainers 等测试与实现依赖。
