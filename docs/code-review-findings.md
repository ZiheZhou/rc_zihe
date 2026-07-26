# Code Review Findings

> 评审日期：2026-07-27
> 评审范围：notification-gateway 全量代码

---

## 总览

| # | 严重度 | 分类 | 问题 |
|:--|:--|:--|:--|
| 1 | 🔴 Critical | correctness | update() 无 status 守卫，Worker 和恢复器并发写覆盖 |
| 2 | 🔴 Critical | correctness | StaleLockRecovery 错误递增 attemptCount |
| 3 | 🔴 Critical | correctness | attemptCount 用 Java 绝对值赋值，并发时丢增量 |
| 4 | 🟡 Major | correctness | 幂等冲突无 payload 比较，同 key 不同 payload 静默返回 200 |
| 5 | 🟡 Major | simplification | 缺少 X-Notification-Attempt header |
| 6 | 🟡 Major | correctness | payload 无大小上限 |
| 7 | 🔵 Minor | test-coverage | 缺少 delivery_attempts 独立表，无按次投递历史 |

---

## 1. 🔴 Critical — update() 无 status 守卫，Worker 和恢复器并发写覆盖

**文件**：`src/main/java/com/examine/infrastructure/persistence/NotificationRequestRepositoryImpl.java:35`

**问题描述**：
`update()` 方法使用 `jpaRepository.save(entity)`，Hibernate 生成的 SQL 只有 `WHERE id = ?`，没有 status 守卫。Worker 的投递结果写回和 StaleLockRecovery 的恢复操作之间没有互斥，后写者覆盖先写者。

**失败场景**：
```
T0: Worker A acquireLock → status=SENDING, lockedUntil=T0+60s
T1: Worker A 发起 HTTP 调用，vendor 挂起超过 60s
T0+65s: StaleLockRecovery 发现 lockedUntil 过期
         → markFailed: status=FAILED, attemptCount++
         → update() 写入 DB
T0+70s: Worker A 的 HTTP 终于返回 200
         → persistSuccess: status=SUCCESS
         → update() 覆盖了 FAILED → 通知被"幽灵成功"
```

反方向同理：Worker 先写完 SUCCESS，StaleLock 后覆盖为 FAILED。

**修复方向**：
- 为 `persistSuccess` / `persistRetryOrDeadLetter` / `persistDeadLetter` / `reschedule` 的写回 SQL 增加 `WHERE status = 'SENDING'` 守卫
- affected rows = 0 时记录 WARNING 日志，表示"写回被忽略：任务已被恢复器处理"

---

## 2. 🔴 Critical — StaleLockRecovery 错误递增 attemptCount

**文件**：`src/main/java/com/examine/application/StaleLockRecoveryAppService.java:87`

**问题描述**：
`recoverOne()` 调用 `request.markFailed()`，执行 `this.attemptCount++`。但崩溃 Worker 发出的 HTTP 请求结果是不确定的——vendor 可能已处理并返回 200（只是响应在途中丢失），也可能从未收到。把它计为一次"失败尝试"语义上不准确，且浪费了重试预算。

**失败场景**：
```
Worker 崩溃时 attemptCount=2，maxAttempts=5。
恢复器执行：attemptCount → 3（假失败）。
下次 Worker 成功 → 实际只做了 2 次真实投递，但 attemptCount 显示 3。
如果 maxAttempts=3，通知会因一次未经证实的"失败"直接进入 DLQ。
```

**修复方向**：
- `recoverOne()` 不再调用 `markFailed()`，改为新的 `releaseStaleLock()` 方法
- `releaseStaleLock()`：status → PENDING，释放 lockedBy/lockedUntil，**不修改 attemptCount**

---

## 3. 🔴 Critical — attemptCount 用 Java 绝对值赋值，并发时丢增量

**文件**：`src/main/java/com/examine/domain/model/NotificationRequest.java:71`

**问题描述**：
`markFailed()` 在 Java 层执行 `this.attemptCount++`，然后通过 `jpaRepository.save()` 将绝对值写入 DB。如果中间有其他操作修改了 DB 中的 attemptCount，Java 侧计算的旧值会覆盖。

**失败场景**：
```
StaleLockRecovery 读到 entity（attemptCount=2），markFailed → 3
Worker 的并发 persistRetryOrDeadLetter 也读到 entity（attemptCount=2），markFailed → 3

两次逻辑递增，DB 最终只有 3，丢失了一次。
```

**修复方向**：
- 写回 SQL 改为 `SET attempt_count = attempt_count + 1`
- 或在 JPA 侧使用 `@Modifying` + 自定义 JPQL，绕过 detached entity 的快照值问题

---

## 4. 🟡 Major — 幂等冲突无 payload 比较

**文件**：`src/main/java/com/examine/domain/service/IdempotencyService.java:48`

**问题描述**：
`toDuplicateResult()` 对于 `PENDING` / `FAILED` 状态的幂等记录直接返回 `Duplicate`，不比较 payload 是否一致。同 idempotencyKey 但不同 payload 时静默返回 200，调用方无法发现错误。

**失败场景**：
```
调用方提交 idempotencyKey='order-123', payload={userId:'u1'} → 202
调用方 bug 导致同一 key 被复用，payload={userId:'u2'} → 200 Duplicate

user 'u2' 的通知被静默丢弃，没有任何错误提示。
```

**修复方向**：
- `IdempotencyRecord` 中增加 `payloadHash` 字段（或直接存 payload 摘要）
- `toDuplicateResult()` 中比对 payload，不一致时返回新的 `AcceptResult.Conflict`

---

## 5. 🟡 Major — 缺少 X-Notification-Attempt header

**文件**：`src/main/java/com/examine/domain/service/VendorRequestAssembler.java:24`

**问题描述**：
`assemble()` 方法注入 `idempotencyKey` 和 `requestId`，但不注入当前尝试次数。Vendor 侧无法区分首次投递和重试。

**失败场景**：
```
Vendor 收到第 5 次重试的通知，但无法感知这是重试。
- 不能对重复尝试做特殊处理（如降低优先级）
- 日志无法关联到具体 attempt
- 失去 webhook 生态的标准实践（Stripe、GitHub 均发送 attempt 计数 header）
```

**修复方向**：
- `VendorRequestAssembler.assemble()` 签名增加 `int attemptCount` 参数
- 注入 `X-Notification-Attempt` header
- 调用方 `DeliveryAppService.tryDispatch()` 传入 `request.getAttemptCount() + 1`

---

## 6. 🟡 Major — payload 无大小上限

**文件**：`src/main/java/com/examine/api/dto/CreateNotificationRequest.java:11`

**问题描述**：
`payload` 字段使用 `@NotNull Map<String, Object> payload`，无 `@Size` 或自定义大小校验。超大的 payload 可以穿透到 DB 和模板渲染层。

**失败场景**：
```
恶意或 buggy 调用方提交 10MB 的 payload → 202 Accepted
→ DB LOB 列被填满 → 每次重试都重新从 DB 加载 10MB → 模板渲染时 OOM
```

**修复方向**：
- `CreateNotificationRequest` 增加 `@Size(max = 262144)` 或自定义 `@MaxPayloadSize` 校验
- `application.yml` 中配置上限值，默认 256 KiB

---

## 7. 🔵 Minor — 缺少 delivery_attempts 独立表

**文件**：`src/main/java/com/examine/domain/service/VendorRequestAssembler.java:40`（相关上下文）

**问题描述**：
当前只在 `NotificationRequest` 上维护聚合的 `attemptCount` 和 `lastError`，没有每次投递尝试的独立记录。运维排查需要翻应用日志。

**失败场景**：
```
运维收到 DLQ 告警："req-123 在 3 次尝试后进入死信"
→ 想排查：第 1 次是超时还是 500？第 2 次耗时多久？返回了什么状态码？
→ 当前只能看到 lastError，无法追溯每次尝试的细节
→ 需要 grep 应用日志按 requestId 拼凑，不可靠且低效
```

**修复方向**：
- 新增 `DeliveryAttemptEntity` + `DeliveryAttemptJpaRepository`
- 投递完成后 insert 一条记录（与状态更新在同一事务内或异步写入）
- `GET /api/v1/notifications/{id}` 响应中增加 attempts 详情的可选字段

---

## 关联问题：第 1、2、3 项的联合修复方案

这三个问题根因相同：**写回路径的并发安全和计数语义**。已一并修复：

1. **NotificationRequestEntity 新增 `@Version`**：乐观锁，UPDATE 自动包含 `WHERE version = ?`
2. **NotificationRequest 新增 `releaseStaleLock()`**：只释放锁、重置 PENDING，不改 attemptCount
3. **StaleLockRecovery 改为调用 `releaseStaleLock()`**：不再递增 attemptCount
4. **Repository.update() 改为预加载合并**：`findById` → `mergeToEntity` → dirty checking，保留 managed entity 的 @Version

> **修复状态**：✅ 已修复（2026-07-27）

---

## 第二轮 Code Review（同批次修复引入的新代码审查）

> 评审日期：2026-07-27
> 评审范围：修复 #1-7 引入的全部变更

### 总览

| # | 严重度 | 分类 | 问题 | 状态 |
|:--|:--|:--|:--|:--|
| 8 | 🔴 Critical | correctness | releaseStaleLock() 无限重试循环：worker 反复崩溃时无死信兜底 | ✅ 已修复 |
| 9 | 🟡 Major | correctness | reschedule() 乐观锁异常未被捕获，泄漏到 Scheduler 记 ERROR | ✅ 已修复 |
| 10 | 🟡 Major | correctness | releaseStaleLock() 未清除 lastError，成功后残留旧错误信息 | ✅ 已修复 |
| 11 | 🟡 Major | correctness | X-Notification-Id/Attempt header 静默覆盖 vendor 同名配置 | ✅ 已修复 |
| 12 | 🟡 Major | efficiency | toDuplicateResult() 对同一条记录调用两次 findById | ✅ 已修复 |
| 13 | 🟡 Major | efficiency | recordAttempt() 独立事务，write-back 失败时 attempt 记录孤儿 | 🟡 接受 |
| 14 | 🔵 Minor | simplicity | toEntity() 和 mergeToEntity() 重复 11 个字段赋值 | 🔵 待修复 |
| 15 | 🔵 Minor | simplicity | NotificationStatusResponse.from(NotificationRequest) 单参数版本无调用方 | 🔵 待修复 |
| 16 | 🔵 Minor | conventions | DeliveryAttemptRepositoryImpl 未使用 EntityMappers，与其他 repo 不一致 | 🔵 待修复 |
| 17 | 🔵 Minor | efficiency | getById() 无条件查询 delivery_attempts，无 query-parameter 跳过 | 🔵 待修复 |
| 18 | 🔵 Minor | efficiency | update() 每次预加载实体（SELECT），额外 DB 往返 | 🟡 接受 |
| 19 | 🔵 Minor | semantics | attemptCount 只有 markFailed 递增，与 delivery_attempt.attempt_number 永远不对齐 | 🔵 待修复 |
| 20 | 🔵 Minor | correctness | IdempotencyRecord 存在但 NotificationRequest 被手动删除时，返回指向不存在记录的 Duplicate | 🔵 待修复 |

---

### 8. 🔴 Critical — releaseStaleLock() 无限重试循环，无死信兜底 ✅

**文件**：`src/main/java/com/examine/application/StaleLockRecoveryAppService.java:54`

**问题描述**：
Worker 反复在 HTTP 调用阶段崩溃（OOM/断电）时，每次 StaleLockRecovery 都只是释放锁回 PENDING，attemptCount 不变。没有代码路径触发 dead-letter 或告警，通知永远 PENDING↔SENDING 循环。

**失败场景**：
```
1. 通知被接受（PENDING, attemptCount=0）
2. Worker A 获取锁，HTTP 调用中，被 OOM 杀死
3. StaleLockRecovery 释放锁 → PENDING, attemptCount 仍为 0
4. Worker B 获取锁，同样崩溃
5. 循环永远继续，无告警，无死信
```

**修复**：增加 `maxStaleAge` 阈值（默认 24h），超过时限直接 dead-letter + ERROR 日志。

---

### 9. 🟡 Major — reschedule() 乐观锁异常泄漏 ✅

**文件**：`src/main/java/com/examine/application/DeliveryAppService.java:128,166`

**问题描述**：
限流/熔断路径调用 `reschedule()`，其内部 `update()` 可能抛 `OptimisticLockingFailureException`。该异常未被 `tryDispatch()` 中 try-catch（仅包裹 persist 操作）捕获，传播到 Scheduler 记 ERROR 日志——对良性并发冲突产生误导性的错误日志。

**修复**：`reschedule()` 内部包裹 try-catch `OptimisticLockingFailureException`，记 INFO。

---

### 10. 🟡 Major — releaseStaleLock() 未清除 lastError ✅

**文件**：`src/main/java/com/examine/domain/model/NotificationRequest.java:102`

**问题描述**：
Worker A 失败记录 `lastError="read timeout"` 后崩溃。恢复器释放锁，Worker B 成功投递。最终 `status=SUCCESS, lastError="read timeout"`——运维看到虚假的失败标记。

**修复**：`releaseStaleLock()` 增加 `this.lastError = null`。

---

### 11. 🟡 Major — X-Notification-Id/Attempt header 覆盖 vendor 配置 ✅

**文件**：`src/main/java/com/examine/domain/service/VendorRequestAssembler.java:41-42`

**问题描述**：
Gateway header 用 `Map.put()` 设置，如果 vendor 自己配置了同名 header，会被静默覆盖。Vendor 可能依赖这些 header 做路由或去重。

**修复**：改为 `putIfAbsent` + WARN 日志，vendor 配置的值优先。

---

### 12. 🟡 Major — toDuplicateResult() 重复 findById ✅

**文件**：`src/main/java/com/examine/domain/service/IdempotencyService.java:45`

**问题描述**：
payload 冲突检查调用一次 `findById`，PENDING/FAILED 分支的 `currentStatusOf()` 再次调用 `findById` 查询同一条记录。重复提交场景下 DB 查询翻倍。

**修复**：复用第一次查询结果，移除 `currentStatusOf()` 方法。

---

### 13. 🟡 Major — recordAttempt() 孤儿记录（接受现状）

**文件**：`src/main/java/com/examine/application/DeliveryAppService.java:144`

**问题描述**：
`recordAttempt()` 在独立事务中提交，如果后续 write-back 因乐观锁冲突失败，attempt 记录已落库但 notification 状态未更新。下次重试会产生相同 attemptNumber 的重复记录。

**决策**：接受。HTTP 调用已是历史事实，always-record 语义正确。孤儿记录不影响 delivery 正确性（at-least-once 允许重复）。

---

### 14. 🔵 Minor — toEntity/mergeToEntity 字段赋值重复

**文件**：`src/main/java/com/examine/infrastructure/persistence/EntityMappers.java:49,67`

**问题描述**：两个方法各自赋值相同的 11 个可变字段。新增字段时容易只改一个，导致 `save()` 和 `update()` 行为分化。

---

### 15. 🔵 Minor — 单参数 from() 死代码

**文件**：`src/main/java/com/examine/api/dto/NotificationStatusResponse.java:24`

**问题描述**：`from(NotificationRequest)` 无任何生产代码调用，永远传 `List.of()`。

---

### 16. 🔵 Minor — DeliveryAttemptRepositoryImpl 未使用 EntityMappers

**文件**：`src/main/java/com/examine/infrastructure/persistence/DeliveryAttemptRepositoryImpl.java:22`

**问题描述**：其余三个 Repository 实现均注入 `EntityMappers` 做映射，唯独这个新实现手动构造 Entity。不一致。

---

### 17. 🔵 Minor — getById() 无条件查询 attempts

**文件**：`src/main/java/com/examine/api/NotificationController.java:62`

**问题描述**：无 query-parameter 跳过 attempt 查询。轮询客户端只需 status，每次仍加载全部历史。

---

### 18. 🔵 Minor — update() 额外 SELECT（接受现状）

**文件**：`src/main/java/com/examine/infrastructure/persistence/NotificationRequestRepositoryImpl.java:35`

**问题描述**：每次状态写回先 `findById` 再 merge，多一次 DB 往返。代价是 @Version 乐观锁保护的前提。

**决策**：接受。@Version 通过 managed entity 的 version 提供 WHERE 守卫，无更轻量的替代方案（除非在 domain 层暴露 version）。

---

### 19. 🔵 Minor — attemptCount 语义与 delivery_attempt.attempt_number 不对齐

**文件**：`src/main/java/com/examine/domain/model/NotificationRequest.java:63-81`

**问题描述**：只有 `markFailed()` 递增 `attemptCount`，`markSuccess()` 和 `markDeadLettered()` 不递增。但 `delivery_attempt.attempt_number` 每次 HTTP 调用都递增。结果是通知成功后 `attempt_count=0` 但 `delivery_attempt` 有 `attempt_number=1`。

---

### 20. 🔵 Minor — IdempotencyRecord 存在但 NotificationRequest 被手动删除时返回幽灵引用

**文件**：`src/main/java/com/examine/domain/service/IdempotencyService.java:47`

**问题描述**：`findById` 返回 null 时, null guard 跳过冲突检查，但仍返回指向不存在记录的 `Duplicate`。调用方拿到一个 404 的 requestId。需手动删 DB 才触发。
