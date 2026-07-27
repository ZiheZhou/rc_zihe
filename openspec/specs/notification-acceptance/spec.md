# notification-acceptance

## Purpose

接收业务系统提交的通知请求，校验参数，写入持久化队列，并基于幂等键返回已接受/已存在/死信等状态。

## Requirements

### Requirement: 接收并校验通知请求
系统 SHALL 提供 `POST /api/v1/notifications` 接口，接收业务系统提交的 `vendorKey`、`idempotencyKey` 和 `payload`，并对必填字段和基本格式进行校验。

#### Scenario: 提交合法新通知
- **WHEN** 业务系统使用合法参数调用 `POST /api/v1/notifications`
- **THEN** 系统返回 202 Accepted，响应体包含新生成的 `requestId` 和状态 `PENDING`

#### Scenario: 缺少必填字段
- **WHEN** 业务系统提交的请求体缺少 `vendorKey` 或 `idempotencyKey`
- **THEN** 系统返回 400 Bad Request，并指明缺失字段

#### Scenario: vendorKey 不存在
- **WHEN** 业务系统提交的 `vendorKey` 在系统中没有对应配置
- **THEN** 系统返回 400 Bad Request，提示 vendor 不存在

### Requirement: 基于幂等键去重
系统 SHALL 使用 `vendorKey + idempotencyKey` 维护幂等记录，保证在保留窗口内同一业务事件只被受理一次，并按当前最终状态返回相应响应。

#### Scenario: 首次提交
- **WHEN** 某 `vendorKey + idempotencyKey` 第一次被提交
- **THEN** 系统创建新的 `NotificationRequest` 和 `IdempotencyRecord`，返回 202 Accepted

#### Scenario: 重复提交且已成功
- **WHEN** 某 `vendorKey + idempotencyKey` 已处于 SUCCESS 状态
- **THEN** 系统返回 200 OK 及原 `requestId` 和 `SUCCESS` 状态，不创建新请求

#### Scenario: 重复提交且处理中
- **WHEN** 某 `vendorKey + idempotencyKey` 已处于 PENDING / SENDING / FAILED 状态
- **THEN** 系统返回 200 OK 及当前 `requestId` 和当前状态，不创建新请求

#### Scenario: 重复提交且已进入死信
- **WHEN** 某 `vendorKey + idempotencyKey` 已处于 DEAD_LETTERED 状态
- **THEN** 系统返回 409 Conflict，提示用户通过死信重放接口处理

### Requirement: 查询通知状态
系统 SHALL 提供 `GET /api/v1/notifications/{requestId}` 接口，返回通知的当前状态、尝试次数、下次重试时间、成功投递时间和最近错误摘要。

#### Scenario: 查询存在的状态
- **WHEN** 调用者使用存在的 `requestId` 查询
- **THEN** 系统返回该通知的完整状态信息

#### Scenario: 查询不存在的状态
- **WHEN** 调用者使用不存在的 `requestId` 查询
- **THEN** 系统返回 404 Not Found
