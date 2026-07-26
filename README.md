# Notification Gateway

> **RightCapital AI Coding 作业** — 内部通知网关的设计与实现
>
> 接收内部业务系统的 HTTP 通知请求，持久化后**可靠投递**到外部供应商 HTTP(S) API。

---

## 1. 问题理解

企业内部多个业务系统在关键事件发生时，需要调用外部供应商的 HTTP(S) API 进行通知。典型场景：

- 用户通过第三方广告系统引流并成功注册后，通知对应的广告系统
- 用户订阅付款成功后，通知 CRM 系统更改 Contact 状态
- 用户购买商品后，通知库存系统进行库存变更

这些外部供应商的 API 在请求地址、Header、Body 格式上各不相同；而内部业务系统只关心"通知能够被稳定、可靠地送达"，并不关心外部 API 的具体返回值。

因此，本系统的核心定位是：

> **一个带持久化和重试能力的出站 HTTP 通知投递服务（Outbound Notification Gateway）。**

业务系统提交通知 → 本系统持久化 → 异步组装 HTTP 请求 → 投递到 vendor → 失败则按策略重试 → 最终成功或进入死信。

---

## 2. 系统边界

### 2.1 本系统解决的问题 ✅

1. **接收并持久化通知请求**：提供 HTTP API，写入持久化存储后立即返回"已接受"
2. **按 vendor 路由与格式化**：根据 `vendorKey` 查找配置，将业务 payload 转换为 vendor 要求的 HTTP 请求格式
3. **异步可靠投递**：Worker 异步发送 HTTP 请求，失败时按指数退避重试
4. **幂等控制**：`vendorKey + idempotencyKey` 双层幂等，已成功的通知不重复投递
5. **死信处理**：超过最大重试次数或不可重试错误进入 DLQ，支持人工重放
6. **per-vendor 限流**：令牌桶保护下游 vendor 不被突发流量压垮
7. **熔断降级**：vendor 长期不可用时自动熔断，减少无效投递尝试
8. **并发控制**：数据库租约锁（CAS UPDATE）防止多 Worker 抢同一条记录
9. **崩溃恢复**：服务重启后自动恢复未完成的投递任务
10. **可观测性**：Micrometer 指标 + MDC 结构化日志 + Webhook 告警

### 2.2 本系统明确不解决的问题 ❌

| 问题 | 不解决的原因 |
|------|-------------|
| **Vendor 返回值的业务处理** | 需求明确业务系统不关心外部 API 返回值 |
| **Vendor 侧的 exactly-once 保证** | 跨系统 HTTP 无法经济地实现，本系统只透传幂等键 |
| **企业级密钥管理（KMS/Vault）** | 第一版简单配置即可，非本次作业重点 |
| **多租户、多 Region** | 需求未涉及，避免过早引入复杂度 |
| **复杂工作流编排 / Saga** | 不是通知系统的职责范围 |
| **实时流式处理** | DB 队列足以满足 MVP 的可靠性和吞吐量要求 |

---

## 3. 整体架构

### 3.1 架构概览

采用"**接收即持久化、异步投递、状态机驱动**"的架构：

```text
业务系统 ──POST──▶ Notification API ──▶ 持久化存储 (通知队列)
                        │                      │
                        ▼                      ▼
                  返回 202 Accepted      Scheduler 定时拉取
                                              │
                                              ▼
                                  ┌─ Worker (租约锁领取) ─┐
                                  │  限流 → 熔断 → 组装   │
                                  │  → HTTP 发送 → 分类   │
                                  │  → 更新状态           │
                                  └───────┬───────────────┘
                                          │
                          ┌───────┬───────┼───────┬───────┐
                          ▼       ▼       ▼       ▼       ▼
                       SUCCESS  FAILED  DEAD_LETTERED  429
                          │       │        │           │
                          │   重试调度  告警+重放   Retry-After
                          └───────┴────────┴───────────┘
```

### 3.2 分层设计

```text
api ────────▶ application ────────▶ domain ◀──────── infrastructure
                  │                    │
                  └────────────────────┘
                       事务边界在 application 层
```

| 层级 | 职责 |
|------|------|
| **API 层** | HTTP 接口、参数校验、DTO 转换 |
| **Application 层** | 用例编排、事务边界、Worker 调度 |
| **Domain 层** | 核心业务规则、状态机、幂等判断、重试策略、错误分类（零框架依赖） |
| **Infrastructure 层** | JPA 持久化、JDK HttpClient、Bucket4j 限流、Resilience4j 熔断、Webhook 告警 |

### 3.3 核心状态机

```text
                         ┌──────────┐
              ┌──────────│ PENDING  │◀──────────┐
              │          │ 等待调度  │           │
              │          └────┬─────┘           │
              │      Worker领取│                │
              │          ┌────▼─────┐           │
              │          │ SENDING  │           │
              │          │ 正在投递  │           │
              │          └────┬─────┘           │
              │     ┌─────────┼─────────┐       │
              │     ▼         ▼         ▼       │
              │ ┌───────┐ ┌───────┐ ┌────────┐  │
              └─┤SUCCESS│ │FAILED │ │DEAD    │  │
                └───────┘ └───┬───┘ │LETTERED│  │
                              │     └────────┘  │
                              │ 计算nextRetryAt  │
                              │ attemptCount+1  │
                              └─────────────────┘
```

---

## 4. 关键工程决策与取舍

### 4.1 投递语义：At-least-once

**决策**：采用 **at-least-once（至少一次）** 投递语义。

**原因**：
- 跨系统 HTTP 调用无法经济地保证 exactly-once——vendor 侧不可控，两阶段提交不可行
- 业务方可通过幂等键和自身幂等设计来消化重复通知
- at-least-once 在可靠性和实现复杂度之间取得了最佳平衡

**代价**：存在极小概率的重复投递（Worker 崩溃时 HTTP 请求已发出但状态未更新），需在文档中明确。

### 4.2 通知队列：DB 表 + Scheduler 拉取

**决策**：MVP 使用数据库表作为通知队列，而不是 Kafka / RocketMQ / MetaQ。

**原因**：
- DB 队列足以满足 MVP 的可靠性和吞吐量要求
- 状态机、幂等、重试调度天然适合关系型存储表达
- 减少外部依赖——一条命令即可启动整个系统，适合作业展示
- 天然持久化，重启不丢数据

**未选方案**：Kafka/MetaQ 引入运维成本，单实例作业不需要；但其高吞吐、低延迟优势在流量显著增长后会成为演进方向。

### 4.3 分布式锁：DB 租约锁（CAS UPDATE）

**决策**：使用数据库 CAS UPDATE 实现租约锁，而非 Redis 分布式锁。

**原因**：
- 少一个中间件依赖
- 租约 + 锁超时恢复已覆盖 Worker 崩溃场景
- CAS UPDATE 对单实例足够；多实例场景下数据库行锁同样有效

**实现**：
```sql
UPDATE notification_request
SET status = 'SENDING', locked_by = :workerId,
    locked_until = :now + leaseDuration
WHERE id = :id AND status IN ('PENDING', 'FAILED')
  AND next_retry_at <= :now AND locked_until <= :now
```
Worker 崩溃后锁自然过期（默认 60s），其他 Worker 可接管。

### 4.4 幂等设计：双层幂等

**决策**：`NotificationRequest` 和 `IdempotencyRecord` 为两张独立表。

| 层级 | 责任方 | 说明 |
|------|--------|------|
| 生成幂等键 | 业务系统 | 业务方最清楚"哪两个通知代表同一业务事件" |
| 本地去重 | 本系统 | `vendorKey + idempotencyKey` 唯一，已 SUCCESS 不重复投递 |
| 重试透传 | 本系统 | 同一通知的所有重试使用同一 `idempotencyKey` |
| Vendor 去重 | Vendor | 本系统仅提供条件，不承诺 vendor 一定去重 |

**为何不合并为一张表**：生命周期不同（通知可归档、幂等需保留窗口）；查询模式不同（调度范围扫描 vs 去重点查）；数据规模不同。

**DLQ 重复提交处理**：若原通知已在 DLQ，业务方用同一 key 再次提交 → 返回 409，要求走人工重放 API。

**受理时的四分支判断**：业务方提交 `(vendorKey, idempotencyKey, payload)` 后，系统按以下决策树处理：

```text
查询 IdempotencyRecord(vendorKey, idempotencyKey)
  ├── 不存在 → 创建 NotificationRequest + IdempotencyRecord → 返回 202
  ├── 状态 = SUCCESS → 返回 200 + 原 requestId（不重复投递）
  ├── 状态 = PENDING/FAILED → 返回 200 + 当前状态（不创建新记录）
  └── 状态 = DEAD_LETTERED → 返回 409（拒绝自动重试，要求人工重放）
```

> **为什么 DEAD_LETTERED 要返回 409 而不是自动重试？** 进入 DLQ 的通知已经过最大重试次数仍未成功，说明存在系统性问题（如 vendor 接口变更、认证失效）。自动重试只会浪费资源并再次进入 DLQ，应人工确认问题已修复后再重放。

### 4.5 重试策略：指数退避 + 等分抖动

**决策**：`nextDelay = min(baseDelay × 2^attempt + jitter, maxDelay)`

- 可重试：网络超时、连接失败、5xx、429
- 不可重试：4xx（除 429 外）→ 直接进 DLQ
- 429 特殊处理：优先采纳 `Retry-After` 头作为下次重试时间的 hint
- 抖动策略：等分抖动（equal jitter），避免惊群效应

### 4.6 限流与熔断：配置即数据

**决策**：限流和熔断参数作为 `VendorConfig` 的一部分，支持运行时手动调整。

- **限流**：Bucket4j 令牌桶（capacity=burst, refillGreedy），per-vendor 隔离
- **熔断**：Resilience4j CircuitBreaker，支持 AUTO / FORCE_OPEN / FORCE_CLOSED 三种模式
- **自动重建**：配置变更时（如 QPS 调整），限流桶和熔断器自动重建，无需重启
- **被限流/熔断阻挡的记录**：保持 PENDING 状态，不增加 attemptCount

**维度对比**：

| 机制 | 触发条件 | 行为 | attemptCount |
|------|---------|------|-------------|
| 限流 | 令牌桶空 | 推迟 `nextRetryAt` | 不增加 |
| 熔断 | 连续失败率 ≥ 阈值 | 跳过投递，等恢复期 | 不增加 |
| 重试 | 单次投递失败 | 计算退避时间 | +1 |

> **为什么限流/熔断不增加 attemptCount？** attemptCount 衡量的是"投递尝试失败次数"，而限流和熔断是主动保护机制——通知本身没有问题，只是当前不允许发送。若将它们也计入 attemptCount，会导致被保护的通知更快耗尽重试配额进入 DLQ，这与限流/熔断的保护目的相悖。

### 4.7 错误分类：投递结果的领域建模

一次 HTTP 投递的结果不只是"成功或失败"——我们将它抽象为四种**领域结果类型**，而非直接暴露 HTTP 状态码：

| 结果类型 | 触发条件 | 状态转换 | attemptCount |
|---------|---------|---------|-------------|
| **Success** | 2xx 响应 | SENDING → SUCCESS | — |
| **RetryableFailure** | 网络超时、连接失败、DNS 失败、5xx | SENDING → FAILED | +1 |
| **RateLimited** | 429 Too Many Requests | SENDING → FAILED | +1 |
| **NonRetryableFailure** | 4xx（除 429）、SSL 握手失败、模板渲染失败 | SENDING → DEAD_LETTERED | 不增加 |

> **为什么要把错误分类放在 domain 层？** "哪些错误应该重试"是一个业务决策，不是 HTTP 实现细节。比如，有的 vendor 用 400 表示"参数错误"（不应重试），有的用 400 表示"暂时不可用"（应该重试）。当前 MVP 按 HTTP 标准语义分类，但 `DeliveryResultClassifier` 是 domain 层接口，未来可以按 vendor 定制分类逻辑，而不会影响上层编排。

**429 特殊处理**：`RateLimited` 独立于 `RetryableFailure`，因为它的重试时间不应由指数退避决定，而应优先采纳 vendor 返回的 `Retry-After` 头。这个 hint 通过 `Optional<Duration>` 传入 `RetryPolicy.calculateNextRetry()`，由重试策略统一决策——保持重试策略的单一职责，而非在分类器中直接计算时间戳。

### 4.8 租约超时恢复：处理 Worker 崩溃

Worker 获取租约锁后进入 SENDING 状态进行 HTTP 投递。如果 Worker 进程在投递过程中崩溃：

1. 该记录的 `lockedUntil` 不会被动释放——只能等待自然过期（默认 60s）
2. 独立的 `StaleLockRecoveryAppService` 定时扫描 `status=SENDING AND lockedUntil <= now` 的记录
3. 将这些记录视为一次失败尝试：`attemptCount += 1`，计算 `nextRetryAt`，释放锁
4. 若已超过最大重试次数，直接进入 DLQ

> **为什么选择"等待过期"而非"心跳检测"？** 心跳检测需要在 Worker 和记录之间维持活跃连接，增加实现复杂度和 DB 写入压力。租约过期是"惰性恢复"——Worker 崩溃后最多损失一个租约周期（60s）的投递时效，这个代价对于异步通知场景可以接受。租约时长设计为 HTTP 超时（30s）+ 处理时间（5s）+ 缓冲（25s）= 60s。

**与重复投递的关系**：Worker 崩溃时 HTTP 请求可能已发出（vendor 已处理）但状态尚未更新为 SUCCESS。恢复后该记录会被重新投递，产生一次**重复调用**。这正是我们选择 at-least-once 语义的原因——通过幂等键让 vendor 侧识别重复。

### 4.9 事务边界：TransactionTemplate 与 HTTP 调用隔离

**决策**：使用 `TransactionTemplate` 显式管理事务，而非 `@Transactional` 注解。

**原因**：
- 同类内自调用 `@Transactional` 不生效（Spring AOP 代理限制），而投递流程中 `tryDispatch()` 需要调用本类的 `persistRetryOrDeadLetter()`——用注解会产生"以为有事务、实际没有"的隐蔽 bug
- `TransactionTemplate` 使事务边界在代码中**显式可见**，维护者无需查注解就能看到哪些操作在同一事务中

**HTTP 调用必须在事务外**：事务中不能包含 HTTP 调用——因为 HTTP 请求发出后、事务提交前如果 DB 回滚，已发出的通知无法撤销；反过来如果 HTTP 成功但事务提交失败，vendor 收到了通知但本系统认为未投递。正确顺序是：

```text
1. 开事务：acquireLock (CAS UPDATE)
2. 关事务
3. 限流检查 → 熔断检查 → 组装请求 → HTTP 发送（无事务）
4. 开事务：根据 HTTP 结果更新状态 + 幂等记录
5. 关事务
6. 事务提交成功后：异步触发告警（避免回滚导致误报）
```

> **为什么步骤 4 的事务要在步骤 3 之后开？** 如果步骤 3 和步骤 4 在同一事务中，事务持有数据库连接的时间 = HTTP 调用时长。若 vendor 响应慢（30s），数据库连接池会被耗尽，其他 Worker 无法获取锁。将 HTTP 调用放在事务之间，连接仅在实际写入时短暂持有。

### 4.10 VendorConfig 缓存策略

**决策**：使用 `ConcurrentHashMap` 作为内存缓存，Admin API 写入后主动刷新，不引入 Redis。

**原因**：
- `VendorConfig` 是典型的读多写少场景（每次投递都读，但配置变更频率极低）
- 配置变更需要立即生效（如运维临时调高 QPS 或强制熔断），不能等定时刷新
- `ConcurrentHashMap` 零依赖，单实例足够；多实例场景下可演进为 Redis 缓存 + 发布/订阅通知

**实现**：`VendorConfigCache` 维护 `ConcurrentHashMap<String, VendorConfig>`，提供 `get(vendorKey)` 和 `refresh(vendorKey)` 方法。Admin API 的 create/update/delete 操作在 DB 写入成功后调用 `refresh`，Worker 每次投递前调用 `get`（纯内存读取，微秒级）。

> **为什么不直接用 DB 查询而需要缓存？** Worker 每次投递都要读 vendor 配置（endpoint、超时、模板、限流参数等）。若每次投递都查 DB，在每秒数十次投递的场景下，配置查询会成为不必要的 DB 热点。缓存让配置读取变为纯内存操作，DB 只在配置变更时写入。

### 4.11 告警设计：异步 + 冷却收敛

**决策**：告警异步触发不阻塞主流程，按 `事件类型 + vendorKey` 做冷却收敛（默认 5 分钟），防止告警风暴。

**为什么需要冷却收敛**：假设 vendor-a 的接口突然全部返回 500，系统会产生连续的 DLQ 事件——如果不收敛，每条通知进 DLQ 都发一次 Webhook，几分钟内可能发出数百条告警，反而淹没真正需要关注的信息。冷却窗口保证同一 vendor 的同类告警在窗口内只触发一次。

**告警通道取舍**：MVP 只配一个全局 Webhook URL；若 URL 未配置，降级为 ERROR 级别日志。不引入 per-vendor 告警渠道——vendor 数量少时，全局 Webhook + 日志兜底足够；per-vendor 渠道在 vendor 数量超过两位数且需要差异化通知时再引入。

---

## 5. 可靠性与失败处理策略

| 失败场景 | 处理策略 |
|---------|---------|
| 网络超时 / 连接失败 | 标记为可重试，按指数退避调度下次重试 |
| Vendor 返回 5xx | 可重试，纳入退避调度 |
| Vendor 返回 429 | 可重试，优先尊重 `Retry-After`，否则按退避策略 |
| Vendor 返回 4xx（除 429） | 不可重试，直接进 DLQ |
| Vendor 长期不可用 | 熔断打开，新通知暂存不投递，恢复期后自动 HALF_OPEN 探测 |
| Worker 崩溃 | 租约锁过期后（60s），其他 Worker 自动接管 |
| 服务重启 | Scheduler 无状态，重启后自动扫描 PENDING/FAILED/SENDING（锁过期）记录 |
| 超过最大重试次数 | 进入 DLQ，触发 Webhook 告警，等待人工重放 |
| 重复提交（已 SUCCESS） | 返回 200 + 原 requestId，不重复投递 |
| 重复提交（已在 DLQ） | 返回 409，拒绝自动重试，要求走人工重放 API |
| 告警发送失败 | 降级为 ERROR 级别日志 |

---

## 6. AI 使用说明

> 完整记录见 [docs/ai-usage.md](docs/ai-usage.md)，以下是摘要。

### 6.1 协作方式

整个项目按"讨论 → 架构文档 → 技术设计 → 精简计划 → TDD 实现"五阶段推进：

1. **讨论阶段（人工主导）**：先不写代码，围绕投递语义、幂等、重试、锁超时恢复、DLQ、限流、熔断、告警、系统边界逐项讨论，人工拍板关键取舍
2. **文档沉淀（AI 起草，人工确认）**：架构设计、技术设计、精简实施计划均由 AI 生成初稿，人工逐条确认和修改
3. **TDD 实现（AI 执行，逐任务提交）**：17 个任务，每个任务先写失败测试再实现，通过全套测试后单独 commit

### 6.2 AI 帮助最大的点

- **样板代码批量产出**：JPA Entity/Repository/DTO/Controller 等结构化代码，AI 生成速度快且风格一致
- **边界 case 枚举**：幂等四种分支、熔断三种模式、重试抖动封顶等测试场景清单
- **问题定位**：`@DataJpaTest` 中 bulk JPQL 绕过一级缓存、WireMock thin 包缺 Jetty、Spring 多构造器歧义等问题的根因定位
- **E2E 场景编排**：WireMock 状态机（Scenario）+ Awaitility 组合，5 个端到端场景一次通过

### 6.3 AI 给出过但未被采纳的建议

| AI 建议 | 不采纳原因 |
|---------|-----------|
| 引入 Kafka/RabbitMQ | MVP 流量和复杂度不需要；DB 队列天然持久化，一条命令即可启动 |
| 引入 Redis（分布式锁/限流/熔断共享） | 单实例 MVP 不需要；DB 租约锁已覆盖 Worker 崩溃场景 |
| 引入 Quartz 调度框架 | Spring `@Scheduled` + 线程池足够，不需要额外的调度持久化 |
| 引入 Mustache/SpEL 模板引擎 | `{{var}}` 占位符足够覆盖当前 vendor 格式差异；复杂模板非本次作业重点 |
| Vendor 配置用 YAML 文件管理 | YAML 无法运行时动态修改；DB + Admin API 支持不重启接入新 vendor |
| 投递成功后的通知回调 | 需求明确"业务系统不关心外部 API 返回值"，增加回调链路反而引入复杂性 |
| FAILED 到 PENDING 中间态的显式恢复任务 | 合并为失败后直接回到 PENDING，减少一次无意义的状态迁移，语义等价 |
| RestClient 作为 HTTP 客户端 | 不支持 per-request 读超时，而每个 vendor 超时可能不同；JDK HttpClient 的 `HttpRequest.timeout()` 精确匹配需求 |
| Exactly-once 投递语义 | 跨系统 HTTP 无法经济地保证；vendor 侧不可控，两阶段提交不可行 |

### 6.4 人工做出的关键决策（及原因）

| 决策 | 原因 |
|------|------|
| **投递语义选择 at-least-once** | 外部 vendor 无法控制，exactly-once 成本不可接受；at-least-once + 幂等去重是工程最优解 |
| **幂等必须进入 MVP** | 没有幂等的 at-least-once 就是"可重复投递"而非"可靠投递"；且幂等记录的结构设计影响 DB schema 和状态机 |
| **429 Retry-After 传递方案** | 选 `Optional<Duration> hint` 传入 `RetryPolicy.calculateNextRetry`，而非在分类器里直接算好时间戳——保持重试策略的单一职责 |
| **限流/熔断作为 VendorConfig 一部分** | 配置即数据，支持运行时手动调整（含 FORCE_OPEN/FORCE_CLOSED）；配置变更时自动重建限流桶和熔断器 |
| **告警选全局 Webhook（非 per-vendor）** | MVP 简化；冷却窗口收敛防风暴（同 `type+vendor` 5 分钟内只发一次） |
| **Domain 层零 Spring 注解** | 保持 domain 层可独立单元测试，统一在 `DomainServiceConfig` 中装配 |
| **TransactionTemplate 而非 @Transactional** | 同类内自调用 `@Transactional` 不生效，显式模板更诚实；HTTP 调用明确保持在事务外 |
| **两表分开（NotificationRequest + IdempotencyRecord）** | 生命周期、查询模式、数据规模均不同；合并会引入隐式耦合 |

---

## 7. 取舍与演进

### 7.1 第一版明确不做但 AI 建议过的"过度设计"

| 过度设计 | 不采纳原因 | 演进触发条件 |
|---------|-----------|-------------|
| Kafka / RocketMQ | MVP 流量不需要，DB 队列足够 | 吞吐量达到 DB 轮询瓶颈，或延迟要求 < 100ms |
| Redis 分布式协调 | 单实例够用，DB 租约锁已覆盖崩溃场景 | 多实例部署且需要全局限流/熔断视图 |
| 企业级 KMS / Vault | 密钥管理是独立领域，MVP 简单配置即可 | 安全审计要求或密钥轮换需求 |
| Exactly-once 投递 | 跨系统 HTTP 成本过高，收益不明确 | 业务方明确要求且愿意承担 vendor 改造成本 |
| 复杂模板引擎 | `{{var}}` 占位符足够 | vendor 请求需要条件分支、嵌套渲染 |
| per-vendor 告警渠道 | 运维成本高 | vendor 数量超过两位数且需要差异化通知 |

### 7.2 MVP 的已知限制

- **单实例设计**：限流桶和熔断器在内存中，多实例各自独立计数（但 DB 租约锁本身支持多实例）
- **无优先队列**：所有通知按 `nextRetryAt` 公平调度
- **告警单通道**：仅全局 Webhook，无值班路由 / 告警升级
- **管理端无鉴权**：Admin API 假设在内网环境
- **模板仅支持 Body 和 Header**：URL 不支持模板

### 7.3 未来演进路径

1. **消息队列化**：DB 队列 → Kafka/RocketMQ，DB 保留幂等和查询
2. **Redis 缓存层**：多实例共享 VendorConfig、限流状态、熔断状态
3. **优先级队列**：按业务事件类型设置投递优先级
4. **密钥管理**：接入 Vault / AWS Secrets Manager
5. **可视化运维**：管理后台展示通知状态、DLQ、vendor 配置
6. **多租户隔离**：按租户维度隔离队列和限流资源

---

## 8. 快速开始

### 一键运行

```bash
./mvnw spring-boot:run          # 默认 H2 file 模式（./data/，重启可恢复）
```

运行测试：

```bash
./mvnw test                     # 109 个测试（单元/集成/E2E）
```

### API 示例

```bash
# 1. 注册 vendor
curl -X POST localhost:8080/admin/v1/vendor-configs \
  -H 'Content-Type: application/json' \
  -d '{
    "vendorKey": "vendor-a",
    "endpoint": "https://api.vendor-a.com/notify",
    "method": "POST",
    "headers": {"Authorization": "Bearer token"},
    "bodyTemplate": "{\"user\":\"{{userId}}\",\"msg\":\"{{msg}}\"}",
    "timeoutMs": 30000,
    "retryPolicy": {"maxAttempts": 10, "baseDelayMs": 1000, "maxDelayMs": 3600000},
    "rateLimit": {"qps": 10, "burst": 20},
    "circuitBreaker": {"mode": "AUTO", "failureRateThreshold": 50,
      "minCalls": 10, "cooldownSeconds": 60, "halfOpenMaxCalls": 3},
    "idempotencyKeyLocation": "HEADER",
    "idempotencyKeyName": "Idempotency-Key"
  }'

# 2. dry-run 预览渲染结果（不发 HTTP）
curl -X POST localhost:8080/admin/v1/vendor-configs/vendor-a/preview \
  -H 'Content-Type: application/json' \
  -d '{"payload":{"userId":"u1","msg":"hi"}}'

# 3. 提交通知 → 202 Accepted
curl -X POST localhost:8080/api/v1/notifications \
  -H 'Content-Type: application/json' \
  -d '{"vendorKey":"vendor-a","idempotencyKey":"order-12345",
       "payload":{"userId":"u1","msg":"hi"}}'

# 4. 查询投递状态
curl localhost:8080/api/v1/notifications/{requestId}

# 5. DLQ 列表 / 人工重放
curl localhost:8080/admin/v1/dead-letters
curl -X POST localhost:8080/admin/v1/dead-letters/{requestId}/retry
```

### 切换 PostgreSQL

```bash
docker compose up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres
```

### 监控端点

`/actuator/health`、`/actuator/metrics`（`notifications_received_total` / `delivered` / `dead_lettered` / `pending_depth` / `dlq_depth`）

---

## 9. 技术栈

| 层次 | 选型 | 说明 |
|------|------|------|
| 框架 | Spring Boot 3.x / Java 21 | 生态成熟 |
| 数据访问 | Spring Data JPA + Hibernate | JPA 适合状态机表达 |
| 数据库 | H2（默认）+ PostgreSQL 兼容 | H2 一键启动，PostgreSQL 生产就绪 |
| HTTP 客户端 | JDK `HttpClient` | per-request 超时支持 |
| 调度 | Spring `@Scheduled` + 线程池 | 足够支撑 DB 队列拉取 |
| 限流 | Bucket4j | 标准令牌桶实现 |
| 熔断 | Resilience4j CircuitBreaker | Spring 生态标准 |
| 模板 | Apache Commons Text `StringSubstitutor` | `{{var}}` 占位符替换 |
| 测试 | JUnit 5 + Mockito + WireMock + Testcontainers | 单元/集成/E2E 全覆盖 |
| 构建 | Maven Wrapper | 零预装依赖 |

---

## 10. 项目结构

```text
src/main/java/com/examine/
├── api/                    # HTTP 接口层
│   ├── controller/         # NotificationController, AdminController
│   └── dto/                # 请求/响应 DTO
├── application/            # 应用服务层（用例编排、事务边界）
│   └── service/            # DeliveryAppService, DeadLetterReplayAppService 等
├── domain/                 # 领域层（零框架依赖）
│   ├── model/              # 实体、值对象、状态机
│   ├── service/            # IdempotencyService, VendorRequestAssembler, Classifier
│   └── repository/         # 仓库接口（domain 层定义）
└── infrastructure/         # 基础设施层
    ├── persistence/        # JPA Entity/Repository 实现
    ├── http/               # JDK HttpClient 适配器
    ├── ratelimit/          # Bucket4j 限流
    ├── circuitbreaker/     # Resilience4j 熔断
    ├── scheduling/         # Scheduler / Worker
    ├── alert/              # Webhook 告警
    └── config/             # Spring 配置、Domain 服务装配
```

---

## 11. 文档索引

| 文档 | 内容 |
|------|------|
| [docs/design.md](docs/design.md) | 架构设计：问题理解、系统边界、关键决策的详细论证 |
| [docs/technical-design.md](docs/technical-design.md) | 技术设计：分层、领域模型、状态机、API 契约、中间件取舍 |
| [docs/ai-usage.md](docs/ai-usage.md) | AI 使用说明：协作方式、帮助最大的点、未采纳建议、人工决策 |
| [openspec/changes/notification-gateway-mvp/](openspec/changes/notification-gateway-mvp/) | OpenSpec 变更提案与验收标准 |

---

## 12. 测试覆盖

| 层级 | 框架 | 覆盖内容 |
|------|------|---------|
| 单元测试 | JUnit 5 + Mockito | Domain 服务、重试策略、错误分类、幂等判断 |
| 集成测试 | @DataJpaTest / @SpringBootTest | Repository CAS 锁、事务边界、Scheduler 调度 |
| E2E 测试 | WireMock + Awaitility | 5 场景：成功、幂等去重、500 重试成功、400→DLQ→重放成功、429 Retry-After |
| Schema 兼容 | Testcontainers + PostgreSQL | 验证 DDL 与 PostgreSQL 兼容（无 Docker 自动跳过） |

---

*本 README 是 RightCapital AI Coding 作业的提交文档，包含问题理解、架构设计、关键工程决策与取舍说明、AI 使用说明。代码实现为最小可行实现（MVP）。*
