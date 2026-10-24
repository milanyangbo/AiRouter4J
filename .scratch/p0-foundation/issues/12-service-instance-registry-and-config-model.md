# 12: 服务 / 实例 / 适配器配置模型与运行时注册表

> 来源：[ADR-0011](../../docs/adr/0011-feature-roadmap-and-migration-boundary.md) 决策 1、3、4

**What to build:** 让「服务 → 多个实例」这个维度真的存在：一份可校验的配置模型
（服务 / 实例 / 适配器 / 网关策略），一个把它变成运行时对象的注册表，
以及一条不重建整个上下文的热重载路径。

这是 ADR-0011 判断的**地基**：负载均衡、规则路由、失败转移、限流作用域、配额维度、
控制台的实例管理，全部以它为前提。

**Blocked by:** 11 存储与迁移（配置版本与运行时覆盖要落库）

**Status:** todo

- [ ] 配置模型：Service（`adapter` / `instances` / `loadBalance` / `rateLimit` / `circuitBreaker`）、
      Instance（`instanceId` / `name` / `baseUrl` / `path` / `adapter` / `weight` / `headers` / `tags`）。
      实例级下游凭据只放在该实例自己的 `headers` 里
- [ ] 适配器选择延续 `UpstreamShape`：vLLM / GPUStack / Xinference / LocalAI **不是四个类**，
      而是 `openai-compatible` 形态 + 各自默认地址与路径的配置
- [ ] 启动期完整性校验：实例地址与形态不符、LB 类型不存在、熔断阈值非正、
      权重全为零——**启动即失败**并指出是哪个服务的哪个实例（不靠运行期报 404 才发现）
- [ ] 配置来源可追溯：启动时打印每个配置块的来源（classpath yml / 外部挂载 / 库表 / 环境变量），
      排查「改了没生效」时这是唯一可靠的入口
- [ ] 热重载：改实例 / 权重 / 策略后**不重建上下文**，在途请求不受影响；
      重载失败时保持旧配置并报错，不允许「半套配置」
- [ ] 库表覆盖 yml：控制台改过的运行时配置落库，启动时库优先、无行回落 yml
- [ ] 配置来源只从环境变量取敏感值；受跟踪文件里不出现真实凭证

## 实现要点（来自 legacy 的教训）

- legacy 有 **4 套并存的版本服务**（`VersionControlService` / `VersionManagementService` /
  `ConfigVersionManager` / `VersionMetadataManager`），其中一个是内存 `AtomicInteger`。
  我们只留**一套**版本存储，版本与回滚的深化放到票 26。
- legacy 的热重载是「写 store → 发事件 → 异步分发」，这条链路值得学：
  它把「改配置」与「重建对象」分开，因此失败可以回滚。不要做成 `RefreshScope` 式的整体刷新。
- 双轨鉴权里 WebFlux 下类级 `@PreAuthorize` 不可靠（legacy 自己的注释承认）——
  这条教训同样适用于「配置校验只写在注解上」：校验必须有**启动期**那一处，不能只靠 Bean Validation。
- 校验消息要指名环境变量（与 `EndpointProperties` 同款），否则运维拿到「配置无效」无从下手。
