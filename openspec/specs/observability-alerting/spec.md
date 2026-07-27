# observability-alerting

## Purpose

暴露通知生命周期日志、Micrometer 指标，并在通知进入 DLQ 或 vendor 失败率过高时触发异步告警。

## Requirements

### Requirement: 结构化日志
系统 SHALL 使用 SLF4J + MDC 输出结构化日志，关键日志字段至少包含 `requestId`、`vendorKey`、`idempotencyKey` 和事件类型。

#### Scenario: 受理通知
- **WHEN** 系统成功受理一条通知
- **THEN** 输出 INFO 级别日志，包含上述字段和事件类型 `NOTIFICATION_ACCEPTED`

#### Scenario: 投递成功
- **WHEN** 通知成功投递到 vendor
- **THEN** 输出 INFO 级别日志，事件类型为 `NOTIFICATION_DELIVERED`

#### Scenario: 进入死信
- **WHEN** 通知进入 DEAD_LETTERED
- **THEN** 输出 ERROR 级别日志，事件类型为 `NOTIFICATION_DEAD_LETTERED`

### Requirement: Micrometer 指标暴露
系统 SHALL 通过 Spring Boot Actuator + Micrometer 暴露关键指标，至少包括接收总数、成功投递数、可重试失败数、死信数、当前 PENDING 数和当前 DLQ 数。

#### Scenario: 指标可被访问
- **WHEN** 调用者访问 `/actuator/metrics/notification.delivered.total`
- **THEN** 系统返回该指标的当前值

### Requirement: DLQ 告警
系统 SHALL 在通知进入 DLQ 时触发异步告警，告警内容至少包含事件类型、vendor、requestId、时间戳、错误摘要和建议动作。

#### Scenario: 死信触发告警
- **WHEN** 某通知状态变为 DEAD_LETTERED
- **THEN** 系统在事务提交后异步向配置的 Webhook 发送告警，失败时写入 ERROR 日志

### Requirement: Vendor 失败率告警
系统 SHALL 在某 vendor 连续失败率或连续失败次数超过阈值时触发告警。

#### Scenario: 失败率过高
- **WHEN** 某 vendor 在最近 N 次尝试中失败率达到阈值
- **THEN** 系统触发 P2 级别告警

### Requirement: 告警收敛
系统 SHALL 对同一事件在短时间内进行告警收敛，避免告警风暴。

#### Scenario: 同一 vendor 大量死信
- **WHEN** 同一 vendor 短时间内产生多条死信
- **THEN** 系统只发送一次或按配置的冷却间隔发送告警
