# AI 使用记录（ai-usage.md）

本文件记录本次作业中 AI（Claude Code）的协作方式、人工决策点，以及实现与计划/文档的偏离说明。

## 协作方式

整个项目按「讨论 → 架构文档 → 技术设计 → 精简计划 → TDD 实现」推进：

1. **问题讨论（人工主导）**：先不写代码，围绕投递语义、幂等、重试、锁超时恢复、DLQ、限流、熔断、告警、系统边界逐项讨论，人工拍板关键取舍。
2. **文档沉淀（AI 起草，人工确认）**：`docs/design.md`（架构）、`docs/technical-design.md`（技术设计）、`docs/plans/`（精简实施计划：只列任务拆解 + 文件结构 + 接口约定）。
3. **TDD 实现（AI 执行，逐任务提交）**：17 个任务，每个任务先写失败测试再实现，通过全套测试后单独 commit。

## AI 帮助最大的点

- **样板代码批量产出**：JPA Entity/Repository/DTO/Controller 等结构化代码，AI 生成速度快且风格一致。
- **边界 case 枚举**：幂等四种分支、熔断三种模式、重试抖动封顶等测试场景清单。
- **问题定位**：`@DataJpaTest` 中 bulk JPQL 绕过一级缓存导致断言失败、WireMock thin 包缺 Jetty、两个构造器 Spring 无法抉择等问题，AI 能快速给出根因与修法。
- **E2E 场景编排**：WireMock 状态机（Scenario）+ Awaitility 组合覆盖 5 个端到端场景，一次通过。

## 人工决策（AI 建议但未自动采纳）

- **投递语义**：至少一次 + 幂等去重，而非追求 exactly-once（人工判断外部 vendor 无法控制，exactly-once 成本不可接受）。
- **MVP 范围**：幂等必须进 MVP；MetaQ/Kafka/Redis 不进 MVP，但取舍必须写进文档。
- **429 Retry-After 传递**：选方案 A（`Optional<Duration> hint` 传入 `RetryPolicy.calculateNextRetry`），而非在分类器里直接算好时间戳。
- **限流/熔断配置归属**：作为 VendorConfig 一部分，且支持运行时手动调整（含 FORCE_OPEN/FORCE_CLOSED）。
- **告警范围**：全局 webhook（方案 A），不做 per-vendor 渠道。

## 实现与计划/文档的偏离

| 偏离 | 原因 |
|---|---|
| FAILED→PENDING 合并为一跳（FAILED 到期直接参与派发查询） | 减少一次无意义的状态迁移，语义等价；已在计划中声明 |
| HTTP 客户端用 JDK `HttpClient` 而非计划中的 RestClient | RestClient 不支持 per-request 读超时，而每个 vendor 超时不同；JDK HttpClient 的 `HttpRequest.timeout()` 精确匹配需求 |
| domain 服务（IdempotencyService/VendorRequestAssembler/Classifier）不带 Spring 注解，统一在 `DomainServiceConfig` 装配 | 保持 domain 层零框架依赖的分层约束 |
| DeliveryAppService/StaleLockRecoveryAppService 用 `TransactionTemplate` 而非 `@Transactional` | 同类内自调用 `@Transactional` 不生效，显式模板更诚实；HTTP 调用保持在事务外 |
| 限流桶/熔断器按 settings 变化自动重建，而非仅在 refresh 时清桶 | 配置变更即生效，无需缓存层与限流/熔断层之间引入显式耦合 |
| WireMock 依赖用 `wiremock-standalone` | thin 包不含 Jetty，standalone 避免与 Spring Boot 的 Jetty 版本对齐问题 |

## 已知限制（MVP 边界）

- 单实例部署假设：租约锁防并发重复投递，但多实例水平扩展未验证（DB 锁机制本身支持）。
- 模板引擎为最简 `{{var}}` 占位符，无 URL 模板、无嵌套 JSON 渲染。
- 告警只有全局 webhook + 日志兜底，无告警升级/值班路由。
- 管理端无鉴权（内网假设）。

## AI工具和模型

- claude
- kimi-3 + kimi-2.7 + Deepseek