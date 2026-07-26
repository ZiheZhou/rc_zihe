# Notification Gateway 实现计划

> **For agentic workers:** 使用 superpowers:subagent-driven-development 或 superpowers:executing-plans 按任务顺序实现。步骤使用 checkbox（`- [x]`）追踪。
>
> 本计划为精简版：只包含任务拆解、文件结构、接口约定，不含完整代码。实现时以 `docs/technical-design.md` 为详细设计依据。

**Goal:** 实现一个接收内部业务系统 HTTP 通知请求、持久化后可靠投递到外部 vendor API 的通知网关 MVP。

**Architecture:** 分层架构（api / application / domain / infrastructure），DB 队列 + Scheduler 拉取 + 租约锁，状态机驱动，指数退避重试，per-vendor 限流/熔断，幂等去重，DLQ + 人工重放，全局 Webhook 告警。

**Tech Stack:** Java 21 · Spring Boot 3.4.x · Spring Data JPA · H2（默认，file 模式）+ PostgreSQL 兼容 schema · RestClient · Spring @Scheduled · Bucket4j · Resilience4j · Apache Commons Text · JUnit 5 + Mockito + WireMock + Awaitility + Testcontainers

**设计依据:** `docs/design.md`（架构）、`docs/technical-design.md`（技术设计）。两文档与代码不一致时以技术设计文档为准，代码实现中的必要偏离需记录到 `docs/ai-usage.md`。

## Global Constraints

- 基础包：`com.examine`，Java 21（可用 record / sealed / switch 模式匹配）。
- 分层约束：`domain` 不依赖 `api` / `application` / `infrastructure` / Spring 注解（Jackson 除外，仅用于 assembler 之外的 JSON 处理放 application/infra）。
- 事务注解只出现在 `application` 层方法上；HTTP 调用不得在事务内执行。
- 时间一律通过注入的 `java.time.Clock` 获取，禁止 `Instant.now()` 直接调用（测试可控）。
- 状态机：`PENDING → SENDING → SUCCESS / FAILED / DEAD_LETTERED`，`FAILED` 到期后由调度查询重新纳入投递（FAILED → SENDING，等价于文档中的 FAILED → PENDING → SENDING，实现时合并为一跳，此偏离需记录）。
- 被限流/熔断延迟的记录：状态回 `PENDING`、只改 `nextRetryAt`、**不增加 attemptCount**。
- 幂等：同一 `vendorKey + idempotencyKey` 已 SUCCESS 不重复投递；已 DLQ 返回 409；处理中返回当前状态。
- 调度在测试中必须可关闭：通过 `notification.scheduling.enabled` 属性控制 `@EnableScheduling`，测试默认关闭，E2E 显式打开。
- 默认 DB 为 H2 file 模式（`./data/`，可演示重启恢复）；schema 必须同时兼容 PostgreSQL。
- 每个任务完成后单独 commit；提交信息格式 `feat: ...` / `test: ...` / `chore: ...` / `docs: ...`。
- 项目目录尚未初始化 git：Task 1 第一步执行 `git init`。

---

## 文件结构

```text
notification-gateway/
├── pom.xml
├── docker-compose.yml                        # 可选 PostgreSQL（Task 17）
├── README.md                                 # Task 17
├── docs/
│   ├── design.md                             # 已完成
│   ├── technical-design.md                   # 已完成
│   ├── ai-usage.md                           # Task 17
│   └── plans/2026-07-26-notification-gateway-implementation.md  # 本文件
└── src/
    ├── main/
    │   ├── java/com/examine/
    │   │   ├── NotificationGatewayApplication.java
    │   │   ├── api/
    │   │   │   ├── NotificationController.java
    │   │   │   ├── DeadLetterAdminController.java
    │   │   │   ├── VendorConfigAdminController.java
    │   │   │   ├── GlobalExceptionHandler.java
    │   │   │   └── dto/
    │   │   │       ├── CreateNotificationRequest.java
    │   │   │       ├── NotificationResponse.java
    │   │   │       ├── NotificationStatusResponse.java
    │   │   │       ├── VendorConfigRequest.java
    │   │   │       └── ErrorResponse.java
    │   │   ├── application/
    │   │   │   ├── NotificationAcceptAppService.java
    │   │   │   ├── DeliveryAppService.java
    │   │   │   ├── DeadLetterReplayAppService.java
    │   │   │   ├── VendorConfigAppService.java
    │   │   │   ├── StaleLockRecoveryAppService.java
    │   │   │   └── VendorNotFoundException.java
    │   │   ├── domain/
    │   │   │   ├── model/
    │   │   │   │   ├── NotificationRequest.java
    │   │   │   │   ├── IdempotencyRecord.java
    │   │   │   │   ├── Status.java                  # PENDING/SENDING/SUCCESS/FAILED/DEAD_LETTERED
    │   │   │   │   ├── IdempotencyStatus.java       # PENDING/SUCCESS/FAILED/DEAD_LETTERED
    │   │   │   │   ├── AcceptResult.java            # sealed: Accepted / Duplicate / DeadLettered
    │   │   │   │   ├── DeliveryResult.java          # sealed: Success / RetryableFailure / NonRetryableFailure / RateLimited
    │   │   │   │   ├── HttpOutcome.java             # HTTP 调用原始结果（状态码+headers 或异常）
    │   │   │   │   ├── VendorHttpRequest.java       # 组装后的 vendor 请求
    │   │   │   │   └── config/                      # VendorConfig 相关
    │   │   │   │       ├── VendorConfig.java
    │   │   │   │       ├── RetryPolicySettings.java
    │   │   │   │       ├── RateLimitSettings.java
    │   │   │   │       ├── CircuitBreakerSettings.java
    │   │   │   │       ├── CircuitBreakerMode.java  # AUTO/FORCE_OPEN/FORCE_CLOSED
    │   │   │   │       ├── HttpMethod.java
    │   │   │   │       └── IdempotencyKeyLocation.java # HEADER/BODY
    │   │   │   ├── policy/
    │   │   │   │   ├── RetryPolicy.java             # 接口
    │   │   │   │   ├── JitterStrategy.java          # 接口
    │   │   │   │   ├── EqualJitterStrategy.java
    │   │   │   │   ├── ExponentialBackoffRetryPolicy.java
    │   │   │   │   └── DeliveryResultClassifier.java
    │   │   │   ├── repository/
    │   │   │   │   ├── NotificationRequestRepository.java   # 接口
    │   │   │   │   ├── IdempotencyRecordRepository.java     # 接口
    │   │   │   │   └── VendorConfigRepository.java          # 接口
    │   │   │   └── service/
    │   │   │       ├── IdempotencyService.java
    │   │   │       ├── VendorRequestAssembler.java
    │   │   │       ├── RateLimiter.java             # 接口（infra 实现）
    │   │   │       ├── VendorCircuitBreaker.java    # 接口（infra 实现）
    │   │   │       └── AlertService.java            # 接口（infra 实现）
    │   │   └── infrastructure/
    │   │       ├── persistence/
    │   │       │   ├── NotificationRequestEntity.java
    │   │       │   ├── IdempotencyRecordEntity.java
    │   │       │   ├── VendorConfigEntity.java
    │   │       │   ├── NotificationRequestJpaRepository.java
    │   │       │   ├── IdempotencyRecordJpaRepository.java
    │   │       │   ├── VendorConfigJpaRepository.java
    │   │       │   ├── NotificationRequestRepositoryImpl.java
    │   │       │   ├── IdempotencyRecordRepositoryImpl.java
    │   │       │   ├── VendorConfigRepositoryImpl.java
    │   │       │   └── EntityMappers.java
    │   │       ├── http/HttpClientAdapter.java
    │   │       ├── ratelimit/Bucket4jRateLimiter.java
    │   │       ├── circuitbreaker/Resilience4jVendorCircuitBreaker.java
    │   │       ├── config/
    │   │       │   ├── VendorConfigCache.java
    │   │       │   ├── NotificationProperties.java   # @ConfigurationProperties
    │   │       │   └── SchedulingConfig.java         # @EnableScheduling + 条件开关
    │   │       ├── scheduling/
    │   │       │   ├── DeliveryScheduler.java
    │   │       │   └── StaleLockRecoveryScheduler.java
    │   │       ├── alert/
    │   │       │   ├── AlertEvent.java
    │   │       │   └── WebhookAlertService.java
    │   │       └── metrics/NotificationMetrics.java
    │   └── resources/
    │       ├── application.yml
    │       ├── application-postgres.yml
    │       └── schema.sql
    └── test/
        └── java/com/examine/                      # 与 main 包结构对应的测试
            ├── domain/...                          # 纯单元测试
            ├── infrastructure/persistence/...      # @DataJpaTest
            ├── api/...                             # @WebMvcTest / MockMvc
            ├── application/...                     # Mockito 单元测试
            ├── e2e/NotificationFlowE2ETest.java    # @SpringBootTest + WireMock + Awaitility
            └── support/                            # 测试基类、固定 Clock、fakes
```

---

## 接口约定（实现时严格保持一致）

### Domain 模型

```java
// NotificationRequest：不可变字段 final，状态转换通过方法完成
static NotificationRequest create(String id, String vendorKey, String idempotencyKey, String payload, Instant now)
static NotificationRequest restore(String id, String vendorKey, String idempotencyKey, String payload,
    Status status, int attemptCount, Instant nextRetryAt, String lockedBy, Instant lockedUntil,
    Instant createdAt, Instant updatedAt, Instant deliveredAt, String lastError)
void markSending(String workerId, Instant lockedUntil, Instant now)
void markSuccess(Instant now)                       // 设置 deliveredAt + 释放锁
void markFailed(Instant nextRetryAt, String error, Instant now)   // attemptCount+1 + 释放锁
void markDeadLettered(String error, Instant now)    // 不增加 attemptCount + 释放锁
void reschedule(Instant nextRetryAt, Instant now)   // → PENDING，不增加 attemptCount（限流/熔断用）
void replay(Instant now)                            // DLQ 重放 → PENDING
Instant UNLOCKED = Instant.EPOCH                     // 未锁定哨兵值

// IdempotencyRecord
static IdempotencyRecord create(String id, String vendorKey, String idempotencyKey, String requestId, Instant now, Duration retention)
void markSuccess() / void markFailed() / void markDeadLettered()

// AcceptResult (sealed)
record Accepted(String requestId)
record Duplicate(String requestId, Status status)
record DeadLettered(String requestId)

// DeliveryResult (sealed)
record Success(int statusCode)
record RetryableFailure(String reason, Optional<Duration> retryAfter)
record NonRetryableFailure(String reason)
record RateLimited(Optional<Duration> retryAfter)

// HttpOutcome：HTTP 调用的原始结果，由 infra 产生、classifier 消费
static HttpOutcome response(int statusCode, Map<String,String> headers)
static HttpOutcome failure(Throwable error)

// VendorHttpRequest
record VendorHttpRequest(String url, HttpMethod method, Map<String,String> headers, String body, Duration timeout)

// VendorConfig 族（均 record）
VendorConfig(String vendorKey, String endpoint, HttpMethod method, Map<String,String> headers,
    String bodyTemplate, Duration timeout, RetryPolicySettings retryPolicy, RateLimitSettings rateLimit,
    CircuitBreakerSettings circuitBreaker, IdempotencyKeyLocation idempotencyKeyLocation, String idempotencyKeyName)
RetryPolicySettings(int maxAttempts, Duration baseDelay, Duration maxDelay)
RateLimitSettings(int qps, int burst)
CircuitBreakerSettings(CircuitBreakerMode mode, int failureRateThreshold, int minCalls, int cooldownSeconds, int halfOpenMaxCalls)
```

### Domain 策略与服务

```java
// RetryPolicy（方案 A：429 hint 统一传入）
boolean allowRetry(int attemptCount)
Instant calculateNextRetry(int attemptCount, Instant now, Optional<Duration> hint)  // hint 优先；否则 base*2^(n-1)+等分抖动，封顶 maxDelay
int maxAttempts()

// DeliveryResultClassifier
DeliveryResult classify(HttpOutcome outcome)
// 规则：error→网络类(IOException/SocketTimeout/Connect)Retryable，其他 NonRetryable；
//       2xx→Success；429→RateLimited(解析 Retry-After)；5xx→Retryable；其余 4xx→NonRetryable

// IdempotencyService
AcceptResult accept(String vendorKey, String idempotencyKey, String payload, Instant now)
// 逻辑见 technical-design.md 8.3：不存在→创建两记录返回 Accepted；SUCCESS→Duplicate；处理中→Duplicate(当前状态)；DLQ→DeadLettered

// VendorRequestAssembler
VendorHttpRequest assemble(String requestId, String idempotencyKey, Map<String,Object> payload, VendorConfig config)
// StringSubstitutor "{{var}}"；缺失字段→空字符串+WARN 日志；idempotencyKey/requestId 注入 values；HEADER 模式时写入 header

// RateLimiter / VendorCircuitBreaker / AlertService（domain 接口，infra 实现）
boolean tryAcquire(String vendorKey)
boolean allowCall(String vendorKey)  /  void onSuccess(String vendorKey)  /  void onFailure(String vendorKey)
void notifyDeadLetter(String requestId, String vendorKey, String reason)
```

### Repository 接口（domain）

```java
// NotificationRequestRepository
NotificationRequest save(NotificationRequest request)
NotificationRequest update(NotificationRequest request)
Optional<NotificationRequest> findById(String id)
List<NotificationRequest> findPendingForDispatch(Instant now, int limit)   // status IN (PENDING,FAILED) AND nextRetryAt<=now AND lockedUntil<=now
List<NotificationRequest> findStaleSendingRecords(Instant now, int limit)  // status=SENDING AND lockedUntil<=now
boolean acquireLock(String id, String workerId, Instant lockedUntil, Instant now)  // 原子 CAS 更新，返回是否成功
List<NotificationRequest> findByStatus(Status status, int limit)
long countByStatus(Status status)

// IdempotencyRecordRepository
Optional<IdempotencyRecord> findByKey(String vendorKey, String idempotencyKey)
IdempotencyRecord save(IdempotencyRecord record)
void updateStatus(String vendorKey, String idempotencyKey, IdempotencyStatus status)
int deleteExpired(Instant now)

// VendorConfigRepository
Optional<VendorConfig> findByKey(String vendorKey)
boolean existsByKey(String vendorKey)
List<VendorConfig> findAll()
VendorConfig save(VendorConfig config)
void delete(String vendorKey)
```

### Application 服务

```java
// NotificationAcceptAppService          @Transactional
AcceptResult accept(String vendorKey, String idempotencyKey, Map<String,Object> payload)  // 校验 vendor 存在→IdempotencyService.accept

// DeliveryAppService
boolean tryDispatch(String requestId)
// acquireLock(短事务) → 失败返回 false → 加载记录 → configCache 取配置(缺失→DLQ)
// → rateLimiter 拒绝→reschedule(now+500ms) → cb 打开→reschedule(now+cooldown)
// → assembler 组装 → httpClient.send → classifier.classify → 按结果分支：
//   Success→markSuccess+幂等 SUCCESS+cb.onSuccess（同一事务）
//   RateLimited/Retryable→attemptCount+1，超 maxAttempts→DLQ+幂等 DLQ+alert，否则 markFailed(nextRetry)
//   NonRetryable→DLQ（不增加 attemptCount）+幂等 DLQ+alert
// 持久化方法（persistSuccess/persistRetry/persistDeadLetter）各自 @Transactional；HTTP 调用在事务外

// DeadLetterReplayAppService            @Transactional
NotificationResponse replay(String requestId)   // 仅 DEAD_LETTERED 可重放→replay()+幂等记录回 PENDING

// StaleLockRecoveryAppService
void recoverStale(int limit)   // 扫描 SENDING 锁过期→视为失败一次：attemptCount+1，超上限→DLQ+alert，否则 markFailed

// VendorConfigAppService
VendorConfig create/update(VendorConfigRequest dto)   // 保存后 cache.refresh(vendorKey)
void delete(String vendorKey)                          // 删除后 cache.refresh
```

### API 契约

```http
POST /api/v1/notifications        → 202 Accepted / 200 (重复) / 409 (DLQ) / 400
GET  /api/v1/notifications/{id}   → 200 NotificationStatusResponse / 404
POST /admin/v1/dead-letters/{id}/retry → 200 / 404 / 409(非 DLQ 状态)
GET  /admin/v1/dead-letters       → DLQ 列表
POST/GET/PUT/DELETE /admin/v1/vendor-configs[/{vendorKey}]
```

异常映射（GlobalExceptionHandler）：`VendorNotFoundException`→400；`IllegalStateException`→409；not found→404；参数校验→400。

### 配置（application.yml）

```yaml
notification:
  scheduling.enabled: true        # 测试关闭
  scheduler.fixed-delay-ms: 2000
  scheduler.batch-size: 100
  worker.pool-size: 10
  lease-duration-ms: 60000
  alert.webhook-url: ""           # 空 = 仅日志
  idempotency.retention-days: 7
```

---

## 任务拆解

### Task 1: 项目骨架

**Files:** pom.xml（Spring Boot 3.4.1 parent + web/data-jpa/validation/actuator/h2/postgresql/commons-text/bucket4j/resilience4j/test+wiremock+awaitility+testcontainers）、application.yml、schema.sql、`NotificationGatewayApplication.java`、删除 `Main.java`、`.gitignore` 追加 `data/`、Maven Wrapper

**Steps:**
- [x] `git init`
- [x] 重写 pom.xml，添加全部依赖
- [x] `mvn -N wrapper:wrapper`（生成 mvnw，OpenSpec 任务 10.1 要求 `./mvnw test`）
- [x] 写 application.yml（H2 file 模式 `MODE=PostgreSQL`）、schema.sql（三张表 + 索引，兼容 PostgreSQL）
- [x] 主类 + `contextLoads` 冒烟测试
- [x] `./mvnw -q compile` 通过；`./mvnw -q test` 通过
- [x] Commit: `chore: spring boot project skeleton`

### Task 2: Domain 模型与状态机

**Files:** Status/IdempotencyStatus、NotificationRequest、IdempotencyRecord、AcceptResult + 单测

**Interfaces:** 产出接口约定中“Domain 模型”全部签名。

**测试要点:** 状态转换（create→PENDING、markSending 带锁、markSuccess 释放锁并记 deliveredAt、markFailed attempt+1、markDeadLettered 不加 attempt、reschedule 不改 attempt、replay 回 PENDING）。

- [x] 写失败测试 → 实现 → 通过 → Commit: `feat: domain model and state machine`

### Task 3: VendorConfig 配置模型

**Files:** domain/model/config/ 下 7 个类（VendorConfig、RetryPolicySettings、RateLimitSettings、CircuitBreakerSettings、CircuitBreakerMode、HttpMethod、IdempotencyKeyLocation）

- [x] 实现 records/enums + 简单构造校验（如 maxAttempts>0）
- [x] 单测（默认值/校验）
- [x] Commit: `feat: vendor config model`

### Task 4: RetryPolicy 策略族

**Files:** RetryPolicy、JitterStrategy、EqualJitterStrategy（注入 Random 便于测试）、ExponentialBackoffRetryPolicy + 单测

**测试要点:** hint 优先于退避；attempt 1→[base/2, base]；attempt 3→[4·base/2, 4·base]；封顶 maxDelay；allowRetry 边界。固定种子 Random 保证确定性。

- [x] 测试 → 实现 → Commit: `feat: retry policy with exponential backoff and jitter`

### Task 5: HttpOutcome + DeliveryResult + Classifier

**Files:** HttpOutcome、DeliveryResult（domain/model）、DeliveryResultClassifier（domain/policy）+ 单测

**测试要点:** 2xx/429(有/无 Retry-After)/500/400/ConnectException/SocketTimeout/其他异常 全部分类正确。

- [x] 测试 → 实现 → Commit: `feat: delivery result classification`

### Task 6: Repository 接口 + JPA 持久化

**Files:** domain/repository/ 三个接口；infrastructure/persistence/ 下 entities、JpaRepository、Impl、EntityMappers

**关键实现:**
- `acquireLock` 用 JPQL `@Modifying` 条件更新实现 CAS：`set status='SENDING', lockedBy, lockedUntil where id=:id and status in ('PENDING','FAILED') and nextRetryAt<=:now and lockedUntil<=:now`，返回影响行数==1。
- `findPendingForDispatch`：status IN (PENDING,FAILED)，按 nextRetryAt 升序，Pageable 限量。
- VendorConfigEntity 的 headers Map 以 JSON 字符串存 CLOB 列，ObjectMapper 转换。

**测试:** `@DataJpaTest`：save/find 往返、acquireLock 成功与二次失败、未来 nextRetryAt 不可领取、stale SENDING 查询、幂等表唯一约束与 updateStatus、vendor config headers JSON 往返。

- [x] 接口定义 → 测试 → 实现 → Commit: `feat: persistence layer with lease-lock CAS`

### Task 7: IdempotencyService

**Files:** domain/service/IdempotencyService.java + 单测（内存 fake repository）

**测试要点:** 四种分支（新受理 202 语义、SUCCESS 重复、处理中重复、DLQ 409 语义）；两表同写调用次序。

- [x] 测试 → 实现 → Commit: `feat: idempotency service`

### Task 8: 受理用例 + Notification API

**Files:** NotificationAcceptAppService、VendorNotFoundException、NotificationController、dto/（CreateNotificationRequest、NotificationResponse、NotificationStatusResponse、ErrorResponse）、GlobalExceptionHandler

**测试:** `@WebMvcTest`：新通知 202、重复 200、DLQ 409、缺 idempotencyKey 400、未知 vendorKey 400、GET 查询 200/404。

- [x] 测试 → 实现 → Commit: `feat: notification accept api`

### Task 9: VendorRequestAssembler

**Files:** domain/service/VendorRequestAssembler.java + 单测

**测试要点:** body/header 占位符替换、缺失字段→空串+WARN、idempotencyKey/requestId 注入、HEADER 模式写 header、BODY 模式仅模板内替换。

- [x] 测试 → 实现 → Commit: `feat: vendor request assembler`

### Task 10: HttpClientAdapter

**Files:** infrastructure/http/HttpClientAdapter.java + WireMock 测试

**关键实现:** RestClient + `SimpleClientHttpRequestFactory`（按 config.timeout 设置 connect/read timeout）；禁用默认错误状态抛异常（onStatus 吞掉错误码统一返回）；`ResourceAccessException` 取其 cause 包装为 HttpOutcome.failure。

**测试:** 200→response；500→response（不抛）；connection refused→failure；延迟响应超 timeout→failure(SocketTimeoutException)。

- [x] 测试 → 实现 → Commit: `feat: http client adapter`

### Task 11: RateLimiter + VendorCircuitBreaker + VendorConfigCache

**Files:** domain/service/ 三个接口的实现：Bucket4jRateLimiter、Resilience4jVendorCircuitBreaker、VendorConfigCache（ConcurrentHashMap + refresh）

**关键实现:**
- Bucket4j：per-vendor 桶，`capacity=burst`，`refillGreedy(qps, 1s)`；配置变更后桶重建（refresh 时清桶）。
- Resilience4j：per-vendor CB，`CircuitBreakerRegistry` + 从 VendorConfig 映射配置（failureRateThreshold、minCalls、cooldown、halfOpenMaxCalls）；AUTO 走 CB 状态机，FORCE_OPEN 直接拒绝，FORCE_CLOSED 直接放行。

**测试:** qps=2/burst=2 第三次拒绝；AUTO 连续失败达阈值后拒绝、冷却后可探测；FORCE_OPEN/FORCE_CLOSED 行为。

- [x] 测试 → 实现 → Commit: `feat: rate limiter and circuit breaker`

### Task 12: AlertService + Metrics

**Files:** domain/service/AlertService 接口；infrastructure/alert/（AlertEvent、WebhookAlertService：单线程 executor 异步发送，URL 为空时 ERROR 日志兜底）；infrastructure/metrics/NotificationMetrics.java（Micrometer counters：received/delivered/failed/dead_lettered；gauges：pending/dlq 队列深度）

**OpenSpec 增量（specs/observability-alerting）：**
- 告警事件模型字段：事件类型、vendor、requestId、时间戳、错误摘要、建议动作；
- **Vendor 失败率告警（P2）**：vendor 连续失败次数或失败率达阈值时触发告警（可与熔断打开事件联动，熔断 CLOSED→OPEN 时告警）；
- **告警收敛**：同一事件类型 + vendor 在冷却窗口（默认 5min，可配置）内只发送一次；
- 结构化日志事件类型：`NOTIFICATION_ACCEPTED` / `NOTIFICATION_DELIVERED` / `NOTIFICATION_DEAD_LETTERED`（SLF4J + MDC 含 requestId/vendorKey/idempotencyKey）。

- [x] WireMock 验证 webhook POST；空 URL 路径不抛异常；收敛窗口内重复事件只发一次
- [x] Commit: `feat: async webhook alert and metrics`

### Task 13: DeliveryAppService

**Files:** application/DeliveryAppService.java + Mockito 单测

**测试要点（mock 各协作者）:** 成功路径（SUCCESS+幂等同步+cb.onSuccess）、可重试失败（FAILED+attempt+1+nextRetryAt）、达上限进 DLQ（+幂等 DLQ+alert）、不可重试直接 DLQ、限流 reschedule（attempt 不变）、熔断 reschedule、acquireLock 失败直接返回 false。

- [x] 测试 → 实现 → Commit: `feat: delivery orchestration service`

### Task 14: Scheduler + 锁超时恢复

**Files:** NotificationProperties、SchedulingConfig（`@ConditionalOnProperty(notification.scheduling.enabled)`）、WorkerConfig（fixed pool bean）、DeliveryScheduler、StaleLockRecoveryScheduler、StaleLockRecoveryAppService + 集成测试

**测试（@SpringBootTest，scheduling 关闭，直接调 poll/recover 方法）:** 种 PENDING 记录 + WireMock 200 vendor → poll() 后 SUCCESS；种锁过期的 SENDING → recoverStale() 后 FAILED 且 attempt=1。

- [x] 测试 → 实现 → Commit: `feat: delivery scheduler and stale lock recovery`

### Task 15: Admin APIs

**Files:** DeadLetterReplayAppService、VendorConfigAppService、DeadLetterAdminController、VendorConfigAdminController、VendorConfigRequest dto + MockMvc 测试

**OpenSpec 增量（specs/vendor-config-management）：**
- 新增 dry-run 预览接口 `POST /admin/v1/vendor-configs/{vendorKey}/preview`：入参示例 payload，返回渲染后的 url/headers/body，不触发真实投递。

**测试:** vendor config CRUD（创建/查询/更新后缓存刷新/删除）；dry-run 预览返回渲染结果且不发 HTTP；DLQ 重放 200/404/409。

- [x] 测试 → 实现 → Commit: `feat: admin apis for dlq replay and vendor config`

### Task 16: E2E 集成测试

**Files:** test/e2e/NotificationFlowE2ETest.java、application-test.yml（scheduling 开、fixed-delay 100ms、短退避配置）

**场景（@SpringBootTest RANDOM_PORT + WireMock + Awaitility，每场景前清库）:**
1. 提交 → await SUCCESS（WireMock 200）
2. 同 idempotencyKey 重复提交 → 同 requestId，vendor 只被调 1 次
3. vendor 500×N→200 → 重试后 SUCCESS
4. vendor 400 → 进 DLQ → admin replay →（WireMock 改 200）→ SUCCESS
5. vendor 429 + Retry-After → 遵守后再成功

- [x] 场景 1-5 全部通过
- [x] Commit: `test: end-to-end notification flow`

### Task 17: 交付物完善

**Files:** docker-compose.yml（PostgreSQL）、application-postgres.yml、README.md、docs/ai-usage.md

**OpenSpec 增量：**
- 新增 Testcontainers + PostgreSQL 兼容性验证测试（`@Testcontainers(disabledWithoutDocker = true)`，无 Docker 环境自动跳过）：用 PostgreSQL 容器跑一遍 schema 初始化 + 基本读写，验证 schema 兼容性。

**README 必含:** 一键运行（`./mvnw spring-boot:run`）、API 示例 curl、架构图链接 docs/ 与 openspec/、关键取舍摘要、测试运行方式、PostgreSQL 切换方式。

**ai-usage.md 必含:** AI 帮助点、未采纳建议、人工决策（含 FAILED→PENDING 合并一跳、Resilience4j 封装 FORCE 模式等实现偏离）。

- [x] docker-compose + postgres profile，手动验证 `docker compose up` + `--spring.profiles.active=postgres` 启动成功（需本机 Docker；无 Docker 则跳过并在 README 注明）
- [x] README.md
- [x] docs/ai-usage.md
- [x] OpenSpec 校验：`openspec validate --change notification-gateway-mvp`（若本机有 openspec CLI；无则跳过并记录）
- [x] Commit: `docs: readme, docker compose and ai usage notes`

---

## Self-Review 记录

- **Spec 覆盖：** 设计文档 6/7/8 章各项机制（租约锁 8.1→Task 6/14；重试 8.2→Task 4/5；幂等 8.3→Task 7/8；限流 8.4→Task 11；熔断 8.5→Task 11；组装 8.6→Task 9；调度 8.7→Task 14；告警 8.8→Task 12）均有对应任务。
- **已知实现偏离（需写入 ai-usage.md）：** FAILED→PENDING 由调度查询合并；测试调度开关。
- **类型一致性：** 各任务消费/产出的签名以“接口约定”一节为准。

---

*计划版本：v1.0 · 2026-07-26*
