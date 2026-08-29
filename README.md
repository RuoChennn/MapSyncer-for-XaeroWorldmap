# MapSyncer for Xaero's World Map

一个 Minecraft 多平台地图同步模组，将服务端已探索区域同步到客户端的 Xaero's World Map。

> **适用场景**：玩家首次进入已开放服务器，或服务器已用 Chunky 预生成地图，需要将地图同步给玩家，减少重复跑图时间成本。

---

## 开发日志

### v1.0.4（2026-08-29）— 跨地址缓存复用 + 超时重发兜底

本版本重点修复跨地址地图同步失败、同步超时无响应等问题：

1. **零拷贝重命名方案** — `handleMultiEntryCacheReuse` 从复制整目录改为 `Files.move` 重命名，解决复制 5-6GB 地图耗时过长问题
2. **空壳目录自动删除后重命名** — 修复根因 bug：原代码对空壳目录也返回 true 导致跳过复制
3. **超时重发机制** — 服务端分包 20s 超时通知重发 + 客户端 60s 超时自动重发（最多 3 次）+ `request_partial_timeout` 即时重发
4. **自动同步进度修复** — 删除 `handleProgressUpdate` 静默丢弃逻辑 + 修复 `incrementalUpdateMode` 默认 DISABLED

> 完整更新日志见 [`CHANGELOG.md`](CHANGELOG.md)

### v1.0.3 -> v1.0.4（2026-06-13 ~ 2026-07-01）

本版本核心是**多层洞穴渲染 + 自动同步系统 + 多版本构建架构**三大块：

1. **多层洞穴 / 地狱地图** — LayerPlan 分层扫描（SURFACE / ALL / 显式 Y），单次 MCA 输出多层洞穴，对齐 Xaero 的 underair 状态机
2. **自动同步增强** — 客户端 `autoSyncEnabled` 开关、TICK 周期同步（默认 5 分钟）、SCHEDULED 时间戳比对
3. **性能优化** — 增量扫描并行转换、流式读取降内存、客户端同步异步化、多处热点消除
4. **多版本工程重组** — G1-G4 锚点 + 胶水层，新增 mc-26.2（协议 776），恢复 Forge 与 26.x Fabric 构建
5. **生态适配与工具** — Fabric / Forge / NeoForge 权限适配、接入 Fabric Mod Menu、增强 MapPackager

> 完整更新日志见 [`CHANGELOG.md`](CHANGELOG.md)

---

## 运行环境

### 平台支持

> 优先适配现代版本。1.20.4 前 NeoForge 尚未正式独立不做适配；26.1 后 Forge 未提供开发文档不做适配。

| MC 版本 | Forge | NeoForge | Fabric |
|---------|:-----:|:--------:|:------:|
| 1.20.1 | ✅ | — | ✅ |
| 1.21.1 | ✅ | ✅ | ✅ |
| 1.21.11 | ✅ | ✅ | ✅ |
| 26.1 | — | ✅ | ✅ |
| 26.2 | — | ✅ | ✅ |

> 详细平台兼容性信息见 [`docs/features.md`](docs/features.md)

### 客户端依赖

支持独立服务器和内置服务器（单人游戏局域网共享）。内置服务器模式下，直接复用主机的 Xaero's World Map 存档目录作为地图缓存，无需二次转换。

| 依赖 | 要求 |
|------|------|
| Xaero's World Map | 1.40.11+ |

### 服务端要求

- 无需安装 Xaero，可独立运行
- 推荐配合 Chunky 等预生成工具使用

---

## 功能特性

> 按模块的完整说明见 [`docs/features.md`](docs/features.md)。

| 特性 | 说明 |
|------|------|
| **增量同步** | CRC32 + 时间戳比对，仅传输有变化的区域；保留客户端更新探索 |
| **流式加载** | 边接收边写入 Xaero 目录，每区域触发重载 |
| **带宽控制** | 可配置单包大小与 KB/s 限速；大包自动分片组装 |
| **断点续传** | 基于客户端哈希缓存，中断后可恢复 |
| **跨地址缓存复用** | 切换服务器地址时通过 `Files.move` 零拷贝重命名复用已下载地图，避免重复下载 |
| **视距优先** | 视距内区域优先传输；视距外可限速喂给 Xaero |
| **维度 / 洞穴** | 原版与 Mod 维度；`dimension = layerPlan`（SURFACE / ALL / 显式 Y / 组合） |
| **增量更新** | 服务端 DISABLED / TICK / SCHEDULED 自动更新地图缓存 |
| **自动同步** | 按服务端模式进服或在线自动拉取（可关）；手动 sync 始终可用 |
| **并发转换** | `maxConcurrentRegions`：0=自动（逻辑核−2，上限 16） |
| **配置热重载** | `/mapsyncer reloadconfig`（Fabric 为 `/mapsyncerserver`） |
| **内置服务器** | 局域网共享时复用主机 Xaero 存档，免二次转换 |
| **MapPackager** | 离线将 `server_map_cache` 打成客户端可用 zip |
| **握手保护** | 未安装本模组的客户端不发送自定义包 |

---

## 命令清单

### 客户端命令

| 命令 | 说明 |
|------|------|
| `/mapsyncer` | 显示帮助 |
| `/mapsyncer sync` | 同步当前维度 |
| `/mapsyncer sync <维度>` | 同步指定维度 |
| `/mapsyncer sync all` | 同步所有维度 |
| `/mapsyncer autosync` | 查看客户端自动同步开关状态 |
| `/mapsyncer autosync on\|off` | 开启/关闭客户端自动同步（写入配置文件） |

**维度参数支持**：
- 原版：`overworld`、`the_nether`、`the_end`
- Mod 维度：完整 ID，如 `twilightforest:twilight_forest`

### 服务端命令（需 OP 权限）

> Forge/NeoForge 服务端命令前缀为 `/mapsyncer`，Fabric 为 `/mapsyncerserver`（避免与客户端 `/mapsyncer` 冲突）

| 命令 | 说明 |
|------|------|
| `/mapsyncer generate` | 生成所有维度缓存 |
| `/mapsyncer generate <维度>` | 生成指定维度（增量模式） |
| `/mapsyncer generate <维度> <x> <z>` | 生成单个区域 |
| `/mapsyncer generate <维度> --force` | 强制重新生成（清除缓存） |
| `/mapsyncer status` | 查看生成进度和缓存统计 |
| `/mapsyncer incremental` | 查看当前增量更新模式 |
| `/mapsyncer incremental off` | 禁用增量更新 |
| `/mapsyncer incremental tick [间隔]` | 启用周期更新（2400–72000 ticks，默认 6000 = 5 分钟） |
| `/mapsyncer incremental scheduled [时] [分]` | 启用定时更新（默认 04:00） |
| `/mapsyncer reloadconfig` | 从磁盘重新加载服务端配置（含维度列表与增量更新模式） |

---

## 配置文档

### 客户端配置

| 配置项 | 默认值 | 范围 | 说明 |
|--------|--------|------|------|
| `hashThreads` | CPU 核心数/2 | 1~核心数 | CRC32 哈希计算并行线程数 |
| `mapRegionLoadIntervalTicks` | 1 | -1~100 | 视距外 region 传入 Xaero 的 tick 间隔：-1=一次排空，0=仅视距内，N=每 N tick 加载 1 个 |
| `autoSyncEnabled` | true | - | 进服自动同步（TICK/SCHEDULED）；TICK 模式另启在线周期同步；关闭后仍可手动 `/mapsyncer sync` |

Fabric 配置文件：`config/mapsyncer-client.properties`（可选 Cloth 仅编辑客户端项）；Forge/NeoForge：`config/mapsyncer-client.toml` 的 `[client]` 段。

Fabric 服务端 `.properties` 同时接受 camelCase 与 snake_case 键名（如 `defaultScanMode` / `default_scan_mode`），便于从 TOML 复制配置。

### 服务端配置

服务端配置**只通过配置文件**（及 `/mapsyncer reloadconfig` / Fabric `/mapsyncerserver reloadconfig`）管理，不使用 Cloth。

Forge 配置文件位于 `world/serverconfig/mapsyncer-server.toml`（每个世界独立配置）
NeoForge / Fabric 配置文件位于 `config/` 目录下（NeoForge 为 `.toml`，Fabric 为 `.properties`）

**通用设置 `[general]`**

| 配置项 | 默认值 | 范围 | 说明 |
|--------|--------|------|------|
| `enableDebugLogging` | false | - | 启用调试日志 |
| `maxConcurrentRegions` | 0（自动） | 0-16 | 并发转换区域数；0 = `max(1, min(16, 逻辑处理器数 − 2))` |
| `maxSyncPacketSize` | 262144 (256KB) | 64KB-1MB | 单包最大大小 |
| `syncSpeedLimitKBps` | 1024 (1MiB/s) | 0-10240 | 同步速率限制（0=不限） |

**增量更新 `[incremental_update]`**

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `incrementalUpdateMode` | DISABLED | DISABLED / TICK / SCHEDULED |
| `incrementalUpdateIntervalTicks` | 6000 | TICK 模式间隔（20 ticks = 1 秒，默认 5 分钟，最小 2 分钟） |
| `scheduledUpdateHour` | 4 | 定时更新小时（0-23） |
| `scheduledUpdateMinute` | 0 | 定时更新分钟（0-59） |

**维度扫描 `[dimension_scan]`**

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `default_scan_mode` | SURFACE | 未配置维度的默认层计划回退（SURFACE=仅地表；CAVE=单层洞穴，见 `default_cave_start`） |
| `default_cave_start` | 63 | `default_scan_mode=CAVE` 时未配置维度的 caveStart Y |

**维度配置**（推荐 `维度 = layerPlan`，Fabric / Forge / NeoForge 均为列表）：

```toml
dimension_configs = [
    "minecraft:overworld = SURFACE",
    "minecraft:the_nether = SURFACE,63",
    "minecraft:the_end = SURFACE"
]
```

**layerPlan**（逗号分隔，可组合）：

| 值 | 说明 |
|----|------|
| `SURFACE` | 仅地表。无顶盖为全列扫描；有顶盖（地狱）为逻辑顶以上（Y≥128） |
| `ALL` | 维度高度范围内全部洞穴层 |
| `63` / `63,127` | 仅指定洞穴层（caveStart Y），不含地表 |
| `SURFACE,63` / `SURFACE,ALL` / `ALL,63` | 组合；`ALL` 与显式 Y 按层号去重 |

- 仅写 `SURFACE` **不会**自动生成洞穴；需洞穴须写 Y 或 `ALL`
- 地狱默认 `SURFACE,63`：逻辑顶以上地表 + 洞穴 Y=63（Xaero 层号 3 → `caves/3/`）
- 兼容：`维度|layerPlan`、旧多字段管道、Fabric 旧键；维度类型信息运行时从 API 获取，不写进配置

**洞穴层号**：`caveStart >> 4`（Y=63 → 层 3 → `caves/3/`）。

---

## 增量更新模式与客户端自动同步

服务端通过 `incrementalUpdateMode` 控制**地图缓存**何时重新扫描 MCA 并生成；客户端在收到 `ServerInstalledPayload` 后，根据同一模式且 **`autoSyncEnabled` 为 true** 时决定是否**自动发起 sync**（与手动 `/mapsyncer sync` 共用同一套 hash/时间戳比对，无需传输的区域会被跳过）。可通过 `/mapsyncer autosync off` 或配置文件关闭客户端自动同步。

### DISABLED（禁用）

| 端 | 行为 |
|----|------|
| **服务端** | 不运行增量扫描处理器 |
| **客户端** | 不自动 sync；可手动 `/mapsyncer sync`；若增量更新关闭且存在未完成同步，进服时提示断点续传 |

### TICK（周期模式）

| 端 | 行为 |
|----|------|
| **服务端** | 每 `incrementalUpdateIntervalTicks` tick 扫描一次有变化的 MCA 并更新缓存（默认 **6000 tick = 5 分钟**，最小 **2400 tick = 2 分钟**） |
| **客户端 · 进服** | 比对 `ClientTimestampCache` 最大时间戳与服务端 `lastGenerationTimestamp`；若本地较旧 **且** 距上次自动 sync 已超过 tick 间隔（分钟），则延迟 5 秒后 `sync all`（聊天栏提示） |
| **客户端 · 在线** | 启动与生成周期一致的计时器，每周期自动 `sync all`；进度与结果仅显示在 **Action Bar**（周期同步文案），不发聊天消息 |
| **客户端 · 手动** | `/mapsyncer sync` 不受冷却限制 |

### SCHEDULED（日程表模式）

| 端 | 行为 |
|----|------|
| **服务端** | 每天在 `scheduledUpdateHour:scheduledUpdateMinute`（默认 **04:00**，服务器**本地时区**）的 1 分钟窗口内执行一次增量扫描；同一天只执行一次 |
| **客户端 · 进服** | 仅比对时间戳：若 `clientMaxTimestamp < serverLastGenerationTimestamp` 则自动 `sync all`；**无冷却**，每次进服只要本地落后就会 sync |
| **客户端 · 在线** | **无**在线周期计时器；服务端更新后需进服触发或手动 sync |
| **客户端 · 手动** | 同 TICK |

### 共用规则

- **断点续传**：任意模式下，若存在未完成同步（`needsResume`），进服优先自动续传，且启用自动 sync 时不再弹出断点续传聊天提示。
- **比对逻辑**：服务端 `RegionSyncPolicy` — hash 一致跳过；客户端时间戳 ≥ 服务端则保留本地探索；否则传输。
- **状态提示**：进服时显示「自动同步：已关闭 / 每 X 分钟 / 每天」（SCHEDULED 的「每天」指服务端生成 schedule，客户端进服只看时间戳）。

---

## MapPackager — 离线地图打包工具

独立 CLI 工具，将服务器 `server_map_cache/` 目录打包为客户端可直接使用的 Xaero 地图 zip 包。适用于无法安装 mod 的客户端或离线分发的场景。

### 用法

```bash
java -jar mapsyncer-packager.jar -c <缓存目录> -o <输出文件> [选项]
```

### 参数

| 参数 | 说明 |
|------|------|
| `-c, --cache-dir <路径>` | 服务器缓存目录路径（必填） |
| `-o, --output <路径>` | 输出 zip 文件路径（必填） |
| `-s, --server-name <名称>` | 服务器名称，默认 "Server" |
| `-w, --world-id <id>` | 手动指定 World ID |
| `-d, --world-dir <路径>` | 自动从 xaeromap.txt 检测 World ID |
| `-h, --help` | 显示帮助 |

### 示例

```bash
# 基本用法
java -jar mapsyncer-packager.jar -c ./server_map_cache -o ./map_pack.zip

# 指定服务器名称和 World ID
java -jar mapsyncer-packager.jar -c ./cache -s "MyServer" -w 42 -o output.zip

# 自动检测 World ID
java -jar mapsyncer-packager.jar -c ./cache -d ./world -o output.zip
```

### 功能

- 自动扫描所有维度目录（含 Mod 维度）
- 转换 `generation_cache.properties` → `sync_timestamps.cache`（客户端可直接使用）
- 按 `Multiplayer_<服务器名>/<维度>/mw$<worldId>/` 结构组织
- 不需要安装 Xaero 或 Minecraft，纯 Java 运行

---

## 项目结构

```
libs/                   抽象库层（平台无关，编译为独立 JAR）
├── core/               纯 Java 核心：MCA/NBT 解析、工具类、MapPackager
├── platform-api/       平台抽象接口、网络 Payload 定义
├── common/             客户端/服务端共享逻辑（同步、缓存、自动同步管理器）
├── mc-1.20/            G1 锚点源码（1.20.1 API）
├── mc-1.21/            G2 锚点源码（1.21.1 API）
├── mc-1.21.11/         G3 锚点源码（1.21.11 API）
└── mc-26/              G4 锚点源码（26.x API）

mc-1.20.1/              1.20.1 胶水层（Loader + Platform 实现）
├── fabric/
└── forge/

mc-1.21.1/              1.21.1 胶水层
├── fabric/
├── forge/
└── neoforge/

mc-1.21.11/             1.21.11 胶水层
├── fabric/
├── forge/
└── neoforge/

mc-26.1/                26.1 胶水层（协议 775）
├── fabric/
└── neoforge/

mc-26.2/                26.2 胶水层（协议 776，复用 libs/mc-26）
├── fabric/
└── neoforge/
```

### 工作流

```
服务端 MCA 文件 (region/*.mca)
        │
        ▼
    MCA 解析器（纯 Java，不依赖 Xaero）
   解压 → NBT 解析 → 提取区块数据
        │
        ▼
   区域转换 (RegionConverterStandalone)
        │
        ▼
压制成 Xaero 格式 (region.zip)
        │
        ▼
  时间戳+哈希缓存 (GenerationCache)
        │
        ▼
    增量更新处理器（可选）
  TICK 模式 / SCHEDULED 模式
        │
        ▼
    网络同步协议
  哈希比对 → 视距优先排序
  分批传输 + 速度限制
        │
        ▼
    流式加载接收
  边接收边写入（mw$worldId/）
        │
        ▼
   Xaero 加载触发（反射调用）
  requestLoad → 地图重新渲染
```

### 文件存储

```
服务端:
  <server>/server_map_cache/
  ├── null/              # 主世界
  ├── DIM-1/             # 地狱
  ├── DIM1/              # 末地
  ├── caves/<layer>/     # 洞穴模式输出
  └── generation_cache.properties  # 时间戳+哈希缓存

客户端:
  <client>/xaero/world-map/Multiplayer_<IP>/     # 新版 Xaero 统一路径（优先）
  <client>/XaeroWorldMap/Multiplayer_<IP>/       # 旧版 Xaero 路径（兼容 fallback）
  ├── null/mw$<worldId>/   # 主世界
  ├── DIM-1/mw$<worldId>/  # 地狱
  └── DIM1/mw$<worldId>/   # 末地
```

### 维度映射

| 维度 | Minecraft ID | Xaero 目录 |
|------|--------------|------------|
| 主世界 | `minecraft:overworld` | `null` |
| 地狱 | `minecraft:the_nether` | `DIM-1` |
| 末地 | `minecraft:the_end` | `DIM1` |
| Mod 维度 | `namespace:path` | `namespace$path` |

---

## 构建

```bash
# 构建所有活跃平台（并行）
./gradlew build -x test --parallel

# 构建单个平台
./gradlew :mc-1.21.1:forge:build -x test
./gradlew :mc-1.21.1:fabric:build -x test

# 构建 MapPackager 独立工具
./gradlew buildPackager

# 快捷脚本
scripts/fastbuild/build-all.bat              # 构建全部活跃平台
scripts/fastbuild/build-forge-1.20.1.bat     # 构建指定平台
scripts/fastbuild/build-packager.bat         # 构建 MapPackager
scripts/fastbuild/build-target.ps1 all -NoTest  # PowerShell 构建全部
```

产物输出：mod JAR 到各平台模块的 `build/libs/` 目录，`buildPackager` 和 `buildAll` 额外收集到根目录 `output/`。

---

## 已知问题

| 问题 | 说明 | 影响 |
|------|------|------|
| 非原版维度洞穴层 | 部分 Mod 维度的多层洞穴配置需自行验证 layerPlan | 仅在使用 ALL/显式 Y 的 Mod 维度时可能需调参 |

> v1.0.4 已修复地狱 `SURFACE,63` 地表/洞穴层生成与客户端同步显示；详见 `CHANGELOG.md`。

---

**许可证**：GPL-3.0

**致谢**：Xaero's World Map & Minimap
