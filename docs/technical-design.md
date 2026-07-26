# Notification Gateway 技术设计文档

> 本文档为 RightCapital AI Coding 作业的技术设计文档，承接 `docs/design.md` 的架构设计，进一步细化代码分层、领域模型、状态机、API 契约、核心机制等技术实现层面的设计。
> 
> 本文档仍不绑定具体框架/中间件选型（如 Spring/JPA/数据库等），仅描述逻辑层面的技术方案与接口边界。

---

## 1. 设计原则

1. **领域驱动分层**：`domain` 层独立，不依赖任何框架或基础设施。
2. **依赖倒置**：`domain` 层定义仓库接口，`infrastructure` 层实现。
3. **状态机驱动**：通知请求的生命周期由明确的状态机管理，所有状态转换都有唯一触发条件。
4. **幂等优先**：所有可能产生重复投递的路径都有幂等控制兜底。
5. **可观测内建**：每个关键状态转换都产生日志/事件，便于追踪和告警。

---

## 2. 代码分层

采用经典分层架构，模块依赖关系如下：

```text
api ─────────▶ application ─────────▶ domain ◀──────── infrastructure
                    │                    │
                    └────────────────────┘
                         事务边界在 application 层
```

### 2.1 各层职责

| 层级 | 包/模块 | 职责 |
|------|--------|------|
| **API 层** | `api.controller`, `api.dto`, `api.mapper` | 接收 HTTP 请求，参数校验，DTO 与领域对象转换，返回响应。 |
| **Application 层** | `application.service`, `application.command` | 编排领域服务，定义事务边界，处理具体用例。 |
| **Domain 层** | `domain.model`, `domain.service`, `domain.repository`, `domain.policy` | 核心业务规则、实体、值对象、状态机、领域服务接口、仓库接口。 |
| **Infrastructure 层** | `infrastructure.persistence`, `infrastructure.http`, `infrastructure.scheduling`, `infrastructure.config`, `infrastructure.rateLimit`, `infrastructure.circuitBreaker`, `infrastructure.alert` | 具体技术实现：数据库访问、HTTP 客户端、调度器、配置加载、限流、熔断、告警。 |

### 2.2 分层约束

- `domain` 不依赖 `api`、`application`、`infrastructure` 中的任何代码。
- `application` 不直接调用 `infrastructure`，只通过 `domain.repository` 接口。
- `api` 不直接调用 `domain`，通过 `application` 编排。
- `infrastructure` 通过依赖注入/配置向 `application` 提供服务。

---

## 3. 领域模型

### 3.1 核心实体

#### `NotificationRequest`（通知请求）

表达“一次投递任务”的完整生命周期。

```java
class NotificationRequest {
    RequestId id;                    // 本系统生成的唯一 ID
    VendorKey vendorKey;             // 目标 vendor
    IdempotencyKey idempotencyKey;   // 业务方提供的幂等键
    Payload payload;                 // 业务 payload（JSON 对象/字符串）
    Status status;                   // 状态机状态
    AttemptCount attemptCount;       // 已尝试次数
    NextRetryAt nextRetryAt;         // 下次可调度时间
    LeaseLock leaseLock;             // 租约锁（lockedBy + lockedUntil）
    CreatedAt createdAt;
    UpdatedAt updatedAt;
    DeliveredAt deliveredAt;         // 成功投递时间
    LastError lastError;             // 最后一次错误摘要
}
```

#### `IdempotencyRecord`（幂等记录）

表达“某个业务事件是否已被本系统受理及最终状态”。

```java
class IdempotencyRecord {
    IdempotencyRecordId id;          // 内部 ID
    VendorKey vendorKey;
    IdempotencyKey idempotencyKey;
    RequestId requestId;             // 关联的 NotificationRequest
    IdempotencyStatus status;        // PENDING / SUCCESS / FAILED / DEAD_LETTERED
    CreatedAt createdAt;
    ExpiresAt expiresAt;             // 幂等窗口过期时间
}
```

#### `VendorConfig`（供应商配置）

表达“如何向某个 vendor 发起请求”的全部配置。

```java
class VendorConfig {
    VendorKey vendorKey;
    Endpoint endpoint;
    HttpMethod method;
    Headers headers;
    BodyTemplate bodyTemplate;
    Timeout timeout;
    RetryPolicy retryPolicy;
    RateLimit rateLimit;
    IdempotencyKeyLocation idempotencyKeyLocation;
    IdempotencyKeyName idempotencyKeyName;
}
```

### 3.2 值对象

| 值对象 | 类型建议 | 说明 |
|--------|---------|------|
| `RequestId` | UUID / 雪花 ID | 本系统生成的唯一标识。 |
| `VendorKey` | String | vendor 唯一标识，如 `ad-platform-a`。 |
| `IdempotencyKey` | String | 业务方提供的幂等键。 |
| `Payload` | JSON / Map | 业务 payload，保持原始格式。 |
| `Status` | Enum | `PENDING`, `SENDING`, `SUCCESS`, `FAILED`, `DEAD_LETTERED`。 |
| `IdempotencyStatus` | Enum | 与 `Status` 对齐，但只记录最终受理状态。 |
| `AttemptCount` | int | 非负整数，从 0 开始。 |
| `NextRetryAt` | Instant / Timestamp | 下次可调度时间。 |
| `LeaseLock` | Object | 包含 `lockedBy`（worker 标识）和 `lockedUntil`（锁过期时间）。 |
| `Endpoint` | URL String | 目标地址。 |
| `HttpMethod` | Enum | GET / POST / PUT / PATCH / DELETE。 |
| `Headers` | Map<String, String> | 默认 headers。 |
| `BodyTemplate` | String | 模板字符串，含占位符。 |
| `Timeout` | Duration | HTTP 连接/读取超时。 |
| `RetryPolicy` | Object | 最大重试次数、基础延迟、最大延迟、抖动策略。 |
| `RateLimit` | Object | QPS、并发数等限流参数。 |
| `IdempotencyKeyLocation` | Enum | `HEADER` / `BODY`。 |
| `IdempotencyKeyName` | String | 幂等键在 header 或 body 中的字段名。 |

---

## 4. 状态机

### 4.1 状态定义

| 状态 | 含义 |
|------|------|
| `PENDING` | 已持久化，等待被 worker 调度投递。 |
| `SENDING` | 已被某个 worker 租约锁定，正在投递中。 |
| `SUCCESS` | 已成功投递到 vendor。 |
| `FAILED` | 最近一次投递失败，已计算下次重试时间，等待重新进入 PENDING。 |
| `DEAD_LETTERED` | 超过最大重试次数，进入死信队列。 |

### 4.2 状态转换规则

```text
                    ┌─────────────────┐
        ┌──────────▶│     PENDING     │◀──────────┐
        │           │   等待调度       │            │
        │           └────────┬────────┘            │
        │                    │ worker 领取          │
        │                    │ (设置 lease lock)    │
        │                    ▼                     │
        │           ┌─────────────────┐            │
        │           │     SENDING     │            │
        │           │  正在投递        │            │
        │           └────────┬────────┘            │
        │                    │                     │
        │         ┌─────────┼─────────┐            │
        │         ▼         ▼         ▼            │
        │    ┌────────┐ ┌────────┐ ┌───────────┐   │
        └────┤ SUCCESS│ │ FAILED │ │ DEAD_LETTERED│
             └────────┘ └───┬────┘ └───────────┘
                            │ 计算 nextRetryAt
                            │ attemptCount + 1
                            └─────────────────────┘
```

### 4.3 各转换触发条件

| 转换 | 触发条件 | 动作 |
|------|---------|------|
| `PENDING → SENDING` | worker 通过 lease lock 领取记录 | 更新 `status=SENDING`，设置 `lockedBy` 和 `lockedUntil` |
| `SENDING → SUCCESS` | HTTP 投递成功（2xx） | 更新 `status=SUCCESS`，`deliveredAt=now`，释放锁，更新 `IdempotencyRecord.status=SUCCESS` |
| `SENDING → FAILED` | HTTP 投递失败且可重试（超时/5xx/429） | 更新 `status=FAILED`，`attemptCount+=1`，计算 `nextRetryAt`，释放锁 |
| `SENDING → FAILED` | 锁超时（worker 崩溃/失联） | 同上，视为一次失败尝试 |
| `SENDING → DEAD_LETTERED` | 投递失败且 `attemptCount >= maxAttempts` | 更新 `status=DEAD_LETTERED`，释放锁，更新 `IdempotencyRecord.status=DEAD_LETTERED`，触发告警 |
| `SENDING → DEAD_LETTERED` | 不可重试错误（4xx 除 429） | 立即进入死信，不增加 attemptCount |
| `FAILED → PENDING` | scheduler 扫描到 `nextRetryAt <= now` | 更新 `status=PENDING`，等待重新领取 |
| `DEAD_LETTERED → PENDING` | 人工调用重放接口 | 重置 `attemptCount=0` 或保留原值（按策略），`status=PENDING` |

---

## 5. API 契约

### 5.1 接收通知

```http
POST /api/v1/notifications
Content-Type: application/json
```

请求体：

```json
{
  "vendorKey": "ad-platform-a",
  "idempotencyKey": "user-123-register-20260726",
  "payload": {
    "userId": "123",
    "eventType": "REGISTER"
  }
}
```

响应：

| 场景 | HTTP 状态 | 响应体 |
|------|----------|--------|
| 新通知已接受 | 202 Accepted | `{ "requestId": "...", "status": "PENDING" }` |
| 幂等键已存在且 SUCCESS | 200 OK | `{ "requestId": "...", "status": "SUCCESS" }` |
| 幂等键已存在且处理中（PENDING/SENDING/FAILED） | 200 OK | `{ "requestId": "...", "status": "<current>" }` |
| 幂等键已在 DLQ | 409 Conflict | `{ "requestId": "...", "status": "DEAD_LETTERED", "message": "..." }` |
| 参数校验失败 | 400 Bad Request | `{ "error": "...", "field": "..." }` |
| vendorKey 不存在 | 400 Bad Request | `{ "error": "Vendor not found: ad-platform-a" }` |

### 5.2 查询通知状态

```http
GET /api/v1/notifications/{requestId}
```

响应：

```json
{
  "requestId": "...",
  "vendorKey": "ad-platform-a",
  "status": "SUCCESS",
  "attemptCount": 2,
  "nextRetryAt": null,
  "deliveredAt": "2026-07-26T10:00:00Z",
  "lastError": null
}
```

### 5.3 死信管理（Admin API）

```http
POST /admin/v1/dead-letters/{requestId}/retry
```

响应：

```json
{
  "requestId": "...",
  "status": "PENDING"
}
```

### 5.4 Vendor 配置管理（Admin API）

```http
POST   /admin/v1/vendor-configs
GET    /admin/v1/vendor-configs/{vendorKey}
PUT    /admin/v1/vendor-configs/{vendorKey}
DELETE /admin/v1/vendor-configs/{vendorKey}
```

---

## 6. Repository 接口设计

Repository 接口定义在 `domain` 层，由 `infrastructure` 层实现。

### 6.1 `NotificationRequestRepository`

| 方法 | 职责 |
|------|------|
| `save(request)` | 创建新的通知请求。 |
| `update(request)` | 更新通知请求状态、重试信息、锁信息等。 |
| `findById(id)` | 按 ID 查询。 |
| `findPendingForDispatch(now, limit)` | 查询可调度投递的 PENDING 记录。 |
| `findStaleSendingRecords(now, limit)` | 查询锁已超时的 SENDING 记录。 |
| `acquireLock(id, workerId, lockedUntil)` | **原子获取锁**：将 PENDING 记录更新为 SENDING 并设置租约锁，返回成功锁定的记录。 |
| `findByVendorAndStatus(vendorKey, status, pageable)` | 按 vendor 和状态查询。 |
| `findDeadLetters(vendorKey, pageable)` | 查询死信记录。 |

**关键设计**：`acquireLock` 必须是原子操作，由底层存储的 CAS 机制保证，避免多 worker 抢同一条记录。

### 6.2 `IdempotencyRecordRepository`

| 方法 | 职责 |
|------|------|
| `findByKey(vendorKey, idempotencyKey)` | 按 vendor + key 查询幂等记录。 |
| `save(record)` | 创建幂等记录。 |
| `updateStatus(vendorKey, idempotencyKey, status)` | 更新幂等记录最终状态。 |
| `deleteExpired(now)` | 清理过期的幂等记录。 |

### 6.3 `VendorConfigRepository`

| 方法 | 职责 |
|------|------|
| `findByKey(vendorKey)` | 按 key 查询 vendor 配置。 |
| `findAll()` | 查询全部配置。 |
| `save(config)` | 保存或更新配置。 |
| `delete(vendorKey)` | 删除配置。 |

### 6.4 `DeliveryAttemptLogRepository`（可选）

用于审计每次投递尝试的详细日志。

---

## 7. 领域服务与应用服务拆分

### 7.1 分层原则

- **Domain 层**：封装核心业务规则和策略，不依赖框架。
- **Application 层**：编排领域服务，定义事务边界，处理具体用例。

### 7.2 Domain 层服务/策略

| 服务/策略 | 职责 |
|----------|------|
| `IdempotencyService` | 受理通知时的去重判断、创建幂等记录、更新最终状态。 |
| `RetryPolicy` | 计算下次重试时间、判断是否允许重试、处理 429 `Retry-After` hint。 |
| `DeliveryResultClassifier` | 根据 HTTP 响应/异常判断结果类型。 |
| `VendorRequestAssembler` | 根据配置组装 vendor HTTP 请求。 |
| `LeaseLockPolicy` | 计算锁超时时间。 |
| `CircuitBreaker` | 维护 vendor 健康状态，判断是否熔断。 |
| `RateLimiter` | 按 vendor 限流判断。 |

### 7.3 Application 层服务

| 服务 | 职责 |
|------|------|
| `NotificationAcceptAppService` | 受理通知用例：参数校验、调用 `IdempotencyService`、管理事务。 |
| `DeliveryAppService` / `DeliveryWorker` | 单次投递用例：获取锁、限流检查、熔断检查、组装请求、发送、处理结果。 |
| `DeadLetterReplayAppService` | 死信重放用例。 |
| `VendorConfigAppService` | Vendor 配置 CRUD 用例。 |
| `StaleLockRecoveryAppService` | 锁超时恢复用例。 |

### 7.4 事务边界

- 事务注解放在 **Application Service** 层。
- Domain Service 不感知事务，只编排领域对象和 Repository 调用。
- 以下操作必须在同一事务中：
  - 受理通知：`NotificationRequest` 创建 + `IdempotencyRecord` 创建；
  - 投递成功：`NotificationRequest` 更新为 SUCCESS + `IdempotencyRecord` 更新为 SUCCESS；
  - 进入 DLQ：`NotificationRequest` 更新为 DEAD_LETTERED + `IdempotencyRecord` 更新为 DEAD_LETTERED。
- **告警必须在事务提交后异步触发**，避免事务回滚导致误报。

---

## 8. 关键技术机制

### 8.1 租约锁机制

#### 8.1.1 锁的获取

worker 通过原子操作获取锁：

```
UPDATE notification_request
SET status = 'SENDING',
    locked_by = :workerId,
    locked_until = :now + leaseDuration,
    updated_at = :now
WHERE status = 'PENDING'
  AND next_retry_at <= :now
  AND locked_until <= :now
ORDER BY next_retry_at ASC
LIMIT :batchSize
```

**关键设计**：锁获取是原子的，由 Repository 的 `acquireLock` 接口封装，底层存储通过 CAS / `SELECT ... FOR UPDATE SKIP LOCKED` 实现。

#### 8.1.2 锁的释放

投递完成后释放锁：

```
UPDATE notification_request
SET locked_by = NULL,
    locked_until = '1970-01-01 00:00:00',
    updated_at = :now
WHERE id = :requestId
```

#### 8.1.3 锁超时恢复

scheduler 定时扫描锁超时的 `SENDING` 记录：

```
SELECT * FROM notification_request
WHERE status = 'SENDING'
  AND locked_until <= :now
```

对这些记录的处理：
1. 视为一次失败尝试；
2. `attemptCount += 1`；
3. 若未超过最大重试次数，计算 `nextRetryAt`，`status = FAILED`；
4. 若超过最大重试次数，`status = DEAD_LETTERED`，同步更新幂等记录，触发告警。

#### 8.1.4 锁超时时长

- `leaseDuration` 应略大于 HTTP 超时 + 处理时间。
- 例如：HTTP timeout 30s，处理时间 5s，则 `leaseDuration = 60s`。

### 8.2 错误分类与重试策略

#### 8.2.1 投递结果分类

一次 HTTP 投递的结果被抽象为四类：

| 结果类型 | 触发条件 | 处理 |
|---------|---------|------|
| **Success** | 2xx 响应 | `SENDING → SUCCESS` |
| **RetryableFailure** | 网络超时、连接失败、DNS 失败、5xx | `SENDING → FAILED`，`attemptCount + 1`，计算 `nextRetryAt` |
| **RateLimited** | 429 Too Many Requests | `SENDING → FAILED`，优先使用 `Retry-After` 计算 `nextRetryAt` |
| **NonRetryableFailure** | 4xx（除 429）、URL 非法、模板渲染失败 | `SENDING → DEAD_LETTERED`，不增加 `attemptCount` |

#### 8.2.2 错误分类器

`DeliveryResultClassifier` 根据 HTTP 响应码和异常类型判断结果类别：
- 异常层：区分 `TimeoutException`、`ConnectException`（可重试）与 `SSLHandshakeException`、配置错误（不可重试）。
- 响应码层：2xx 成功，5xx 可重试，429 限流，4xx 不可重试。

#### 8.2.3 重试策略接口

`RetryPolicy` 是 Domain 层策略接口，核心方法：

- `allowRetry(attemptCount)`：判断是否还允许重试。
- `calculateNextRetry(attemptCount, now, hint)`：计算下次重试时间，传入可选的 `hint`（如 429 的 `Retry-After`）。
- `maxAttempts()`：返回最大重试次数。

默认实现为**指数退避 + 等分抖动**：
- `nextDelay = min(baseDelay * 2^attemptCount + jitter, maxDelay)`；
- 若存在 `hint`（如 `Retry-After`），优先使用 hint。

#### 8.2.4 抖动策略

推荐**等分抖动**：`delay = baseDelay/2 + random(0, baseDelay/2)`，避免大量通知同时重试形成 thundering herd。

#### 8.2.5 重试与幂等的关系

同一条 `NotificationRequest` 的所有重试使用同一个 `idempotencyKey`，保证 vendor 侧可以识别为同一请求。

### 8.3 幂等机制

#### 8.3.1 受理流程

```
收到请求 (vendorKey, idempotencyKey, payload)
    │
    ▼
查询 IdempotencyRecord(vendorKey, idempotencyKey)
    │
    ├── 不存在
    │       ├── 创建 NotificationRequest(status=PENDING)
    │       ├── 创建 IdempotencyRecord(status=PENDING, requestId=...)
    │       └── 返回 202
    │
    ├── SUCCESS
    │       └── 返回 200 + 原结果
    │
    ├── PENDING / SENDING / FAILED
    │       └── 返回 200 + 当前状态
    │
    └── DEAD_LETTERED
            └── 返回 409 + 死信状态
```

#### 8.3.2 最终状态同步

当 `NotificationRequest` 状态变为 `SUCCESS` 或 `DEAD_LETTERED` 时，同步更新 `IdempotencyRecord` 的对应状态。

#### 8.3.3 过期清理

`IdempotencyRecord` 按 `expiresAt` 过期删除，保留窗口建议 24h~7d，可配置。

### 8.4 限流机制

#### 8.4.1 限流位置

限流发生在 worker 准备投递前。若被限流阻挡，记录保持 `PENDING`，只推迟 `nextRetryAt`，不增加 `attemptCount`。

#### 8.4.2 限流算法

MVP 推荐**令牌桶**：允许短突发，平均速率稳定，适合业务通知的突发特征。

#### 8.4.3 限流配置

限流参数作为 `VendorConfig` 的一部分，支持运行时手动调整：

- `qps`：每秒平均允许投递数；
- `burst`：允许突发处理的请求数；
- 通过 Admin API 更新后，内存中的限流器应刷新或重建，建议立即生效。

#### 8.4.4 限流与重试的关系

- 被限流阻挡的投递**不算一次失败尝试**；
- `attemptCount` 不增加；
- 只是推迟 `nextRetryAt`。

#### 8.4.5 限流状态

限流状态（当前令牌数）建议放在内存中，不持久化。服务重启后重置是可接受的。

### 8.5 熔断机制

#### 8.5.1 熔断状态

| 状态 | 行为 |
|------|------|
| `CLOSED` | 正常投递 |
| `OPEN` | 跳过该 vendor 的投递，直接延迟到恢复期 |
| `HALF_OPEN` | 允许少量探测请求，根据结果决定是否关闭或重新打开 |

#### 8.5.2 状态转换

- `CLOSED → OPEN`：最近 N 次尝试中失败率达到阈值（如 50%），或连续失败次数达到阈值。
- `OPEN → HALF_OPEN`：经过冷却时间（如 60s）。
- `HALF_OPEN → CLOSED`：探测成功次数达到阈值。
- `HALF_OPEN → OPEN`：探测失败。

#### 8.5.3 熔断配置与手动模式

熔断参数作为 `VendorConfig` 的一部分，并支持手动模式：

- `mode`：`AUTO` / `FORCE_OPEN` / `FORCE_CLOSED`；
- `failureThreshold`：连续失败次数阈值；
- `failureRateThreshold`：失败率阈值；
- `minCalls`：计算失败率的最小样本数；
- `cooldownDuration`：OPEN → HALF_OPEN 的冷却时间；
- `halfOpenMaxCalls`：半开阶段最大探测次数。

手动模式优先级高于自动模式：
- `FORCE_OPEN`：管理员可提前熔断，如知道 vendor 要维护；
- `FORCE_CLOSED`：管理员可强制恢复投递；
- `AUTO`：按阈值自动开合。

#### 8.5.4 熔断与调度的协作

若熔断开启，记录保持 `PENDING`，`nextRetryAt` 设为熔断恢复期，`attemptCount` 不增加。熔断状态建议放在内存中，MVP 阶段不持久化。

### 8.6 Vendor 请求组装

#### 8.6.1 第一版模板策略（最简设计）

MVP 阶段模板渲染按最简设计实现，不属于本次作业核心：

- **可渲染区域**：Headers、Body；
- **URL**：第一版固定，不支持模板；
- **语法**：简单占位符 `{{fieldName}}`；
- **数据来源**：业务 payload + 系统字段（`idempotencyKey`、`requestId`）+ `VendorConfig` 中的静态值；
- **缺失字段**：默认保留空字符串，并记录警告；可配置严格模式；
- **Dry Run**：Admin API 支持预览渲染结果，便于验证配置。

后续如需支持 URL 模板、条件分支、函数等，再引入 Mustache / SpEL 等模板引擎。

#### 8.6.2 请求组装流程

1. 根据 `vendorKey` 加载 `VendorConfig`；
2. 使用固定 URL；
3. 渲染 Headers：默认 headers + 幂等键（若配置在 header）；
4. 渲染 Body：用 `bodyTemplate` 替换占位符，并注入幂等键（若配置在 body）；
5. 设置 timeout。

### 8.7 调度策略

#### 8.7.1 调度模型

MVP 采用 **DB 队列 + Scheduler 定时拉取** 模型。Scheduler 定时扫描 DB，把可投递的任务分发给 Worker。

#### 8.7.2 Scheduler 职责

1. 扫描 `PENDING` 记录：`status=PENDING AND nextRetryAt <= now AND lockedUntil <= now`；
2. 扫描锁超时的 `SENDING` 记录：`status=SENDING AND lockedUntil <= now`；
3. 将任务分发给 Worker。

扫描时按 `nextRetryAt` 升序，每次限制数量（如 100 条）。

#### 8.7.3 Worker 职责

Worker 负责单条记录的完整投递流程：获取锁 → 限流检查 → 熔断检查 → 组装请求 → 发送 → 处理结果 → 更新状态。

Worker 应为无状态，所有状态在 DB 中。

#### 8.7.4 并发模型

- 使用固定大小的 Worker 线程池；
- 每个 Worker 一次处理一条记录；
- 线程数根据 CPU、DB 连接池、HTTP 连接池调整。

#### 8.7.5 调度频率

默认每 1~5 秒扫描一次，可配置。

#### 8.7.6 崩溃恢复

Scheduler 无状态，服务重启后新实例自动扫描 DB。所有未完成的 `PENDING` / `FAILED` / `SENDING`（锁过期后）记录都会被重新 pickup。

### 8.8 告警机制

#### 8.8.1 告警事件

| 事件 | 级别 | 说明 |
|------|------|------|
| 通知进入 DLQ | P1 | 需要人工介入 |
| vendor 连续失败率过高 | P2 | 可能 vendor 故障或配置错误 |
| 队列堆积超过阈值 | P2 | 消费速度跟不上 |
| 熔断器状态变化 | P3 | 信息性通知 |
| 限流频繁触发 | P3 | 可能需要调整 QPS 配置 |

#### 8.8.2 告警原则

- **异步**：告警不阻塞通知投递主流程；
- **收敛**：同一事件在短时间内只告警一次，避免告警风暴；
- **事务后触发**：告警必须在相关事务提交后异步发送，避免误报。

#### 8.8.3 告警通道

MVP 采用 **全局 Webhook + 日志 fallback**：

- 全局配置一个 Webhook URL；
- 告警事件通过线程池异步发送；
- 发送失败时写入 ERROR 级别日志。

后续可扩展 Slack、邮件、PagerDuty 等多渠道。

#### 8.8.4 告警内容

至少包含：事件类型、vendor、requestId（DLQ 时）、时间戳、错误摘要、建议动作。

---

## 9. 数据模型设计原则

### 9.1 表拆分建议

基于可扩展性和查询性能考虑，建议拆分为以下核心表：

| 表 | 用途 |
|---|------|
| `notification_request` | 投递任务主表，高 churn，按状态和时间调度查询。 |
| `idempotency_record` | 去重索引表，低 churn，按 vendor+key 点查。 |
| `vendor_config` | 供应商配置表，读多写少，启动时缓存。 |
| `delivery_attempt_log`（可选） | 每次投递尝试的详细日志，用于审计。 |

### 9.2 索引建议

- `notification_request`：
  - 联合索引：`(status, next_retry_at, locked_until)` —— 调度扫描。
  - 联合索引：`(status, locked_until)` —— 锁超时恢复扫描。
  - 索引：`(vendor_key, status)` —— 按 vendor 查询。

- `idempotency_record`：
  - 唯一索引：`(vendor_key, idempotency_key)` —— 去重。
  - 索引：`(expires_at)` —— 过期清理。

- `vendor_config`：
  - 主键/唯一索引：`(vendor_key)`。

### 9.3 为什么不合并 `notification_request` 和 `idempotency_record`

- **生命周期不同**：通知请求成功后可以归档，幂等记录需要保留窗口。
- **查询模式不同**：一个是范围扫描调度，一个是点查去重。
- **数据规模不同**：`notification_request` 包含 payload 和错误信息，较大；`idempotency_record` 很小。
- **演化空间**：未来可能支持“同一幂等键下的多次重放历史”，分开更灵活。

---

## 10. 异常与边界处理

### 10.1 受理阶段

| 异常 | 处理 |
|------|------|
| vendorKey 不存在 | 400 Bad Request |
| idempotencyKey 为空 | 400 Bad Request |
| payload 格式非法 | 400 Bad Request |
| 幂等记录已存在 | 按状态返回 200/409 |

### 10.2 投递阶段

| 异常 | 处理 |
|------|------|
| vendor 配置加载失败 | 进入 DLQ（配置错误不可重试） |
| 模板渲染失败 | 进入 DLQ |
| HTTP 超时 | FAILED，可重试 |
| HTTP 5xx | FAILED，可重试 |
| HTTP 429 | FAILED，使用 Retry-After 或退避 |
| HTTP 4xx | DEAD_LETTERED |
| 锁超时 | FAILED，attemptCount + 1 |

### 10.3 幂等记录与通知请求不一致

极端情况下，通知请求已更新为 SUCCESS，但幂等记录更新失败。此时：
- 下次业务方用同一 key 提交时，会查询到 `NotificationRequest` 为 SUCCESS；
- 可设计补偿机制：查询 `NotificationRequest` 状态作为幂等记录的 fallback；
- 或保证两者更新在同一事务中。

---

## 11. 技术栈与中间件取舍

### 11.1 核心选型

| 层次 | 选型 | 说明 |
|------|------|------|
| 框架 | Spring Boot 3.x | Java 21 兼容，生态成熟。 |
| 数据访问 | Spring Data JPA | 减少样板代码，适合状态机表达。 |
| 数据库 | H2（默认）+ 兼容 PostgreSQL | H2 便于作业展示和一键运行；schema 兼容 PostgreSQL，可无缝切换。 |
| HTTP 客户端 | Spring 6 `RestClient` | `RestTemplate` 的继任者，同步模型与 DB 队列 worker 配合直观。 |
| 调度 | Spring `@Scheduled` + 线程池 | 足够支撑 DB 队列拉取，无需 Quartz。 |
| 限流 | Bucket4j | 标准令牌桶实现。 |
| 熔断 | Resilience4j | Spring 生态标准。 |
| 模板 | Apache Commons Text `StringSubstitutor` | 最简占位符替换。 |
| 测试 | JUnit 5 + Mockito + Testcontainers + WireMock | 覆盖单元、集成、故障注入。 |

### 11.2 通知队列的实现方式

**决策：使用数据库表作为通知队列，而不是 Metaq / RocketMQ / Kafka。**

原因：
- DB 队列足以满足 MVP 的可靠性和吞吐要求；
- 状态机、幂等、重试调度天然适合关系型存储表达；
- 减少外部依赖，降低部署和运维成本；
- 一条命令即可启动整个系统，适合作业展示。

**通知队列是需要实现的核心能力**，其实现为 `notification_request` 表 + Scheduler 拉取 + 租约锁，而非外部消息队列。

### 11.3 为什么不使用 Redis / Metaq / Kafka？

| 中间件 | 典型用途 | 本 MVP 不使用的理由 |
|--------|---------|-------------------|
| **Metaq / RocketMQ / Kafka** | 高吞吐消息队列、削峰 | MVP 流量和复杂度不需要；DB 队列已能满足可靠投递。 |
| **Redis** | 分布式锁、分布式限流、缓存、熔断状态共享 | MVP 按单实例设计，内存级限流/熔断足够；DB 已承担持久化和去重。 |

### 11.4 演进路径

当流量或复杂度显著增长时，可逐步引入中间件：

1. **消息队列化**：吞吐量达到 DB 轮询瓶颈，或延迟要求 < 100ms 时，将 DB 队列替换为 Kafka/RocketMQ，DB 保留幂等和查询能力。
2. **Redis 缓存**：`VendorConfig` 读量增大或需要多实例共享状态时，引入 Redis 做缓存和分布式协调。
3. **分布式锁**：多实例部署且 DB 租约锁成为瓶颈时，可引入 Redis RedLock 等方案。

### 11.5 数据库方案取舍

| 方案 | 适用场景 | 本设计选择 |
|------|---------|-----------|
| H2 | 作业展示、本地开发、快速启动 | ✅ 默认 |
| PostgreSQL | 生产环境、更高可用性和并发 | ✅ 可选，通过 Docker Compose 提供 |

**取舍说明**：MVP 优先保证“可运行、可演示”，因此默认 H2；同时通过兼容的 schema 和 Docker Compose 配置，展示对生产环境的考虑。

---

## 12. `VendorConfig` 缓存策略

### 12.1 MVP 方案

- **启动时加载**：应用启动时从 DB 加载全部 `VendorConfig` 到内存；
- **Admin API 更新后刷新**：通过 Admin API 修改配置后，主动刷新或失效缓存；
- **可选兜底刷新**：设置较长的后台刷新周期（如 5 分钟），作为兜底。

### 12.2 为什么这样设计？

- `VendorConfig` 读多写少，缓存能显著降低 DB 压力；
- 配置变更不频繁，秒级延迟可接受；
- 手动调整后立即生效是明确需求，通过 Admin API 主动刷新实现；
- 实现简单，不引入 Redis 等额外依赖。

### 12.3 扩展方案

当配置数量大、读量高或多实例共享时，可演进为：
- **Redis 缓存**：多实例共享配置，减少 DB 压力；
- **版本号机制**：每次读取前检查 `version`，过期自动刷新；
- **发布/订阅通知**：配置变更时广播通知所有实例刷新。

---

## 13. Metrics 与日志规范

### 13.1 MVP 日志方案

- **结构化日志**：使用 SLF4J + MDC，每条日志包含 `requestId`、`vendorKey`、`idempotencyKey`、`event` 等字段；
- **日志级别**：
  - INFO：受理成功、投递成功；
  - WARN：可重试失败、锁超时恢复；
  - ERROR：进入 DLQ、不可重试错误。

### 13.2 MVP Metrics 方案

- 使用 Spring Boot Actuator + Micrometer 暴露基础指标；
- 至少覆盖以下指标：
  - `notification.received.total`：接收总数；
  - `notification.delivered.total`：成功投递数；
  - `notification.failed.total`：可重试失败数；
  - `notification.dead_lettered.total`：死信数；
  - `notification.pending.gauge`：当前 PENDING 数量；
  - `notification.dlq.gauge`：当前 DLQ 数量。

### 13.3 为什么这样设计？

- 日志是排查单条通知生命周期的必需品；
- Micrometer/Actuator 是 Spring Boot 生态标准，引入成本极低；
- MVP 不需要复杂 Dashboard，通过 `/actuator/metrics` 即可查看。

### 13.4 扩展方案

- 接入 Prometheus 抓取指标；
- 通过 Grafana 可视化队列深度、DLQ 趋势、vendor 失败率；
- 增加 Timer 指标统计投递耗时。

---

## 14. 待确定事项

以下技术细节在实现阶段进一步细化：

1. **测试策略**：单元测试、集成测试、故障注入方案。
2. **AI 使用说明补充**：实现完成后整理到 `docs/ai-usage.md` 或 README。

---

*文档版本：v1.0*  
*范围：技术设计，不含具体框架选型与最终实现代码*
