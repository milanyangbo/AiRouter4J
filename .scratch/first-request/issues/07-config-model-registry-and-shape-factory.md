# 07: 三级配置模型、形态工厂与注册表

**What to build:** `Service → ModelInstance` 三级配置模型换代掉「一个扁平端点」；
每实例一个 Provider（ADR-0009 第 4 节）；两者之间的 creators 登记在**形态工厂**里，
注册表是「配置 → 运行时对象」的**唯一**持有者（ADR-0010 第 2 节）——
**持有，不是创建**：造 wire 与 Provider 的动作在 `config` 的装配器里，
让注册表去调工厂等于让它认识形态（2026-10-27 复核改掉了原来那句「由注册表创建」）。

形态已经是实例的属性而不是全局设置：同一 Service 下两个实例可以有不同的形态、不同的地址。
在没有第二种形态之前，「两个不同形态共存」这条只能做到**结构层面**（工厂按形态挑构造器、
每实例一个 Provider），端到端那条要等 `ollama` 形态（阶段 12）—— 本票把它记进 Known gaps。

**Blocked by:** 06

**Status:** done（`baseline-6`）

## Acceptance criteria

- [x] `airouter.services.<service>.instances[*]` 三级模型落地：
      `InstanceDefinition`（id / 形态 / 厂商 / Endpoint / 权重 / 启用标志 / 标签）
      + `ServiceDefinition`（实例清单）+ `ServicesProperties`（服务清单与默认服务）
- [x] 实例集合是**列表 + 显式 id**，不是 Map ——
      用 Map 时重复 id 在语法上不可能出现（第二个静默覆盖第一个），
      而 ADR-0010 第 4 节要求「同一 Service 下实例 id 重复」**起不来**。
      列表才能让这一条真有一条测试
- [x] 启动期校验四件全在这无处躲：形态要求的凭证缺失（指名具体实例）、
      地址不符合形态后缀、同一 Service 下实例 id 重复、默认服务不存在
- [x] **形态工厂**登记构造器（形态 → 构造器），按实例的形态创建 Provider；
      调用路径上没有 `switch (shape)` / `if (shape == ...)`
- [x] **每实例一个 Provider**：两个实例各有一个 Provider 与一个 `OutboundWire`；
      `core` 的端口签名与 `faces` 一行未动（ADR-0009 第 4 节的 B 路）
- [x] 注册表是「配置 → 运行时对象」的**唯一**持有者：没有第二处缓存服务/实例的映射
- [x] 只有 `providers` 能构造 `Outcome` 这条不受影响（R4 / R9 仍绿）
- [x] 端到端测试切到新配置后流式与非流式照旧跑通
- [x] `local` 档位仍是「一个变量不设也能起来」

## Known gaps（本票不做，记下来免得假装做了）

- 两个**不同形态**的实例的端到端：`ollama` 原生形态属阶段 12
- **「引用了未声明的厂商」这条启动期校验尚未实现**（ADR-0010 第 4 节第 4 条）：
  厂商声明的清单本身还没落地（ADR-0009 第 2 节的声明式厂商 + 阶段 6 的凭据管理），
  校验一份不存在的清单只能恒过。`vendor` 现在只被搬运到 `RegisteredInstance`，不解释
- 版本快照与回滚、热重载不重建上下文 → ADR-0010 第 5/6 节；**票还没建**，
  建了之后把票号写在这里 —— 不写一个不存在的号
- 实例上挂熔断状态 / 延迟样本 / 健康登记 → 它们各自的阶段，
  注册表只负责「有地方挂」而不是现在就把它们造出来
- 多实例之间的选择（权重怎么被用）→ 阶段 4（ADR-0011）
- 多实例各有自己的 `base-url` 环境变量：本阶段只有 `AIROUTER_ENDPOINT_*` 一组名字，
  真正的「每实例凭证」要等厂商声明给默认地址（ADR-0009 第 2 节）与阶段 6 的凭据管理

## 落点

| 文件 | 内容 |
|---|---|
| `config/InstanceDefinition` | 实例：id / 形态 / 厂商 / Endpoint / 权重 / 启用 / 标签，五处检查 |
| `config/ServiceDefinition` | 服务：实例清单，**重复 id 判红** |
| `config/ServicesProperties` | `airouter.services.*` + `airouter.default-service`，两者互相校验 |
| `providers/UpstreamProviderFactory` + `ProviderConstructor` + `ProviderInputs` | 形态 → 构造器；构造时自检「有形态没登记」 |
| `providers/UpstreamProvider` | 一个实例的出站能力，**自己同时是两个端口**（见下） |
| `registry/{UpstreamRegistry, ServiceView, RegisteredInstance}` | 唯一持有者 |
| `config/UpstreamConfiguration` | 装配：每实例一个 wire + 一个 Provider |

删掉：`config/EndpointProperties`（它的五个字段被 `InstanceDefinition` 吸收）。

## 实测出来的两件事（都不是猜的）

1. **Spring Boot 绑定**列表元素**时不跨配置来源合并。** 在命令行覆盖
   `instances[0].base-url`，这个元素的其余字段不会被 `application.yml` 补齐 ——
   它几乎全空地到达构造器，表现为「id 缺失」而不是「地址改了」。
   实测：只给 base-url 的那一次报的就是 `实例的 id 不能为空`。
   → 要改其中一个字段，请在**同一个来源**里写全（`application-local.yml` 就是这么做的）；
   `LocalProfileTest` 改成用系统属性（等价于环境变量）覆盖，走的是占位符那条正路。
2. **容器按运行期类型匹配 `@Bean` 候选。** 两个 bean 分别声明 `CompletionPort` 与
   `StreamingCompletionPort`，而实现同一个对象同时实现了两个接口 →
   注入任何一侧都同时匹配两个候选（`NoUniqueBeanDefinitionException`，票 02 踩过一次）。
   → 只声明**一个** bean：`UpstreamProvider` 自己同时实现两个端口（委派给内部那两个），
   于是注入点仍然只看见接口，而候选只有一个。

## 探红（四处，都确认会红）

- 重复 id 不判 → `duplicatedInstanceIdFailsNamingTheId` 红
- 工厂缓存（两个实例共用一个 Provider）→ `eachInstanceGetsItsOwnProvider` 红
- 默认服务不在清单里不判 → `unknownDefaultServiceFailsListingTheCandidates` 红
- 一个服务没有任何启用实例不判 → `serviceWithoutAnyEnabledInstanceFails` 红

## 顺带

SpotBugs 在四个持有类上报 `EI_EXPOSE_REP2`。用探针（一个同时含五种形状的临时类）确认：
**判定标准是「有没有在构造器里把字段封死」** —— `Map.copyOf` / `List.copyOf` / `Set.copyOf`
写进 compact constructor 的那几个类（含只含 String 的 Map、List）一条都不报。
于是四个类各补一行封口；`ProviderInputs` 的那一项属于另一类 —— 存 `OutcomeObserver`
（自定义接口）本身就会报，改成只存 `Consumer<Outcome>`（它需要的本来只是「把结论递出去」）。
**没有加任何抑制。**

## 补记：2026-10-27 code review（`baseline-5...HEAD`）

两轴各跑一份：Standards（是否符合仓库自己写下的标准）与 Spec（是否做了票 / ADR 要的事）。
修掉的记在下面；**新增的测试项记在本票，不另开文件。**

### 已修

1. **`InstanceDefinition` 的 `@param vendor` 是空头承诺**（两轴独立指向同一处）：
   javadoc 写「引用了未声明的厂商在启动期判红（检查在 `ServicesProperties` 那一层）」，
   而 `ServicesProperties` 只有三判、没有厂商校验，`vendor` 全仓无消费者。
   → javadoc 改成明说「本阶段不校验」，指向 ADR-0010 第 4 节那一条与上面的 Known gaps；
   ADR-0010 第 4 节第 4 条同步标注「尚未实现」。
2. **「由注册表按实例的形态创建 Provider」这句话写错了**（本票 What to build + 验收 + ADR-0009 §2(c)）：
   代码一直是 `config/UpstreamConfiguration.register()` 建 wire 与 Provider，注册表只持有。
   → 三处措辞都改成「持有，不是创建」，并写明为什么（注册表调工厂 = 让它认识形态，
   那是「transport 不认识 providers」那条方向约束要挡的同一类事）。
3. **`UpstreamRegistry.service(String)`（Optional 版）全仓无调用者** → 删。
   `describeSupportedShapes()` 原本也只被自己的测试用 → 不删，改成真有消费者：
   `create()` 报「没有对应的构造器」时把它摆出来（与注册表列已注册服务名同一理由）。
4. **`eachInstanceGetsItsOwnProvider` 证明的东西比它的名字少**：两条 wire 由测试自己 new，
   绕开了 `UpstreamConfiguration.register()` → 补 javadoc 写明它证明什么、不证明什么。

### 新增测试项（未做，留给下面这些时候）

- **两个实例各有自己的 `OutboundWire`**：起两个录制器（两个地址即可，不必两个形态），
  端到端断言两个实例各收到自己的请求 —— 把装配点改成共用一条 wire 时这条必须红。
  现在只有 `eachInstanceGetsItsOwnProvider` 在守工厂那一层，装配点那一层是空的。
- **「引用了未声明的厂商」→ 启动失败并指名那个厂商名**：随 ADR-0009 声明式厂商（阶段 6）落地时补。
- **`RegisteredInstance` 上的挂载点（熔断 / 延迟样本 / 健康登记）**：随它们各自的阶段补，
  本票只保证「有地方挂」，没造出任何一个。
- **`UpstreamProviderFactory.create()` 报「没有对应的构造器」时列出已登记的形态**：
  这条分支被构造期自检挡着走不到，等有多形态之后再看要不要为它单独造一条。

### 未采纳（判断类，不动）

- `ServiceDefinition` 与 `ServiceView` 那句重复报错、`ServicesProperties` 与 `UpstreamRegistry`
  重复的 `String.join("、", keySet())`：它们分属配置层与注册表层，抽共用会造出一个谁都不属于的工具类
- `Endpoint`（baseUrl / 凭证 / 超时）被摊平成三个字段：GLOSSARY 里有这个词，
  但配置绑定要的就是扁平字段，包一层会让 `@ConfigurationProperties` 那一侧多一跳
- `UpstreamProvider` 转发两个方法、`UpstreamConfiguration.register()` 读九个字段：
  前者是为了「一个 bean 两个端口」，后者本来就是装配点的本分
- 工厂构造期自检循环恒不触发（现在只有一种形态）：它是为第二种形态准备的，等形态多起来才生效
