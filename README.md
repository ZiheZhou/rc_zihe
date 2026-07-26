# Notification Gateway

内部通知网关：接收内部业务系统的 HTTP 通知请求，持久化后**可靠投递**到外部 vendor HTTP(S) API。

- **至少一次投递**：DB 队列 + 调度拉取 + 租约锁，指数退避重试（429 采纳 Retry-After）
- **幂等去重**：`vendorKey + idempotencyKey` 双层幂等（本地记录 + 传递幂等键给 vendor）
- **韧性**：per-vendor 令牌桶限流 + 熔断器（AUTO/FORCE_OPEN/FORCE_CLOSED）
- **死信**：不可重试/耗尽重试进 DLQ，告警 + 人工重放
- **快速接入**：vendor 配置（endpoint/模板/重试/限流/熔断）即数据，Admin API 管理 + dry-run 预览

## 一键运行

```bash
./mvnw spring-boot:run          # 默认 H2 file 模式（./data/，重启可恢复）
```

运行测试：

```bash
./mvnw test                     # 全部单元/集成/E2E 测试（E2E 场景见 NotificationFlowE2ETest）
```

## API 示例

```bash
# 1. 注册 vendor
curl -X POST localhost:8080/admin/v1/vendor-configs -H 'Content-Type: application/json' -d '{
  "vendorKey": "vendor-a",
  "endpoint": "https://api.vendor-a.com/notify",
  "method": "POST",
  "headers": {"Authorization": "Bearer token"},
  "bodyTemplate": "{\"user\":\"{{userId}}\",\"msg\":\"{{msg}}\"}",
  "timeoutMs": 30000,
  "retryPolicy": {"maxAttempts": 10, "baseDelayMs": 1000, "maxDelayMs": 3600000},
  "rateLimit": {"qps": 10, "burst": 20},
  "circuitBreaker": {"mode": "AUTO", "failureRateThreshold": 50, "minCalls": 10, "cooldownSeconds": 60, "halfOpenMaxCalls": 3},
  "idempotencyKeyLocation": "HEADER",
  "idempotencyKeyName": "Idempotency-Key"
}'

# 2. dry-run 预览渲染结果（不发 HTTP）
curl -X POST localhost:8080/admin/v1/vendor-configs/vendor-a/preview \
  -H 'Content-Type: application/json' -d '{"payload":{"userId":"u1","msg":"hi"}}'

# 3. 提交通知 → 202（重复提交同 idempotencyKey → 200 同 requestId；已 DLQ → 409）
curl -X POST localhost:8080/api/v1/notifications -H 'Content-Type: application/json' -d '{
  "vendorKey": "vendor-a",
  "idempotencyKey": "order-12345",
  "payload": {"userId": "u1", "msg": "hi"}
}'

# 4. 查询投递状态
curl localhost:8080/api/v1/notifications/{requestId}

# 5. DLQ 列表 / 人工重放
curl localhost:8080/admin/v1/dead-letters
curl -X POST localhost:8080/admin/v1/dead-letters/{requestId}/retry
```

监控：`/actuator/health`、`/actuator/metrics`（`notifications_received_total` / `delivered` / `dead_lettered` / `pending_depth` / `dlq_depth`）。

## 切换 PostgreSQL

```bash
docker compose up -d                        # 启动 PostgreSQL（需本机 Docker）
./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres
```

`PostgresSchemaCompatibilityTest`（Testcontainers）验证 schema 兼容性，无 Docker 环境自动跳过。

## 文档

- 架构设计（问题理解/系统边界/关键决策）：[docs/design.md](docs/design.md)
- 技术设计（分层/领域模型/状态机/中间件取舍）：[docs/technical-design.md](docs/technical-design.md)
- OpenSpec 变更与验收标准：[openspec/changes/notification-gateway-mvp/](openspec/changes/notification-gateway-mvp/)
- AI 协作记录与实现偏离：[docs/ai-usage.md](docs/ai-usage.md)

## 关键取舍（摘要）

| 决策 | MVP 选择 | 未选方案及原因 |
|---|---|---|
| 投递队列 | DB 表 + Scheduler 拉取 | Kafka/MetaQ：引入运维成本，MVP 单实例作业展示不需要；DB 队列天然持久化、可演示重启恢复 |
| 分布式锁 | DB 租约锁（CAS UPDATE） | Redis：少一个中间件依赖；租约 + 锁超时恢复已覆盖 worker 崩溃场景 |
| 限流/熔断 | 本地 Bucket4j/Resilience4j | 集中式（Redis）：单实例足够，配置即数据支持运行时调整 |
| 告警 | 全局 webhook + ERROR 日志兜底 | per-vendor 渠道：MVP 简化，冷却窗口收敛防风暴 |
| 模板引擎 | `{{var}}` 占位符（Commons Text） | 完整模板引擎：非作业重点，保持最简 |

更完整的取舍论证见 `docs/technical-design.md` §11。
