## ADDED Requirements

### Requirement: 按 vendor 限流
系统 SHALL 在 Worker 投递前按 `VendorConfig` 中的限流参数对出站请求进行限流；若被限流阻挡，记录保持 PENDING，仅推迟 `nextRetryAt`，不增加 `attemptCount`。

#### Scenario: 未触发限流
- **WHEN** 某 vendor 当前投递速率低于配置的 QPS/burst
- **THEN** 系统允许本次投递继续

#### Scenario: 触发限流
- **WHEN** 某 vendor 当前投递速率超过配置的 QPS/burst
- **THEN** 系统将 `nextRetryAt` 推迟到下一个可用时间窗，记录仍保持 PENDING

### Requirement: 限流算法
系统 SHALL 使用令牌桶算法实现限流，允许短突发，平均速率稳定。

#### Scenario: 突发流量
- **WHEN** 短时间内有大量通知需要投递到同一 vendor
- **THEN** 系统在 burst 范围内允许突发，超出部分被限流

### Requirement: 熔断器状态机
系统 SHALL 为每个 vendor 维护一个熔断器，状态包括 CLOSED、OPEN、HALF_OPEN，并根据失败率或连续失败次数自动转换。

#### Scenario: 熔断器关闭时正常投递
- **WHEN** 熔断器处于 CLOSED 状态
- **THEN** Worker 正常投递该 vendor 的通知

#### Scenario: 熔断器打开时跳过投递
- **WHEN** 熔断器处于 OPEN 状态
- **THEN** Worker 跳过该 vendor 的投递，将 `nextRetryAt` 推迟到冷却期后，`attemptCount` 不增加

#### Scenario: 半开探测成功
- **WHEN** 熔断器处于 HALF_OPEN 状态且探测请求成功
- **THEN** 熔断器切换为 CLOSED

#### Scenario: 半开探测失败
- **WHEN** 熔断器处于 HALF_OPEN 状态且探测请求失败
- **THEN** 熔断器重新切换为 OPEN

### Requirement: 手动熔断控制
系统 SHALL 支持通过 Admin API 或配置将某 vendor 的熔断器强制设置为 FORCE_OPEN、FORCE_CLOSED 或 AUTO 模式，手动模式优先级高于自动模式。

#### Scenario: 管理员强制熔断
- **WHEN** 管理员将某 vendor 熔断模式设为 FORCE_OPEN
- **THEN** 该 vendor 的所有投递被暂停，直到模式改回 AUTO 或 FORCE_CLOSED

### Requirement: 限流与熔断状态不持久化
系统 SHALL 将限流令牌状态和熔断状态保存在内存中，服务重启后重置是可接受的。

#### Scenario: 服务重启
- **WHEN** 服务重启
- **THEN** 限流桶和熔断器状态重新初始化
