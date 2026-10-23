# JAiRouter 深度代码评审（作为 AiRouter4J 重写的基线）

> 评审日期：2026-10-23
> 评审对象：`src/main/java` 819 文件 / 149,148 行；`src/test` 426 文件 / 99,857 行；配置 2,435 行；Flyway 双方言 8 个脚本
> 评审目的：**不是维护旧项目，而是为新项目（Spring Boot 4.1 + Spring AI 2.0.1 全量重写）列出"必须改对的"与"必须继承的"清单**
> 方法：逐文件打开核对，每条结论附文件路径 + 行号。含关键的 ** rifles-verify **（自行跑 irreversible 验证的部分已标注）

---

## 0. 一句话结论

| 维度 | 判定 |
|---|---|
| 代码诚实度 | **高**。README 与实际高度吻合，甚至主动列出"明确不实现项"；代码注释里有大量自曝短板的记录（`#215` 33 个死键、`#256` 残行 bug、`#193` Job 退出码） |
| 工程质量 | **参差**。流式 SSE 管道、`StreamingErrorTermination`、SSRF 双层守卫、配置版本回滚这几段属于教科书水平；但也有多处"看起来对、其实是假的"陷阱（见 §2.7、§3.4、§5.3） |
| 主要问题 | **过度组合**：同一抽象写 2~4 遍。 Carroll **65% 的代码量可压缩而功能无损**（见 §6） |
| 能否全量重写 | **能**。前提是把它当"参考实现"而非"迁移目标"（见 §7） |

---

## 1. 项目画像（实测数字）

| 项 | 数值 |
|---|---|
| 主代码 | 819 文件 / 149,148 行 |
| 测试 | 426 文件 / **4,266 个 `@Test`**，其中 `@SpringBootTest` **仅 4 个**，Testcontainers **0** |
| 技术栈 | Java 17 / Boot **3.5.5** / WebFlux / JPA(Hibernate 6) / Flyway / Caffeine + Redis(optional) / jjwt 0.12.3 |
| Spring AI | **完全不存在**（`grep -rn "org.springframework.ai" pom.xml src/` → 空） |
| 顶层包 | 6 个：`config`(94文件) / `auth`(145) / `common`(110) / `monitor`(193) / `persistence`(67) / `router`(175) |
| 包间循环 | **9 组成环对**，6 个顶层包几乎全互连 |
| 上帝类 | 31 个 >500 行；4 个 >900 行（最大 `QuotaLedgerService` **1268 行**） |
| 数据库 | 20 表 / **45 索引** / H2+Postgres 双 Flyway |
| CI | **无**（`.github` 目录不存在）；`.mvn/` 缺失 → `./mvnw` 不可用；`.gitignore` 不存在 |

---

## 2. 领域一：路由与代理核心链路

### 2.1 调用链路（精简版）

```
Controller (OpenAi / Anthropic / Universal 三个面)
  └─ ServiceRequestHandler#handleRequest          (:235)
     ├─ ReactiveSecurityContextHolder.getContext
     ├─ resolveCallerId / hasServicePermission
     ├─ prepareResponseCacheKey → selectInstance → ModelServiceRegistry#selectInstance (:232-399)
     └─ executeWithTracingAndMetrics → executor.execute
        └─ BaseAdapter#chat → processRequestWithFallback → processRequestWithRetry
           └─ StreamingRequestProcessor / NonStreamingRequestProcessor
```

三个面最后都用 lambda 把"调哪个 adapter 方法"延后交给同一个 handler —— 这个设计是对的。

### 2.2 🔴 P0-1 流式请求绕过了熔断器 / 失败指标 / 降级

这是全链路最严重的缺陷，证据链三跳：

```java
// 跳1 流式 Mono 在写出响应头后立刻成功
StreamingRequestProcessor.java:412-414
return Mono.just(ResponseEntity.ok().contentType(TEXT_EVENT_STREAM).body(v1Stream));

// 跳2 外层 doOnSuccess 因此立刻以 success@0ms 结算
BaseAdapter.java:191-203
.doOnSuccess(response -> { long duration = ...-startTime;  // ≈ 0ms
    boolean success = response.getStatusCode().is2xxSuccessful();  // true
    metricsRecorder.recordCompleteCallStats(..., success, ...); })

// 跳3 成功直接穿透到熔断器
ModelServiceRegistry.java:466
circuitBreakerManager.recordSuccess(instanceId, baseUrl);
```

而流式中途失败只走到 `recordStreamingError`（`:474-485`），**不碰 registry / 不碰熔断器 / 不碰 ErrorTracker / 不走 fallback**。

**业务后果**
- 实例只要还能返回 200 头 + 半个 SSE，熔断器**永不 OPEN**，用户永远被路由到坏实例；
- `LatencyAwareLoadBalancer` 学到的流式样本恒为 ~0ms → EWMA 被污染，延迟感知 LB 在流式为主时退化成随机；
- `processRequestWithFallback`（BaseAdapter:250-289）对流式是死代码。

**新版本的验收指标**：`OutcomeObserver#onFinished(signal=CANCEL|ERROR)` 必须到达 `CircuitBreaker.recordFailure`；用一条集成测试钉死"下游 500 在首块之后 → N 次后 CB 变 OPEN"。

### 2.3 🔴 P0-2 `WebClient` 池 60s `responseTimeout` 会掐断长流；256KB `maxInMemorySize` 未配

```java
WebClientPool.java:194   .responseTimeout(Duration.ofSeconds(responseTimeoutSeconds))  // 默认 60s
```

`responseTimeout` 是**总响应超时**，任何超过 60s 的 SSE 流都会被基础设施掐断。它与 `StreamingSafetyProperties.idleTimeout`（默认 5 分钟，**块间**空闲超时，语义正确）直接打架，**60s 的先到**。

同时全仓 `grep "maxInMemorySize|CodecConfigurer"` → **零命中**，即使用 Spring Boot 默认的 256KB。后果：非流式 `.toEntity(String)`（>256KB 的图片/批量 embedding）必然 `DataBufferLimitException`。

**新版本的硬边界**：`text/event-stream` 的下游客户端不设 `responseTimeout`；`maxInMemorySize` 显式配到 16MB。

### 2.4 ⚠️ 契约分裂：同一个 handler 的"健康"默认値自相矛盾

```java
ServiceStateManager.java:54      return instanceHealthBits.isHealthy(key, true);  // 默认健康
ServiceStateManager.java:68-73   return status != null ? status : false;          // 默认不健康
```

而 `InstanceSelector#isInstanceHealthy`（:89-104）用后者。同一个实例走两条路径得到相反结论。

### 2.5 ⚠️ `CachedWeightedSelector` 三个 bug

- `:90-96` 每次选择遍历实例列表拼 String 当 cache key（O(n) 分配），与类注释宣称的"避免每次请求创建临时列表"自相矛盾；
- `:66-77` `invalidate(instanceId)` **完全没用传入参**，谓词 `probability[i]>0 && alias[i]>=0` 几乎恒真 → 等价 `clearAll`；
- `selectorCache` 无淘汰策略，key 是"健康实例集合指纹" → 实例抖动时**无界增长**。

### 2.6 架构冗余：一个 chunk 解析 4 次

Anthropic 流式路径：`StreamingRequestProcessor:244` → `:256` → `OpenAiResponseTranslatorImpl:49/70` → `AnthropicStreamingTranslator:609/785` = **4 次 `readTree` + 2 次 `writeValueAsString`**。中间那次 OpenAI↔OpenAI 的"标准化再解析"对 Anthropic 面毫无收益。

另外 `ClaudeAdapter:292-297` 与 `GeminiAdapter:379-384` 的 `transformStreamChunk` **是 `return chunk`**（未实现），即这两个面的流式实际上是把下游原生协议字节直接吐给 OpenAI 客户端。`grep -rln "String transformStreamChunk"` = **19 处** —— 典型的 N×N 翻译。

→ **新版本**：`ProviderProtocol.decodeStream` 是唯一解析点，每新增 provider / client face 只加 1 个类（N+M）。

### 2.7 做得对、必须继承的（这一层其实是项目最精华的部分）

1. **`StreamingErrorTermination`（StreamingErrorTermination.java:52-63）** —— 三条原则均正确：① 错误路径**绝不补 `[DONE]`**（注释里引了前端 `universal.ts` 见 `[DONE]` 即 `onComplete()`，补了会把截断读成成功）；② 补完 error 帧**仍以原异常终止**（`Flux.concat(just(errorFrame), error(t))`）；③ **首块之前不补帧**（issue #356），配合 `/v1` 面在订阅响应体之前判定 `onStatus`。
2. **`AnthropicStreamingTranslator:207-222` 的订阅级状态 + `Flux.defer`** —— 状态在 `defer` 内创建不跨订阅共享（天然线程安全）；`concatMap` 而非 `flatMap` 保证顺序；收尾事件用 `defer` 延迟求值读累计状态。**全仓写 Reactor 的最佳范本。**
3. **空闲型而非总时长型的看门狗** —— `StreamingSafetyProperties` 明确 `idle-timeout` = 块间空闲，默认 PT5M，PT0S 可禁用。与 content 1Mi / cache 256Ki 构成清晰的资源 SLO 三角。
4. **截断不影响账单的双轨计数**（`:191-197, 246-253, 803-806`）—— 累积缓冲可 truncate，但 token 估算走**独立的字符计数**，因此截断不影响配额。`AnthropicStreamingTranslator:226-234` 复制了同一手法。→ 抽成 `BoundedAccumulator`。
5. **`cancel` 与 `onNext` 不串行 → 显式共享监视器**（`:240-253, 746-762`）—— `synchronized(contentBuilder)` 且注释写明了理由。绝大多数 Reactor 代码库会在这里踩坑。→ 抽成 `CancellableBuffer` 类型而非靠注释约定。
6. **`QuotaReservation` 的 CAS 幂等凭据**（`QuotaReservation.java:154-158`）—— 用 `AtomicBoolean` 让 success/error/cancel 三路重复调用天然幂等。→ 从 httpRequest attributes 挪到 Reactor Context。
7. **SSRF 双层守卫**（`WebClientPool:166,190` + `SsrfGuardedAddressResolverGroup`）—— 创建期校验 + 连接期再判 DNS 解析结果（堵 rebinding），且 `WebClientPool` 是强制的唯一出站出口。
8. **粗粒度的同步前缀 offload**（`ServiceRequestHandler:344-348` 的 `Mono.defer(...).subscribeOn(boundedElastic)`）—— 把含 Redis 限流 / JPA 读配置的整段前缀搬出 EventLoop，比逐点 `publishOn` 更可控。

---

## 3. 领域二：配额 / RBAC / 脱敏 / JWT

### 3.1 `QuotaLedgerService`（1268 行）职责拆解

| # | 职责 | 行号 | 应归属 |
|---|---|---|---|
| R1 | 装配 / 后端选路 / 降级状态位 | 114-207 | `QuotaBackendRouter` |
| R2/R3 | 预留 + 多窗口 CAS 限额 | 264-568 | `QuotaReserver` + `WindowCompensator` |
| R4 | 结算 / 冲正 | 589-644 | `QuotaSettler` |
| R5 | 用量查询（4 套语义） | 653-796 | `QuotaQueryService` |
| R6 | 重置 | 807-831 | `QuotaAdminService` |
| R7 | 三个 `@Scheduled` 后台任务 | 842-906 | `QuotaMaintenanceJob` |
| R8 | DB 快照读写 | 914-1120 | `QuotaSnapshotStore` |
| R9 | Redis 适配 | 962-1034 | `QuotaRedisGateway` |

65 个 if / 20 个 try-catch / 3 个 switch 几乎全部集中在 R2+R3 与 R8+R9 的交叉处 —— **不是业务复杂，是"业务编排"和"三种存储后端"揉进了同一个方法体**。

### 3.2 🔴 陷阱：reserve/settle 不是两阶段提交

`QuotaSettlement` 定义为 `record QuotaSettlement(dimension, estimatedTokens, actualTokens, failed)` —— **没有 reservationId、没有 requestId、没有幂等键**。

- reserve 后进程崩溃 → 预留**永久占用**，无 reaper、无 TTL 扫描；
- settle 是 fire-and-forget（`:593-596` 提交到 boundedElastic 后无人等待），进程关闭即丢；
- settle 内部异常只 `log.warn`（`:640-643`）；
- 跨 reserve/settle 窗口 → 旧窗口预留永不冲正（`QuotaSettlement.java:14-15` 自己承认是"已知偏差"）。

`QuotaReservation.java:16` 的 Javadoc 写"**恰一次语义**"——**这是假的**，实际是"至多一次且可能零次"。

### 3.3 🔴 超卖：默认配置下 Redis 故障 = 配额限额整体失效

```java
QuotaProperties.java:66-68        private boolean failOpen = true;          // 默认值
QuotaProperties.java:20-22        distributed.enabled = false;              // 默认值

QuotaLedgerService.java:437-442   // Redis 异常 + failOpen 分支
    localBackend.incrementLocal(key, 1L, tokens);     // ← 无任何限额检查
    localBackend.addPending(key, 1L, tokens);
```

链路：多实例必须开 distributed → Redis 挂掉 → failOpen(默认 true) → **所有 API Key 的日/时/月/分钟限额全部失效，退化为只记账不拦截**。
而非分布式多实例 = N × 限额（`:79-80` Javadoc 自己承认）。

> 面试官问"你们的配额是硬限还是软限？" 正确答案：**Redis 健康时硬限，故障时延等价不限制，且没有告警升级、没有本地兜底限额**。

### 3.4 🔴 SSE 逐帧脱敏在语义上等于没脱敏

```java
SseResponseSanitizer.java:174-186
return sanitizationService.sanitizeResponse(dataLine.payload(), "application/json")
        .map(sanitized -> dataLine.rebuild(sanitized))
        .onErrorResume(ex -> { warn(...); return Mono.just(line); });   // fail-open
```

OpenAI 风格每个 `data:` 帧只带几个字符：`{"delta":{"content":"138"}}` → `...1234` → `...5678`。手机号规则 `(?<!\d)1[3-9]\d{9}(?!\d)` 跨帧就是命中不了，**没有任何一帧包含完整 11 位号码**。

**反差证据**：落库侧是整段脱敏（`StreamingRequestProcessor:315-336` 用累积的 `contentBuilder`）——
**同一个流式响应：写进 DB 的是脱敏后的，发给客户端的是明文。**

另有 `SseResponseSanitizer:104-112` 每个 DataBuffer 独立 `new String(bytes, UTF_8)` → 中文 UTF-8 跨 buffer 截断产生 U+FFFD，对比非流式路径用 `DataBufferUtils.join` 就没这个问题。

### 3.5 ❌ 中文敏感词 100% 失效

```java
OptimizedSanitizationRuleEngine.java:409-414
case SENSITIVE_WORD -> "\\b" + Pattern.quote(rule.getPattern()) + "\\b";
// 编译：Pattern.compile(pattern, CASE_INSENSITIVE) —— 无 UNICODE_CHARACTER_CLASS
```

Java 的 `\b` 基于 `\w = [a-zA-Z_0-9]`，中文是 non-word → `\b机密\b` 在中文语境下永远不成立。**实测**：`\b\Q机密\E\b` 对"这是机密内容"返回 `false`。（默认敏感词是英文 password/secret 所以默认场景不触发，但一个面向中文用户的项目配中文敏感词全是死规则。）

另：`processLargeContentStreaming`（:227-256）号称流式，实际输入已是完整 String，8KB 分块**无重叠** → 跨块边界的手机号/身份证同样漏检。

### 3.6 ⚠️ RBAC：6 个权限码是"死码"

实测比对了 `PermissionCodes`（**63 个**，非 README 声称的 67）与 `PermissionRuleRegistry`：

```
从未被任何 URL 规则引用：
  actuator:admin:manage / ai:playground:use / cb:history:read
  config:services:read / config:services:write / config:validation:write
```

即：**授了没用，不授反而被拒**（未命中→DENY_ALL→仅 ADMIN 直通）。

- **无权限继承**：扁平表 `role_permissions(role_name, permission_code)`，`manage` 不蕴含 `write` 不蕴含 `read`；唯一"继承"是硬编码的 `ROLE_ADMIN` 短路。
- **三套路径匹配器并存**：URL 规则用 `PathPatternParser`、RBAC 豁免清单用 `PathPatternParser`、脱敏范围用 `AntPathMatcher` —— `DataMaskingScope:114-127` 甚至手工 hack 补 Ant 的 `/api/**` 不匹配 `/api`。
- **规则顺序是隐式契约**（75 条规则、首条命中、O(n) 线性），只有注释保护（`PermissionRuleRegistry:149/210/234`），无测试断言。

✅ **但 `RbacEndpointCoverageChecker`（启动期自检）是这部分的亮点**：把"未登记 ⇒ 隐式放行"改成"未登记 ⇒ 启动 WARN + 默认 DENY_ALL"。**唯一缺的是反向校验**（权限码→规则）。

### 3.7 ⚠️ JWT：刷新无旋转、登出有跨实例窗口、权限吊销最长延迟 5min+有效期

- **并发 refresh 无 CAS**：两个并发请求持有同一 token → 都签发成功且都有效，无 reuse detection（`JwtTokenRefreshService:71-101, 404-419`）。
- **登出**：`EnhancedJwtBlacklistService:67-109` 本地缓存只写本实例；Redis 挂时 **fail-open**（`:181-188` Javadoc 自己承认"存储不可用期间被撤销/登出的令牌不会被拦截"）。
- **权限内嵌 JWT** → 变更需重新登录；叠加 Caffeine 5min 缓存 → 吊销最坏延迟 = 5min + token 剩余有效期。高权限码（如 `security:apikeys:manage`）这是不可接受的。→ 正确做法：JWT 只放 `perm_epoch`，鉴权时比对服务端版本号。
- ✅ **`StartupSecretKeyChecker` + `StartupSecurityGate` 做对了**：生产 fail-fast + 已知弱密钥黑名单（`isDefaultJwtSecret` 额外匹配 `devonly` 子串）+ 显式逃生舱环境变量。

---

## 4. 领域三：配置 / 监控 / 持久化

### 4.1 🔴 每次配置变更把全量配置 POST 给后端模型实例（不存在端点）

```java
HttpConfigPushService.java:30   private static final String PUSH_ENDPOINT = "/internal/config/push";
HttpConfigPushService.java:97-98  pushUrl = baseUrl + PUSH_ENDPOINT;   // baseUrl = 后端 ollama/vllm 地址
ConfigSyncEvent.java:30-32, 65-67  refresh(...) → targetService=null → isBroadcast()==true
```

即：**每改一次配置，向所有被代理的下游模型实例 POST 全量 JAiRouter 配置 JSON**（含后端地址、权重、限流阈值）。而这个端点全仓只有定义处和测试两处命中，实际 404。

三重后果：**配置外泄** + 无谓负载 + 语义错位（这个类显然是想做"集群节点间推送"）。

### 4.2 ⚠️ 同一配置键在两份 yml 中、取值不同

`model.services` 子树同时存在于 `config/router/services.yml` 与 `config/base/model-services-base.yml`（后者在第 57 行导入，注释标为"向后兼容"）。diff 127 行，其中 chat 实例指向**两个不同后端**：

```diff
-        - name: "qwen3:4b"      base-url: "http://172.16.30.6:9090"
+        - name: "qwen2.5:1.5b"  base-url: "http://172.16.30.6:12434"   adapter: "ollama"
```

谁生效完全取决于 `spring.config.import` 的覆盖顺序 —— 这是一个**没有任何测试断言的隐式契约**。

### 4.3 ⚠️ 配置校验写了三遍，且语义相反

| 文件 | 行数 | 备注 |
|---|---|---|
| `config/core/ConfigurationValidator.java` | 565 | private 版 4 个白名单方法 |
| `config/core/helper/ConfigValidatorHelper.java` | 591 | public 版，**4 个方法与之逐字节相同** |
| `config/core/manager/ConfigValidator.java` | 253 | 第三套：归一化 + 抛异常 |

而且同一个 `RateLimitConfig` 未启用时，`ConfigValidatorHelper:84-87` 返回 true，`ConfigurationValidator:59` 仍要求 algorithm 合法 —— **两个类给出相反答案**。

全项目 **15 个 `*Validator*`，共 4,884 行**。真正的 schema（`ModelRouterProperties`）反而**零 Jakarta 校验注解**（全项目 23 个 `@ConfigurationProperties` 只有 1 个带 `@Validated`）。

### 4.4 ❌ 两张死表 + 一批只写不读的表

| 表 | 索引 | 判定 |
|---|---|---|
| `exception_stats_hourly` | **4 + 1 唯一约束** | **无 Repository，无写入方，无读取方**；Entity 里 3 个 `incrementXxx()` 零调用 |
| `config_change_audit_log` | 0 | 实体/仓储/服务齐全，但 `ConfigChangeAuditService` 无调用方 |
| `config_version_history` | 3 | 只 `save`；且 `serviceType`、`configSnapshot` **恒 NULL** → 索引白建、快照不可用 |
| `config_audit_log` | 3 | 只 `save`；5 个查询方法全是死代码 |

真正的回滚能力在 `ConfigVersionManager.applyVersion`（:206-267），走的是 `config_data` 表 —— **四张"版本/审计"表里只有一张在为它服务**。

另有 **17 个 `*Version*` 文件 / 2,780 行**，其中 `version/strategy/` 整个包（4 文件 280 行）从未被调用（`ConfigVersionManager:166` 内联 `getCurrentVersion()+1`）。

### 4.5 ❌ 索引错位：45 个索引里 51% 在日志表，热路径核心表 0 索引

- `exception_events` 9 + `api_call_history` 7 + `security_audit` 7 = **23 个索引**（51%）
- **`service_config`（service_type, is_latest, config_key 全无索引）** —— 每次配置加载都查，14 个调用方
- **`service_instance`（外键 `service_config_id` 无索引）** —— PG 上父表 DELETE 会锁全子表 + join 全表扫，9 个调用方

### 4.6 ❌ 导出器健康检查永远报"健康"（507 行基本失效）

```java
ExporterHealthChecker.java:125-131
CompletableFuture<Void> exportFuture = CompletableFuture.runAsync(() -> {
    spanExporter.export(Collections.singletonList(testSpan));   // ← 返回的 CompletableResultCode 被丢弃
});
exportFuture.get(timeout.toMillis(), MILLISECONDS);
return true;
```

OTel 的 `export()` **是异步的**，失败通过返回的 `CompletableResultCode` 报告、**不抛异常**。这段代码只判断"没抛同步异常"就 return true → **collector 完全不可达时也报健康**。

另：`:152-166` `createTestSpanData()` 每次检查**真的**导出一条 `health-check` span（污染 trace 数据），同时把一个手写的 90 行 `TestSpanData` stub（含已废弃 API）喂给 export —— 注释自己承认"为了简化，这里只提供一个空的实现"。

> 唯一有价值的只有去抖动那 ~36 行（`:182-218`）。

### 4.7 ❌ `MetricsBatchReporter`（552 行）在 Prometheus 拉取架构下是冗余的，且语义错误

- `:75` 默认目标 = `LOG`，全项目**无一处调用 `setReportTarget()`** → 每 10s 把全量 Meter 快照塞进内存、每 60s 打一行 INFO 日志。**552 行换来每分钟一行日志。**
- `:115` buffer 满时**静默丢弃**，无 droppedCount（对比 `AsyncMetricsProcessor:64-78` 就做对了：有界队列 + 计数）；
- `:143` poll 抽干后 `:152` 上报失败 → batch 直接 GC，无 retry/DLQ；
- `:404-410` 把 Micrometer `Counter`（单调累计）按 `name value timestamp` 推给 Pushgateway，无 `_total`、无 delta —— **语义错误**；
- 而项目已经有 `micrometer-registry-prometheus`（pom:233）+ `/actuator/prometheus`（monitoring-base.yml:52-54）。Prometheus 是拉取模式，这个推送器是架构错配。

### 4.8 🔴 自研 Controller 遮蔽 `/actuator/metrics`

```java
MetricsManagementController.java:31-32   @RequestMapping("/actuator/metrics")
config/base/monitoring-base.yml:16       include: health,info,metrics,prometheus,...
```

WebFlux 下 `RequestMappingHandlerMapping`(order 0) 优先于 `WebFluxEndpointHandlerMapping` → **Spring Boot 原生 `/actuator/metrics` 被顶掉**（很多监控脚本 / Spring Boot Admin 依赖它）。
且它与 `MonitoringController`（606 行）端点重复：`/degradation/level`、`/degradation/auto-mode`、`/degradation/force-recovery`、`/health` 各两份。

### 4.9 指标命名：实测 7 种风格并存

| 风格 | 样例 | 位置 |
|---|---|---|
| `jairouter.` 点分 | `jairouter.metrics.errors` | `MetricsErrorHandler:44` |
| `jairouter.` 点分 | `jairouter.security.authentication.attempts` | `SecurityMetrics:44` |
| `jairouter_` 下划线 `_total` | `jairouter_tracing_operations_total` | `TracingMetricsDelegate:63` |
| `jairouter_` 无 `_total` | `jairouter_requests_total` | `DefaultMetricsCollector` |
| **无前缀** | `jwt.cleanup.token.executions` | `CleanupMetricsService:70` |
| **无前缀另一棵树** | `tracing.performance.anomalies` | `TracingPerformanceMonitor:128` |
| 独立可配前缀 | `properties...getCounterPrefix()+".total"` | `ErrorMetricsCollector` |

其中 JWT 清理指标还有**两套并行实现且名字不同**（`CleanupMetricsService:70` vs `JwtCleanupMetrics:27`）。

### 4.10 Parallel Class Hierarchies（同一抽象写了 2~4 遍）

| 实例 | 证据 |
|---|---|
| 导出器工厂 ×2 | `SpanExporterFactory`(360行, **零引用死代码**) ↔ `TracingExporterConfiguration`(249行, @Bean @Primary 活的) |
| 配置校验器 ×3 | 见 4.3 |
| 版本管理器 ×2 | `VersionManagementService`(345) 与 `ConfigVersionManager`(411) 重叠；还有个**同名**的 `persistence/store/ConfigVersionManager`(88行接口, 无人实现) |
| Tracing Wrapper 四件套 | CircuitBreaker 有 Wrapper(243) **加** Delegate(413) 两套；+ RateLimiter(439) / LoadBalancer(399) / AdapterEnhancer(388) / HealthCheckEnhancer(411) = **2,293 行同构代码** |
| 状态持久化 ×4 | File(211) / H2(175) / Redis(223) / Composite(331) + StoreManager 家族 6 个 = **10 层抽象**；且 **`H2StatePersistenceServiceImpl` 按数据库方言命名而非用途命名**（坏味道） |
| 脱敏引擎 ×2 | Default / Optimized，后者 `matchIfMissing=true` 是默认 → A/B 实现未收敛 |
| 迁移脚本 ×2 | V3 两份文件**逐字相同**，只有注释长度不同 |

### 4.11 ✅ 这一层做对的

- **Flyway 按方言自动选址**（`FlywayDialectEnvironmentPostProcessor`）—— 按 JDBC URL 而非 profile 判定，不用 profile 的理由写在类注释 `:14-19`。
- **V1__baseline.sql 由 Hibernate 导出**，两侧列集合 100% 一致；差异只在物理类型（json vs jsonb、enum vs varchar+check）。
- **`issue #190` 裁决**：JSON 列**故意不写 `columnDefinition`**，靠 `@JdbcTypeCode(SqlTypes.JSON)` 让方言选类型；并记录了一次真实的方言事故。
- **`AsyncMetricsProcessor`** 有界队列 + droppedCount，背压正确。
- **`MetricsCircuitBreaker`** 保护指标处理链路本身，注释写清了作用域（MonitoringController:408-412）。
- **`ConfigBindingSweepTest` / `TracingConfigBindingGuardTest`**：用 `NoUnboundElementsBindHandler` 把配置树绑一遍，任何"配了没人读"的键让测试变红 —— 这是被 `#215`（33 个死键）教训出来后补的，方向正确（只是目前是审计模式不是 fail-fast）。
- **`config_audit_log` / `RbacEndpointCoverageChecker` 的 fail-closed 默认**。

---

## 5. 领域四：架构 / 依赖 / 测试 / 工程基建

### 5.1 循环依赖的根因不是"不会分层"，而是"用 `@Lazy` 和 setter 注入把环压住"

**实证**：`grep -rn "避免循环依赖" src/main/java` → **34 处自白**。典型手段：

```java
// ServiceRequestHandler.java:112-118 区域 —— 6 处 @Autowired
// ConfigurationService.java:163,173,184 —— 3 个 setter 注入，注释写明"避免循环依赖"
// BaseAdapter.java:96-108 —— ApplicationContextProvider.getBean(...)  Service Locator 反模式
```

代价：① 装配顺序隐式化；② `required=false` 让缺失依赖变成**运行时 NPE** 而非启动失败；③ 单元测试必须全 mock（直接导致 §5.3 的问题）。

**实测的 9 组成环对**（跨包 import 计数）：
`router↔monitor(58/20)`、`router↔persistence(15/22)`、`router↔config(25/32)`、`common↔monitor(6/40)`、`common↔auth(1/151)`、`auth↔monitor(5/17)`、`auth↔persistence(47/4)`、`persistence↔config(3/50)`、`config↔auth(3/3)`

**最便宜的解环点**（改动行数）：`common/dto/SecurityAuditQueryResponse.java:4`（1 行）、`persistence/store/ConfigVersionManager.java:3-4`（2 行）—— 改完断 3 个环。

### 5.2 适合六边形（端口与适配器），不适合 DDD 四层

理由：**JAiRouter 的复杂度 100% 在集成侧**。真正的护城河是那几百行手工协议互译（`VllmResponseTransformer:46-171`、`ClaudeAdapter:114-263`、`GeminiAdapter:116-225`）—— DDD 的 `infrastructure` 层语义会把它们误标为"不重要"。

推荐目标结构（见 §8），其中 `core/translate` 是**纯函数、零 IO、无 Spring**，可以用 golden-file 契约测试锁死。

### 5.3 ⚠️ 测试结构是"看起来有 4266 个测试，实则几乎没有端到端验证"

| 指标 | 值 |
|---|---|
| `@SpringBootTest` | **4 个** |
| `*IntegrationTest.java` | 17 个，**其中含 `@SpringBootTest` 的：0**（全是 mock） |
| `@MockBean` | **0**（没有温馨提示：Framework 7 才推 `@MockitoBean`） |
| Mockito 文件 | 213 / 426；`Strictness.LENIENT` 68 个（宽容即失去断言价值） |
| `StepVerifier` 文件 | 84；但 **`withVirtualTime` / `thenAwait`：0**（没用虚拟时间，超时靠 mock） |
| `setAccessible(true)` | **106 处 / 35 文件**（在测 private 方法 = 测结构而非行为） |
| Testcontainers | **0** |
| `@EnabledIfEnvironmentVariable` | 6 个文件 —— **全部默认跳过，永 远 不 执 行**（这些才是真正需要 Redis/PG 的测试） |

**结论**：真正的端到端验证能力 ≈ 0。**这直接决定了 Jackson 3 迁移能不能做**（见 §7）。

### 5.4 checkstyle 实际失效（186 行 suppressions 里约 70% 是全局一刀切）

先注意：`checkstyle.xml:8` `severity=warning` —— **Checkstyle 全部规则都是 warning，本来就不会让构建失败**。suppressions 的真实作用是"让 warning 列表干净"。

| 被全局 `.*\.java` 抑制 | 影响 |
|---|---|
| `DesignForExtension`（另加 30+ 条后缀通配，含 `.*Impl`/`.*Manager`/`.*Config`） | 🔴 全 codebase 失去继承安全网；且主要是为了 CGLIB —— 用 `@Configuration(proxyBeanMethods=false)` 就能解决，不必压制 |
| `VisibilityModifier` | 🔴 导致 `MonitoringController:598-606` 那种 7 个 public 可变字段的"响应对象" |
| `NeedBraces` / `MissingSwitchDefault` | 🟠 |
| `FileLength`（阈值 2000，本项目最大 606 → **本就不触发，纯噪声**） | 🟠 |
| 命名类规则（MemberName/MethodName/LocalVariableName...） | 🟡 掩盖了 `ModelRouterProperties:391` 的 `covertTo()` 拼写错误 |
| `ConstantName`（为了 Lombok `log`） | 🟡 可用 `format` 精确解决 |

`qodana.yaml` 全默认、`failureConditions` 与 `testCoverageThresholds` 全注释 → 门禁未启用。JaCoCo 规则 `BUNDLE/COMPLEXITY/COVEREDRATIO ≥ 0.60` **未绑定 phase**，实际不跑。

### 5.5 工程基建缺口

| 缺口 | 面试可见性 |
|---|---|
| **无 CI**（`.github` 不存在） | 🔴 高。面试官打开 GitHub 第一眼就是没有 Actions 绿标 |
| `.mvn/` 缺失 → `./mvnw` 不可用（README 却写着用它） | 🟠 中 |
| 无 `.gitignore` | 🟠 中 |
| deploy/k8s 无 HPA（有 PDB 有 migration-job） | 🟡 低~中 |
| 无 Testcontainers | 🟠 中 |

---

## 6. 关于"过度工程"的可视化账 V2

| 领域 | 现状 | 建议目标 | 削减 |
|---|---|---|---|
| 配置 | 94 文件 / 15,423 行 + 28 个 yml | ~35 文件 / ~5,000 行 + 4 个 yml | **−68%** |
| 监控 | 193 文件 / 39,220 行 | ~60 文件 / ~12,000 行 | **−69%** |
| 持久化 | 67 文件 / 10,433 行 + 20 表 45 索引 | ~40 文件 / ~6,000 行 + 15 表 ~20 索引 | **−42%** |
| 合计 | **354 文件 / 65,076 行** | **~135 文件 / ~23,000 行** | **−65%** |

削减的不是功能，而是：同一抽象写了 2~4 遍的副本 / 建了没人用的 / 写了没人读的 / 靠约定维持却无机器校验的。

**建议直接砍掉（面试可辩护）**：
`exception_stats_hourly` 整表、`config_change_audit_log` 整表+4 文件、`version/strategy/` 整包、`VersionManagementService`(345)、`persistence/store/ConfigVersionManager` 接口、`HttpConfigPushService` 推送分支、`MetricsBatchReporter`(552)、`SpanExporterFactory`(360)、`ExporterHealthChecker.TestSpanData`(90)、`MetricsManagementController`(529) 或迁出 `/actuator/*`、`CircuitBreakerTracingWrapper`(243) 二选一、`ModelCallStatsRepository`(460) 改名合并、`StoreManager` 家族收敛到 1 接口 3 实现。

---

## 7. Boot 4 / Jackson 3 升级影响面（**这是决定"能不能重写"的关键依据**）

> 说明：这份数据在"就地升级旧项目"的语境下得出的结论是"不能全量迁移"。
> **但我们已经选择"新项目实施全量重写、旧项目降为 legacy 参考"，所以这些数据的作用变了**——它们告诉我们**哪些坑必须在新版本一开始就绕开**，而不是告诉我们别动手。

### 7.1 Jackson 2 → 3 影响面

| 类别 | 主代码 | 测试 |
|---|---|---|
| `import com.fasterxml.jackson` 文件 | **118** / 819 (14.4%) | **60** / 426 |
| 自建 `new ObjectMapper()` | 10 | **66** |
| `catch (JsonProcessingException)` | 38 | 0 |
| `getOriginalMessage()` | 5 | 0 |
| `ObjectNode/ArrayNode` 手工构建 | **15 / 8 文件** | 少量 |
| `TypeReference` | 14 | — |
| `@JsonSerialize`/`@JsonDeserialize` 需核对 | 59 处 | — |

**最危险的 C 类静默行为变更**（不报错、直接改契约）：
- **日期序列化从 timestamp 变 ISO-8601**
- `FIX_FIELD_NAME_UPPER_CASE_PREFIX` 默认 true → 大量 Lombok `@Data` 的首字母大写缩写字段（`getTPM()`/`getRPM()`/`getTTFTokens()`，**AI 网关高频词**）JSON key 静默改名 → `ModelRouterProperties` 一个字段改名 = 某项配置静默失效
- 属性默认排序变化 → 所有 golden-file 断言爆炸
- 构造函数推断变化（`databind#5318`）+ Lombok `@Builder`+`@Data` 组合 → 反序列化可能选错构造器

### 7.2 Boot 4 硬断点清单

| ID | 断点 | 位置 | 类型 |
|---|---|---|---|
| B-1 | `EnvironmentPostProcessor` 包迁移 + `spring.factories` 不再支持 | `META-INF/spring.factories` + `FlywayDialectEnvironmentPostProcessor` | 编译错 + **静默失效**（Flyway 选错方言 = 生产故障） |
| B-2 | `WebServerFactoryCustomizer` 包 relocation | `TracingSecurityConfiguration:42` | 编译错（建议直接删这个空 Bean） |
| **B-3** | **`AuthorizationManager#check` 被移除 → `authorize`** | `PermissionAuthorizationManager:123-125` | **编译错 + 整个 RBAC 测试要改名** |
| B-4b | **optional 依赖不再进 uber jar** | `pom:285-287`(redis-reactive)、`pom:312-314`(lombok) | **运行时 ClassNotFound**，必须真实多副本冒烟 |
| B-4c | Hibernate ORM 7 | 20 Entity + 20 Repository | 编译错 + 行为变更 |
| — | **springdoc 2.8.13 → 必须 v3** | `pom:253` | **确定的硬不兼容** |
| — | jjwt 0.12.3 内部持有 Jackson 2 | `pom:277` | 需 Jackson 2/3 双 classpath 共存 |
| — | logstash-logback-encoder 7.4 | `pom:380` | 结构化日志可能退化成非 JSON |
| ✅ | **Java 17 基线保留** | `pom:23` | **不受影响**（网上流传的"必须 Java 21"是错的） |

---

## 8. 对新重写项目的设计建议

### 8.1 目标模块结构

```
io.github.airouter/
├── core/                        ← 无框架依赖，纯 Java + 少量 jackson-annotations
│   ├── model/                   UnifiedRequest / UnifiedResponse / StreamEvent(sealed)
│   ├── translate/               【护城河】纯函数协议互译，golden-file 契约测试全覆盖
│   └── port/                    所有对外依赖的接口（不下沉实现）
├── routing/                     决策：RuleEngine / InstanceFilter / LoadBalancer（零 IO，100% 可单测）
├── traffic/                     RateLimiter / CircuitBreaker / HealthRegistry / CacheStore
├── transport/                   DownstreamClient（唯一出站口：连接池 + codecs + SSRF）+ StreamingPipeline
├── dispatch/                    RequestDispatcher + OutcomeObserver（唯一的成功/失败/取消观测点）
├── faces/                       OpenAiFace / AnthropicFace / ConsoleFace（只做 encode/decode）
├── agent/                       【Spring AI 2.0.1】ChatClient 装配 + Advisor 链 + Tool + Subagent
├── ops-tools/                   Ops Agent 的工具集（数据源 = 本网关自己的可观测数据）
├── admin-api/                   WebFlux REST + SSE
└── app/                         启动装配
```

### 8.2 Spring AI 的边界（**这一条会在面试被追问，必须想清楚**）

> 评审 agent 的一个结论值得单独拎出来：**"Spring AI 不应替换 网关的 wire-format 翻译层"** —— 它是对的，但它的前提是"在旧项目里就地引入"。

正确 framing：

| 层 | 是否用 Spring AI | 理由 |
|---|---|---|
| **协议翻译 / 代理路由** | ❌ **不用** | Spring AI 是 application 侧抽象（帮你看 from 某个厂商），**不帮你看 to 某个厂商**。而"to OpenAI / to Anthropic 的 wire format"恰恰是网关的全部价值。它也不做"容忍上游脏数据"这类网关必须做的事 |
| **Agent 层** | ✅ **用** | `ChatClient` + `Advisor` 链 + `@Tool` + `SessionMemoryAdvisor` + Subagent 编排 —— 这就是 application 侧场景，正是 Spring AI 的主场 |
| **两者之间的桥** | ✅ 用 OpenAI-compatible starter | Agent 侧通过一个 `spring-ai-starter-model-openai` 指向**自己网关的 `/v1`**（注意必须带 `/v1`）——形成"自己的 Agent 调用自己的网关"的闭环，这个设计讲出来很有说服力 |

一句话：
> 评估过用 Spring AI 替换 adapter 层，结论是不做 —— 它是 application 侧抽象，我们是 gateway 侧 translation，重叠但方向相反。我们在 Agent 层用 Spring AI，在翻译层保留纯函数实现并用 golden-file 契约测试锁死。

### 8.3 三条可在面试拿出来讲的"比原版更好"的验收指标

| # | 指标 | 原版缺陷 | 新版本验收方式 |
|---|---|---|---|
| 1 | **流式失败必须驱动熔断器** | P0-1：`StreamingRequestProcessor:412-414` → `BaseAdapter:192` → `ModelServiceRegistry:466` 恒 success | 集成测试：下游 500 在首块之后 → N 次后 CB OPEN |
| 2 | **一次 pipeline 只解析一次 JSON** | Anthropic 路径 4 次 `readTree` + 2 次 `writeValueAsString` | 测试期 `ChunkParseCountMeter` 断言 `assertParsedPerChunk() == 1` |
| 3 | **协议扩展 N×M 而非 N×N** | `transformStreamChunk` 19 处，其中 2 处是 `return chunk`（未实现） | 删掉 `BaseAdapter.transformStreamChunk` 这条继承链，看是否还有 provider 需要重写它 |

### 8.4 必须一开始就写进新版的三件事（不能事后补）

1. **契约测试（golden-file）**：对每种 provider × face 的组合，用真实的上游 SSE 样本做快照比对。**这是未来任何升级的安全网。**
2. **Testcontainers**：Postgres（已有 pgvector 可用）+ Redis + 一个 mock LLM 服务，让集成测试默认可执行（不要再用 `@EnabledIfEnvironmentVariable` 把它们永久关掉）。
3. **CI**：checkstyle(error 级) + spotbugs + test + jacoco 四条 gate 必须 fail-fast，`*.md` lint 可选。

---

## 9. 面试官追问速答清单（基于代码证据）

| 追问 | 正确答案（含证据） |
|---|---|
| "reserve→settle 是两阶段提交吗？" | 不是。没有 reservation record / outbox / reaper，崩溃即泄漏。作者自己在 `QuotaSettlement:14-15` 承认跨窗口是"已知偏差" |
| "会超卖吗？" | 会，两种：非分布式多实例 = N×限额；分布式 + Redis 故障 + 默认 failOpen = 限额整体失效（`QuotaLedgerService:437-442`） |
| "流式脱敏了吗？" | 代码脱了，语义没脱。逐帧边界 = 匹配边界，手机号/身份证 100% 漏；反差证据是 DB 里那份是干净的 |
| "中文敏感词能用吗？" | 不能。`\b` 基于 `\w=[a-zA-Z_0-9]`，实测 `\b\Q机密\E\b` 对中文返回 false |
| "权限有继承吗？" | 没有。扁平表 + 精确字符串匹配；63 个码里 6 个是死码（可被授予但永不生效） |
| "登出立即生效吗？" | 不。本地缓存只写本实例；Redis 挂时黑名单 fail-open（Javadoc 自己承认） |
| "流式会触发熔断吗？" | 不会。三层证据：`StreamingRequestProcessor:412` → `BaseAdapter:192` → `ModelServiceRegistry:466` |
| "4000+ 测试真的有效吗？" | 4266 个 @Test，但 `@SpringBootTest` 只有 4 个；17 个 `*IntegrationTest` 全是 mock；6 个需要真基础设施的被环境变量永久关掉 |
| "为什么不用 Spring AI 重写整个网关？" | 见 §8.2 —— application 侧抽象 vs gateway 侧 translation，方向相反；我们在 Agent 层用它，在翻译层不用 |
| "选六边形还是 DDD？" | 六边形。复杂度 100% 在集成侧，DDD 的 infrastructure 语义会把护城河（协议互译）误标为"不重要" |
| "从零重写怎么保证不重蹈覆辙？" | 三件事先做：golden-file 契约测试 / Testcontainers 默认可跑 / CI 四条 gate fail-fast。原项目的根因不是代码量，是**没有验证能力就开始改** |
