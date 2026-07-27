# reliable-delivery

## Purpose

通过 Scheduler + Worker 模型异步投递通知，支持指数退避重试、错误分类、死信队列和人工重放。

## Requirements

### Requirement: 异步调度待投递通知
系统 SHALL 使用 Scheduler 定时扫描数据库，找出状态为 `PENDING` 且 `nextRetryAt <= now` 的记录，并分发给 Worker 处理。

#### Scenario: 存在可投递记录
- **WHEN** Scheduler 扫描到状态为 PENDING 且到达投递时间的记录
- **THEN** Worker 能够获取租约锁并开始投递

#### Scenario: 无可投递记录
- **WHEN** Scheduler 未扫描到可投递记录
- **THEN** 本轮调度不执行任何投递动作

### Requirement: 租约锁控制并发
系统 SHALL 在 Worker 投递前通过原子操作获取租约锁，设置 `lockedBy` 和 `lockedUntil`，确保同一条记录不会被多个 Worker 并发处理。

#### Scenario: 成功获取锁
- **WHEN** 某 PENDING 记录未被锁定或锁已过期
- **THEN** Worker 原子更新该记录为 SENDING 状态并设置锁信息

#### Scenario: 锁未过期
- **WHEN** 某 PENDING 记录已被其他 Worker 锁定且未过期
- **THEN** 当前 Worker 跳过该记录

### Requirement: 按 vendor 组装并发送 HTTP 请求
系统 SHALL 根据 `vendorKey` 对应的 `VendorConfig` 组装 HTTP 请求（URL、method、headers、body），并将 `idempotencyKey` 按配置注入 header 或 body，最后通过 HTTP 客户端发送。

#### Scenario: 正常组装 POST 请求
- **WHEN** Worker 处理一条 vendor 配置为 POST 方法的通知
- **THEN** 系统按模板渲染 body 和 headers，并发送 POST 请求

#### Scenario: 模板渲染失败
- **WHEN** body 模板中存在无法解析的占位符或格式错误
- **THEN** 系统将该通知标记为 DEAD_LETTERED，不增加尝试次数

### Requirement: 投递结果分类与状态转换
系统 SHALL 根据 HTTP 响应码或异常类型将投递结果分类为 Success、RetryableFailure、RateLimited、NonRetryableFailure，并驱动状态机转换。

#### Scenario: 收到 2xx 响应
- **WHEN** vendor 返回 2xx 状态码
- **THEN** 系统将记录更新为 SUCCESS，记录投递时间，释放锁，并更新幂等记录为 SUCCESS

#### Scenario: 收到可重试错误
- **WHEN** vendor 返回 5xx 或发生网络超时/连接失败
- **THEN** 系统将记录更新为 FAILED，`attemptCount` 加 1，计算下次重试时间，释放锁

#### Scenario: 收到 429 限流
- **WHEN** vendor 返回 429 并携带 `Retry-After`
- **THEN** 系统使用 `Retry-After` 计算 `nextRetryAt`，`attemptCount` 加 1

#### Scenario: 收到不可重试错误
- **WHEN** vendor 返回 4xx（除 429）
- **THEN** 系统将记录更新为 DEAD_LETTERED，不增加尝试次数，释放锁，并更新幂等记录

### Requirement: 重试策略
系统 SHALL 对可重试失败使用指数退避 + 抖动策略计算下次重试时间，并设置最大尝试次数上限。

#### Scenario: 首次失败后重试
- **WHEN** 通知第一次投递失败
- **THEN** `nextRetryAt` 按 baseDelay * 2^0 + jitter 计算，并不超过 maxDelay

#### Scenario: 超过最大尝试次数
- **WHEN** `attemptCount` 达到 `VendorConfig` 配置的最大尝试次数
- **THEN** 系统将记录更新为 DEAD_LETTERED，触发告警

### Requirement: 死信队列与人工重放
系统 SHALL 将超过最大重试次数或不可重试失败的通知标记为 DEAD_LETTERED，并提供 Admin 接口将其重新置为 PENDING 以进行人工重放。

#### Scenario: 通知进入死信
- **WHEN** 通知投递失败且不可重试或超过最大重试次数
- **THEN** 记录状态变为 DEAD_LETTERED，并触发 DLQ 告警

#### Scenario: 人工重放死信
- **WHEN** 管理员调用 `POST /admin/v1/dead-letters/{requestId}/retry`
- **THEN** 系统将对应记录重置为 PENDING，`attemptCount` 重置为 0 或按策略保留，并返回新的状态

### Requirement: 锁超时恢复
系统 SHALL 定期扫描锁超时的 `SENDING` 记录，将其视为一次失败尝试并按重试策略处理。

#### Scenario: Worker 崩溃导致锁超时
- **WHEN** 某记录处于 SENDING 状态且 `lockedUntil <= now`
- **THEN** Scheduler 将其 `attemptCount` 加 1，计算 `nextRetryAt`，状态置为 FAILED；若超过最大尝试次数则置为 DEAD_LETTERED
