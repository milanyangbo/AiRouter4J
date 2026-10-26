# ADR-0018：认证、API Key 与 RBAC

- 状态：已接受
- 日期：2026-10-26
- 相关：[0010](./0010-configuration-model-and-runtime-registry.md)（租户是限流/配额维度）、[0019](./0019-admin-plane-api-and-console.md)

能力面：**第 10 类（认证与 RBAC）**

## 1. legacy 的事实（已核实）

- 用 Spring Security，管理面接口上挂 `@PreAuthorize("hasRole('ADMIN')")`（`ApiKeyManagementController` 一个文件里十余处）
- `auth/security/` 下有 `authentication/`、`cache/`（`ApiKeyCache` + Redis/InMemory 两个实现）、`quota/`、`monitoring/` 四块
- 另有 `ConfigurationEncryptionService`（配置加密）与 `SecurityConfigurationBackupService`

## 2. 决定

**(a) API Key**
- 库中**只存哈希**，明文只在创建时返回一次
- 支持启用 / 禁用与过期
- 校验位置在**入口统一处**（选择链第 ① 步），不在各业务分支 —— 散落的校验必有漏掉的一条路径
- 缓存用 `ApiKeyCache` 的两级形态（本地 + Redis），**可搬**

**(b) 租户**
由 client 标识（API Key 或 JWT 声明）退化而来，是限流、配额、缓存键的隔离维度。
一个租户可以持有多把 Key；**配额与缓存按租户算而不是按 key 算** —— 否则多签一把 key 就等于绕开限额。

**(c) RBAC 先做精简版**
固定角色（如 `ADMIN` / `VIEWER`），权限到**接口级**。默认拒绝（fail-closed）。
**全集对齐推迟到控制台阶段**（[0019](./0019-admin-plane-api-and-console.md)）—— 在没人用它的阶段铺一整套角色粒度，
只会得到一个没人验证过的权限矩阵。

**(d) 凭据不透传下游**
出网用的是**实例自己的**凭证，不是调用方带来的那把。
> 透传意味着「谁在用这个实例」对上游可见，且调用方的 Key 泄漏面被放大到每一家厂商。

**(e) 失败即拒绝**
认证组件自身故障（缓存不可用、库查不到）时**拒绝**，不 fail-open。

## 3. 裁决

| legacy 的东西 | 裁决 |
|---|---|
| Spring Security + `@PreAuthorize` | **搬**（这是标准做法，不值得自研） |
| `ApiKeyCache` 两级缓存 | **搬**（改：缓存穿透时的行为要定义清楚，不能「查不到就当无效」而不记指标） |
| `ConfigurationEncryptionService` | **搬**（配置文件里的敏感项加密，与 [0015](./0015-call-history-and-record-governance.md) 共用密钥管） |
| `SecurityConfigurationBackupService` | **改**：备份并入配置版本（[0010](./0010-configuration-model-and-runtime-registry.md) 第 6 节），不单列一套 |
| 完整角色/权限矩阵 | **延后**到控制台阶段对齐 |

## 4. 验收

- 无效 / 过期 / 被禁用的 Key → 拒绝，且不产生出网调用
- 同一租户的两把 Key **共享**配额与限流额度（断言：换 key 不重置额度）
- 跨租户的缓存键不串（同请求、不同租户 → 不命中对方的缓存）
- 管理面接口未带 ADMIN → 拒绝；权限组件异常时也是拒绝
- 出网请求里的 Authorization 头是**实例自己的**凭证，不含调用方 Key（断言）
