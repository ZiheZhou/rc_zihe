## ADDED Requirements

### Requirement: Vendor 配置的 CRUD 管理
系统 SHALL 提供 Admin REST API 对 `VendorConfig` 进行创建、查询、更新和删除，并在修改后刷新内存缓存。

#### Scenario: 创建新 vendor 配置
- **WHEN** 管理员调用 `POST /admin/v1/vendor-configs` 提交合法配置
- **THEN** 系统保存配置并返回 201 Created

#### Scenario: 查询 vendor 配置
- **WHEN** 管理员调用 `GET /admin/v1/vendor-configs/{vendorKey}`
- **THEN** 系统返回对应 vendor 的完整配置

#### Scenario: 更新 vendor 配置
- **WHEN** 管理员调用 `PUT /admin/v1/vendor-configs/{vendorKey}`
- **THEN** 系统更新配置并刷新内存缓存

#### Scenario: 删除 vendor 配置
- **WHEN** 管理员调用 `DELETE /admin/v1/vendor-configs/{vendorKey}`
- **THEN** 系统删除配置，并拒绝后续使用该 vendorKey 的新通知

### Requirement: Vendor 配置模型
`VendorConfig` SHALL 包含 endpoint、HTTP method、headers、body template、timeout、retry policy、rate limit、熔断参数以及幂等键位置与字段名。

#### Scenario: 配置包含完整字段
- **WHEN** 管理员创建或更新配置时提供所有必填字段
- **THEN** 系统校验并保存配置

#### Scenario: 配置缺少必填字段
- **WHEN** 管理员提交的配置缺少 endpoint 或 method
- **THEN** 系统返回 400 Bad Request

### Requirement: 启动时加载配置缓存
系统 SHALL 在应用启动时从数据库加载全部 `VendorConfig` 到内存，并在运行期通过 Admin API 更新后主动刷新。

#### Scenario: 启动加载
- **WHEN** 应用启动完成
- **THEN** 内存中已加载所有 vendor 配置

#### Scenario: 更新后刷新
- **WHEN** 管理员更新某 vendor 配置
- **THEN** 内存缓存立即反映最新配置

### Requirement: Dry Run 预览渲染结果
系统 SHALL 提供 Admin 接口或能力，允许管理员在不实际发送请求的情况下预览给定 payload 渲染后的 HTTP 请求。

#### Scenario: 预览 body 渲染
- **WHEN** 管理员提交 vendorKey 和示例 payload 调用 dry-run
- **THEN** 系统返回渲染后的 URL、headers、body，不触发真实投递
