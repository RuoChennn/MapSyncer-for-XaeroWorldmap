---
name: mc-multi-version
description: >-
  MapSyncer multi-MC-version layout (G1–G4 anchor libs + Fabric/Forge/NeoForge glue).
  Use when adding features, fixing bugs across MC versions, moving shared code,
  editing libs/mc-* or mc-*/ glue, or restructuring version modules.
---

# MapSyncer 多版本架构（G1–G4 + 锚点 + 胶水）

## 目录模型

```
libs/common/       # 全版本业务逻辑（优先改这里）
libs/mc-1.20/      # G1 锚点 1.20.1
libs/mc-1.21/      # G2 锚点 1.21.1
libs/mc-1.21.11/   # G3 锚点 1.21.11
libs/mc-26/        # G4 锚点 26.1
mc-{精确版本}/{fabric|forge|neoforge}/   # 胶水层（Loader + 网络注册 + Platform 实现）
```

**读法：** `libs/mc-1.21` = 按 1.21.1 API 写的 MC 相关源码；`mc-1.21.1/fabric` = 编译 1.21.1 Fabric JAR。

## G1–G4 对照（改代码前先查）

| 组 | 目录 | 锚点 | 覆盖 MC | 不含 | JDK | API 要点 |
|----|------|------|---------|------|-----|----------|
| G1 | `libs/mc-1.20` | 1.20.1 | 1.20.1（可扩 1.20.2–6） | 1.21+ | 17 | `ResourceLocation` · `key.location()` |
| G2 | `libs/mc-1.21` | 1.21.1 | 1.21 · 1.21.1 · 1.21.4–1.21.10 | 1.21.2/3 · 1.21.11 · 26.x | 21 | `ResourceLocation` · Configuration 网络 |
| G3 | `libs/mc-1.21.11` | 1.21.11 | 仅 1.21.11 | 其它 | 21 | `Identifier` · `key.identifier()` |
| G4 | `libs/mc-26` | 26.1 | 26.1 · 26.1.1 · 26.1.2 | 1.x · 26.2+ | 25 | 去混淆 · Loom no-remap · 客户端消息 API 变 |
| G4′ | `libs/mc-26` | 26.2 | 26.2 | 1.x · 26.1.x | 25 | 协议 776；共用 G4 源码 |

**跳过：** 1.21.2 · 1.21.3（物品/协议 API 断裂，不单独维护）。

完整 includes/excludes 见 [gradle/versions.toml](../../../gradle/versions.toml)。

## 胶水层（Loader × 精确 MC 版本）

胶水 **必须** pin 精确 MC 版本；**禁止**把 Fabric/Forge/NeoForge 合并进 `libs/mc-*`。

| 锚点 MC | Fabric | Forge | NeoForge | 构建入口 |
|---------|--------|-------|----------|----------|
| 1.20.1 | `mc-1.20.1/fabric` | `mc-1.20.1/forge` | — | Forge → `scripts/fastbuild/settings-forge.gradle` + Gradle 8.9 |
| 1.21.1 | `mc-1.21.1/fabric` | `mc-1.21.1/forge` | `mc-1.21.1/neoforge` | Forge → settings-forge.gradle |
| 1.21.11 | `mc-1.21.11/fabric` | `mc-1.21.11/forge` | `mc-1.21.11/neoforge` | Fabric → `settings-12111.gradle`；Forge → settings-forge |
| 26.1 | `mc-26.1/fabric` | **无 Forge** | `mc-26.1/neoforge` | Fabric → `settings-26.gradle` |
| 26.2 | `mc-26.2/fabric` | **无 Forge** | `mc-26.2/neoforge` | 协议 776；同 `libs/mc-26` 源码 |

**Forge 说明：** 1.20.1 用 `ForgeLegacyPlatform`；1.21.x Forge 与 NeoForge 并存但 API 近 NeoForge；26.x 仅 NeoForge（无 Forge 官方线）。

## 胶水 build.gradle 模板

```gradle
sourceSets.main.java.srcDirs(
    "${rootDir}/libs/mc-1.21/src/main/java",      // 按 G 组替换
    "${rootDir}/libs/common/src/main/java",
    'src/main/java',
)
sourceSets.main.resources.srcDirs(
    "${rootDir}/libs/common/src/main/resources",
    'src/main/resources',
)
implementation project(':libs:core')
implementation project(':libs:platform-api')
```

## 编辑规则（省 token）

1. **默认只改 `libs/common`** — 同步、缓存、Xaero、命令逻辑、配置解析。
2. **仅当调用 MC 版本敏感 API** 时改对应 `libs/mc-{锚点}`（四选一，勿复制到多个目录）。
3. **禁止** 在 `mc-*/shared` 或 4 份重复文件里改同一逻辑（迁移完成后删除 `mc-*/shared`）。
4. **Loader 差异**（事件注册、Payload、Cloth Config、mods.toml）只改对应 `mc-{版本}/{fabric|forge|neoforge}`。
5. **维度 ID / ResourceKey** 统一走 `DimensionApiHelper`；**玩家 Server 访问** 走 `PlayerLevelApiHelper`；**命令权限** 走 `CommandPermissionHelper`。
6. **客户端消息** 统一走 `ClientMessageHelper` 或 Platform；G4 的 API 差异不进 common。
7. **新增 MC 子版本**：先查 `gradle/versions.toml`；同 G 组只改 glue 的 `minecraft` 依赖，不改 Java。
8. **改完编译**：至少 `:libs:core:compileJava` + 受影响 glue 的 `:mc-*:{loader}:compileJava`；Forge 用 fastbuild 脚本。

## 迁移/重组检查清单

- [x] 删除 `mc-*/shared/`，源码迁入对应 `libs/mc-*`
- [x] 各 glue `build.gradle` 的 `srcDirs` 指向新路径
- [x] `settings.gradle` / fastbuild settings 引用 `libs:mc-*`
- [x] 相同文件只保留一份（`CacheGenerateCommand`、`ServerSyncHandlerLogic` → common）
- [x] 更新 `gradle/versions.toml` includes/excludes + JAR 版本范围

## 反模式

- ❌ `legacy` / `modern-loc` / `unobf` 目录名
- ❌ 为 1.21.5、1.21.7 等 hotfix 单独建 `libs/mc-*`
- ❌ 在 common 里 `import` 仅某一 G 组才有的 MC 类型而不经 Platform/shim
- ❌ 只改 Fabric 胶水忘记 Forge/NeoForge 对称注册（网络 Payload、tick 事件）
