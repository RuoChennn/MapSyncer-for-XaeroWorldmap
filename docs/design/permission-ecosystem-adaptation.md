# Minecraft 权限生态调研与 MapSyncer 适配指引

> 范围：MC **1.20.1 → 26.2**，Loader：**Fabric / Forge / NeoForge**  
> 用途：后续实现 MapSyncer 细粒度权限（LuckPerms / FTB Ranks 等）时的对照文档  
> 调研日期：2026-07-21（线上资料：CurseForge / Modrinth / GitHub / Fabric API javadoc）

---

## 1. 先分清三层（适配时最重要）

| 层 | 角色 | 典型产物 | MapSyncer 该怎么做 |
|----|------|----------|-------------------|
| **检查 API（Check API）** | 其它 mod 调「有没有权限」 | Fabric：`fabric-permissions-api` / Fabric API `permission-v1`；Forge/NeoForge：`PermissionAPI` | **必须对接**（写检查代码） |
| **权限提供者（Provider）** | 实现检查 API 的后端 | LuckPerms、Player Roles、FTB Ranks（经桥接） | **不硬依赖**；有则走节点，无则回退 OP |
| **权限管理 UI/命令** | 服主配置组/节点 | LuckPerms `/lp`、FTB Ranks `/ftbranks` | 文档说明推荐安装即可 |
| **原版扩展** | 给原版命令/行为挂节点 | VanillaPermissions | 与 MapSyncer 无关，可忽略 |

**结论：** MapSyncer 应对接 **Check API**，不要直接 compile 依赖 LuckPerms / FTB Ranks JAR。服主装哪个 Provider，检查会自然落到该 Provider。

---

## 2. 版本 × Loader 覆盖一览（MapSyncer 关心的点）

MapSyncer 当前矩阵（见 `.cursor/skills/mc-multi-version/SKILL.md`）：

| MC | Fabric | Forge | NeoForge |
|----|--------|-------|----------|
| 1.20.1 | ✅ | ✅ | — |
| 1.21.1 | ✅ | ✅ | ✅ |
| 1.21.11 | ✅ | ✅ | ✅ |
| 26.1 / 26.2 | ✅ | ❌（本项目不维护） | ✅ |

### 2.1 推荐对接目标（按 Loader）

| Loader | MC 区间 | 推荐 Check API | 主流 Provider |
|--------|---------|----------------|---------------|
| Fabric | 1.20.1 – 1.21.11 | **lucko `fabric-permissions-api`**（可 `include` 进 JAR） | LuckPerms；可选 Player Roles |
| Fabric | 26.1 – 26.2 | **优先 Fabric API `permission-v1`**（官方，Experimental）；可并存/过渡 lucko API | LuckPerms；Player Roles 1.9+ |
| Forge | 1.20.1 – 1.21.11 | **Forge `PermissionAPI`**（`net.minecraftforge.server.permission`） | LuckPerms；1.20.1 还有 FTB Ranks Forge |
| NeoForge | 1.21.1 – 26.2 | **NeoForge `PermissionAPI`**（`net.neoforged.neoforge.server.permission`） | LuckPerms；FTB Ranks NeoForge |

LuckPerms 在 Modrinth/CurseForge 上已提供 **Fabric / Forge / NeoForge** 构建，并声明覆盖至 **26.2**（例：v5.5.57）。Forge 26.x 有 LP 构建，但 MapSyncer 本身不发 Forge 26 包。

---

## 3. Check API 差异对照

### 3.1 Fabric — lucko `fabric-permissions-api`（1.20.1–26.1 仍可用）

- 仓库：[lucko/fabric-permissions-api](https://github.com/lucko/fabric-permissions-api)
- 用法：[USAGE.md](https://github.com/lucko/fabric-permissions-api/blob/master/USAGE.md)
- 形态：独立小库（~12KB），**建议 `include()` 打进 mod**，避免服主漏装
- 节点形态：**字符串** `mapsyncer.sync`（无强制注册）
- 检查入口：`me.lucko.fabric.api.permissions.v0.Permissions`
- 未设置时：可指定 fallback（`boolean` 或 **原版 permission level**）

```java
Permissions.check(source, "mapsyncer.admin", 4);
Permissions.require("mapsyncer.sync"); // 用于 Brigadier .requires(...)
```

**版本矩阵（官方 USAGE，注意按 MC 选 jar）：**

| Minecraft | fabric-permissions-api |
|-----------|------------------------|
| 26.1 | `0.7.0`（26.1 起依赖用 `implementation`，勿用旧式 `modImplementation`） |
| 1.21.11 | `0.6.1` |
| 1.21.9 – 1.21.10 | `0.5.0` |
| 1.21.6 – 1.21.8 | `0.4.0` |
| 1.21.2 – 1.21.5 | `0.3.3` |
| ≤ 1.21.1（含 1.20.1） | `0.3.1`（文档亦列出 `0.3.x` 线；以 USAGE 为准） |

`fabric.mod.json` 声明：`"fabric-permissions-api-v0": "*"`。

Provider 通过 `PermissionCheckEvent` / `OfflinePermissionCheckEvent` 响应；LuckPerms Fabric 即此路径。

### 3.2 Fabric — 官方 Fabric API `permission-v1`（**仅 26.1+**）

- 自 Fabric API **0.149.0+26.1.2** 引入（PR [#5226](https://github.com/FabricMC/fabric-api/pull/5226)）
- 包：`net.fabricmc.fabric.api.permission.v1`（标注 **@Experimental**）
- 节点形态：`Identifier` + 可选 **Codec 类型**（bool / int / string / 自定义）
- 检查入口：`Entity` / `CommandSourceStack` 实现 `PermissionContextOwner`  
  → `checkPermission(Identifier)` / `checkPermission(PermissionNode, default)`
- 支持 **PermissionContext**（位置、自定义 Key），适合保护类/领地类；MapSyncer 同步权限通常不需要
- Provider 注册：`PermissionEvents` 的 request 回调

与 lucko API 的关键差异：

| | lucko `fabric-permissions-api` | Fabric API `permission-v1` |
|--|-------------------------------|----------------------------|
| 覆盖版本 | 1.20.1 – 26.1（持续维护） | **仅 26.1+** |
| 依赖 | 独立 jar / include | 已在 Fabric API 内 |
| 节点 | 点分字符串 | `Identifier`（常写成 `modid:path`） |
| 类型 | 基本 bool + Options 字符串元数据 | Codec 类型化节点 |
| 上下文 | 弱（主体 + 离线 UUID） | 强（位置等 context） |
| 成熟度 | 多年生产默认 | Experimental，生态迁移中 |

**适配建议（MapSyncer）：**  
- G1–G3（≤1.21.11）：只用 lucko API。  
- G4（26.x）：优先官方 `permission-v1`；若需兼容仍只认 lucko 的老 Provider，可双查或做桥（先查官方，未决再查 lucko）。

### 3.3 Forge `PermissionAPI`（1.20.1 / 1.21.x）

- 包：`net.minecraftforge.server.permission.*`
- **必须**在 `PermissionGatherEvent.Nodes` 注册静态 `PermissionNode<T>`
- 查询：`PermissionAPI.getPermission(ServerPlayer, node, contexts...)`
- 类型：`PermissionTypes.BOOLEAN` / `STRING` / `INTEGER` / `COMPONENT` 等
- 默认 handler：按节点上的 default resolver（常绑 OP level）
- LuckPerms Forge：作为 **IPermissionHandler** 替换默认 handler，从而解析已注册节点

```java
public static final PermissionNode<Boolean> SYNC =
    new PermissionNode<>("mapsyncer", "sync", PermissionTypes.BOOLEAN,
        (player, uuid, ctx) -> false); // 或按 OP 回退

@SubscribeEvent
public void onNodes(PermissionGatherEvent.Nodes e) {
    e.addNodes(SYNC /*, ADMIN, SYNC_ALL ... */);
}

boolean ok = PermissionAPI.getPermission(player, SYNC);
```

注意：节点名建议 `modid.path`；**没有**「任意字符串即时检查」的一等 API——未注册就查会抛 `UnregisteredPermissionException`。

### 3.4 NeoForge `PermissionAPI`（1.21.1 – 26.x）

- 包：`net.neoforged.neoforge.server.permission.*`
- **模型与 Forge 基本同构**（`PermissionNode` + `PermissionGatherEvent.Nodes` + `PermissionAPI.getPermission`）
- 包名/事件总线命名空间不同；胶水层需分 `ForgePlatform` / `NeoForgePlatform` 两套，**不要**共用一个 `net.minecraftforge` 引用到 NeoForge 模块
- LuckPerms NeoForge、FTB Ranks NeoForge 均走此 API

Forge vs NeoForge（适配视角）：

| | Forge | NeoForge |
|--|-------|----------|
| API 形状 | 几乎相同 | 几乎相同 |
| 包前缀 | `net.minecraftforge...` | `net.neoforged.neoforge...` |
| MapSyncer 26.x | 无 Forge 产物 | 有 NeoForge |
| 1.21.1 / 1.21.11 | 两套都要接 | 同上 |

---

## 4. Provider / 管理 mod 差异

### 4.1 LuckPerms（首选跨 Loader 方案）

| 项 | 说明 |
|----|------|
| 站点 | [luckperms.net](https://luckperms.net) / [Modrinth](https://modrinth.com/plugin/luckperms) / CurseForge |
| Loader | Fabric、Forge、NeoForge（另有 Bukkit 等，与本项目无关） |
| MC | 声明覆盖 **1.20.x / 1.21.x / 26.1.x / 26.2** |
| 对 Fabric | 实现 lucko `fabric-permissions-api`；26.x 亦对接 Fabric 新 Permission API（随 LP 版本演进，以发行说明为准） |
| 对 Forge/NeoForge | 实现 Loader `IPermissionHandler` |
| MapSyncer 依赖策略 | **runtime optional**；compile 只依赖 Check API |
| 服主配置 | `/lp user|group permission set mapsyncer.sync true` |

**适配价值：最高。** 一条节点字符串约定即可覆盖绝大多数服。

### 4.2 FTB Ranks

| 项 | 说明 |
|----|------|
| 文档 | [FTB Ranks docs](https://docs.feed-the-beast.com/mod-docs/next/mods/suite/Ranks/) |
| Loader | Fabric + NeoForge 为主；**1.20.1 仍有 Forge**；1.21+ 官方说明常写「Forge 视 Architectury 再定」 |
| MC | CurseForge 可见 **1.20.1、1.21.1、1.21.11、26.1.x** 等；**26.2 需发版时再核** |
| 节点 | 自有 rank/node（bool/number/string）；Forge/NeoForge 上另有 **PermissionAPI wrapper** |
| 对 Fabric | 主要通过自身 API / 与权限生态协作；**不要**假设它等于 lucko 字符串 API |
| MapSyncer 策略 | **不优先直接依赖**；若节点已注册进 Forge/NeoForge PermissionAPI，经 `PermissionAPI` 检查即可间接受益 |

### 4.3 Player Roles（Fabric only）

| 项 | 说明 |
|----|------|
| 仓库 | [NucleoidMC/player-roles](https://github.com/NucleoidMC/player-roles) |
| 能力 | JSON 角色、`permission_level`、命令覆盖；**1.9.0+26.1.2** 起支持 Fabric Permission API |
| 定位 | 轻量权限组；可与 VanillaPermissions 搭配 |
| MapSyncer | 无需特判；走 Fabric Check API 即可 |

### 4.4 VanillaPermissions（Fabric only）

| 项 | 说明 |
|----|------|
| 仓库 | [DrexHD/VanillaPermissions](https://github.com/DrexHD/VanillaPermissions) |
| 作用 | 给**原版**命令/绕过规则挂 `minecraft.*` 节点 |
| MapSyncer | **不对接**；仅作生态背景 |

---

## 5. 差异总表（给实现用）

| 维度 | Fabric ≤1.21.11 | Fabric 26.x | Forge 1.20.1–1.21.11 | NeoForge 1.21.1–26.2 |
|------|-----------------|-------------|----------------------|----------------------|
| Check API | lucko FPA | FAPI `permission-v1`（+ 可选 lucko） | Forge PermissionAPI | NeoForge PermissionAPI |
| 节点形式 | `String` | `Identifier` / `PermissionNode` | 静态 `PermissionNode` + 注册 | 同左（包名不同） |
| 未注册行为 | fallback level/bool | defaultValue / PermissionLevel | 异常或 default resolver | 同左 |
| 离线查询 | `Permissions.check(UUID,…)` → Future | Offline 相关事件/上下文 | `getOfflinePermission` | 同左 |
| 命令 `.requires` | `Permissions.require(...)` | `PermissionPredicates.require(...)` | 手写：`PermissionAPI.getPermission` | 同左 |
| 推荐 Provider | LuckPerms | LuckPerms | LuckPerms | LuckPerms |
| 次选 | Player Roles | Player Roles / FTB Ranks | FTB Ranks（1.20.1） | FTB Ranks |

---

## 6. MapSyncer 适配蓝图（对齐现有架构）

### 6.1 放置位置（遵守 G1–G4）

| 内容 | 位置 |
|------|------|
| 节点常量、策略门控、业务判断 | `libs/common`（如 `MapSyncerPermissions`、`PermissionGates`） |
| `PermissionChecker` 接口 | `libs/platform-api` |
| Fabric lucko / FAPI 实现 | `mc-*/fabric` 胶水（26.x 用 FAPI；更早用 lucko） |
| Forge PermissionAPI 注册 + 查询 | `mc-1.20.1/forge`、`mc-1.21.1/forge`、`mc-1.21.11/forge` |
| NeoForge PermissionAPI | `mc-*/neoforge` |
| 无 Provider 时回退 | 现有 `CommandPermissionHelper`（OP / LEVEL_GAMEMASTERS） |

**禁止**再往已删除的 `mc-*/shared` 复制四份逻辑。

### 6.2 建议节点约定（与旧 `feature/policy-facades` 一致即可）

```
mapsyncer.admin          # generate / status / incremental / reloadconfig 等管理命令
mapsyncer.sync           # 允许发起同步（含网络 SyncRequest）
mapsyncer.sync.all       # sync all / 多维度全量
mapsyncer.sync.dimension # 单维度同步（可选，可与 sync 合并以降低复杂度）
```

Forge/NeoForge 注册时：`new PermissionNode<>("mapsyncer", "sync", BOOLEAN, ...)` → 全名通常为 `mapsyncer.sync`。  
Fabric 26.x Identifier：建议 `mapsyncer:sync`（实现层把 `.` ↔ `:` 做一次规范化，避免服主文档两套写法）。

### 6.3 检查伪代码（跨平台统一语义）

```text
canAdmin(source):
  if PermissionChecker.has(source, ADMIN): return true
  else return vanillaOpFallback(source)   // CommandPermissionHelper

canSync(player, requestMeta):
  if !has(SYNC): deny
  if isSyncAll(requestMeta) && !has(SYNC_ALL): deny
  else allow
```

### 6.4 与「登录门控」分离

权限（能不能 sync）≠ 登录就绪（EasyAuth 等是否允许自动 sync）。  
登录门控继续用独立 `AuthReadiness` / `SyncAllowedPayload`；不要塞进 PermissionAPI。

### 6.5 依赖与可选性

| 依赖 | 策略 |
|------|------|
| lucko fabric-permissions-api | Fabric ≤1.21.11：`include`；26.x 视是否双栈 |
| Fabric API permission-v1 | 已随 Fabric API，无额外 jar |
| Forge/NeoForge PermissionAPI | Loader 自带 |
| LuckPerms / FTB Ranks | **绝不** `implementation` 硬依赖 |

---

## 7. 坑与注意事项

1. **Forge/NeoForge 必须先 register nodes**，再 `getPermission`；Fabric lucko 无此限制。  
2. **节点名无隐式父子继承**：`mapsyncer.sync` 不会自动包含 `mapsyncer.sync.all`——由 Provider（如 LuckPerms）配置继承。  
3. **Fabric 26.x 双 API 并存期**：新 mod 用 FAPI；老 Provider 可能仍只填 lucko 事件——适配层最好有明确 fallback 链。  
4. **FTB Ranks 不要当跨 Loader 唯一方案**：Forge 在 1.21+ 支持不稳定；优先 LuckPerms。  
5. **CommandSource vs ServerPlayer**：Forge API 主路径是 `ServerPlayer`；控制台/命令方块需单独策略（通常放行 admin 或拒绝 sync）。  
6. **MapSyncer 无 Forge 26**：文档/测试矩阵不要假设 Forge 26 权限路径。  
7. **旧分支 `feature/policy-facades`**：设计可参考，代码基于重构前 `shared`，勿直接 merge（见此前评估）。

---

## 8. 参考链接

| 资源 | URL |
|------|-----|
| lucko fabric-permissions-api USAGE | https://github.com/lucko/fabric-permissions-api/blob/master/USAGE.md |
| Fabric API permission-v1 javadoc (26.2) | https://maven.fabricmc.net/docs/fabric-api-0.149.1+26.2/net/fabricmc/fabric/api/permission/v1/package-summary.html |
| Fabric Permission API PR | https://github.com/FabricMC/fabric-api/pull/5226 |
| Forge PermissionAPI (1.20.1) | https://lexxie.dev/forge/1.20.1/net/minecraftforge/server/permission/PermissionAPI.html |
| NeoForge PermissionAPI (1.21.1) | https://lexxie.dev/neoforge/1.21.1/net/neoforged/neoforge/server/permission/PermissionAPI.html |
| LuckPerms | https://luckperms.net / https://modrinth.com/plugin/luckperms |
| FTB Ranks | https://docs.feed-the-beast.com/mod-docs/next/mods/suite/Ranks/ |
| VanillaPermissions | https://github.com/DrexHD/VanillaPermissions |
| Player Roles | https://github.com/NucleoidMC/player-roles |

---

## 9. 一句话决策

> **业务只认字符串节点 + `PermissionChecker`；Fabric ≤1.21.11 用 lucko FPA，Fabric 26.x 用官方 permission-v1，Forge/NeoForge 用各自 PermissionAPI 注册同名节点；Provider 只推荐 LuckPerms，无 Provider 时回退 OP。**
