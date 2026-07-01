# MapSyncer for Xaero's World Map

一个 Minecraft 多平台地图同步模组，将服务端已探索区域同步到客户端的 Xaero's World Map。

> **适用场景**：玩家首次进入已开放服务器，或服务器已用 Chunky 预生成地图，需要将地图同步给玩家，减少重复跑图时间成本。

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

| 特性 | 说明 |
|------|------|
| **增量同步** | CRC32 哈希比对 + 时间戳比对，仅同步有变化的区域 |
| **流式加载** | 边接收边写入 Xaero 目录，每区域立即触发重载 |
| **带宽感知** | 动态调整发送速率，避免阻塞游戏网络 |
| **断点续传** | 同步中断后重连自动恢复（基于哈希比对） |
| **视距优先** | 玩家视距范围内的区域优先传输 |
| **维度支持** | 主世界、地狱、末地及 Mod 维度（暮光森林等） |
| **增量更新** | 服务端可配置 TICK 周期 / SCHEDULED 定时自动更新地图缓存 |
| **洞穴模式** | 通过 layerPlan 配置地表/洞穴层（SURFACE、ALL、显式 Y），输出到 caves 子目录 |
| **多线程哈希** | 客户端 CRC32 计算支持并行，线程数可配置 |
| **自动同步** | 根据服务端增量更新模式，进服或在线自动拉取地图（见下文） |
| **内置服务器** | 单人游戏局域网共享，复用主机 Xaero 存档 |
| **MapPackager** | 独立 CLI 工具，将服务器缓存打包为客户端可用的 Xaero 地图包（离线分发） |
| **Payload 分片** | 双向自动分片，>28KB 数据拆分为小包传输，接收端自动组装 |
| **握手保护** | 禁止向未安装 mod 的客户端发送自定义 payload，防止踢出玩家 |

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
| `/mapsyncer clearstate` | 清除同步恢复状态（忽略断点续传） |

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
| `/mapsyncer incremental off` | 禁用增量更新 |
| `/mapsyncer incremental tick [间隔]` | 启用周期更新（2400–72000 ticks，默认 6000 = 5 分钟） |
| `/mapsyncer incremental scheduled [时] [分]` | 启用定时更新（默认 04:00） |

---

## 配置文档

### 客户端配置

| 配置项 | 默认值 | 范围 | 说明 |
|--------|--------|------|------|
| `hashThreads` | CPU 核心数/2 | 1~核心数 | CRC32 哈希计算并行线程数 |
| `autoSyncEnabled` | true | - | 客户端自动同步（进服 + TICK 在线周期）；关闭后仍可手动 `/mapsyncer sync` |

Fabric 配置文件：`config/mapsyncer-client.properties`；Forge/NeoForge：`config/mapsyncer-client.toml` 的 `[client]` 段。

### 服务端配置

Forge 配置文件位于 `world/serverconfig/mapsyncer-server.toml`（每个世界独立配置）
NeoForge / Fabric 配置文件位于 `config/` 目录下（NeoForge 为 `.toml`，Fabric 为 `.properties`）

**通用设置 `[general]`**

| 配置项 | 默认值 | 范围 | 说明 |
|--------|--------|------|------|
| `enableDebugLogging` | false | - | 启用调试日志 |
| `maxConcurrentRegions` | 4 | 1-16 | 并发转换区域数 |
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
| `default_scan_mode` | SURFACE | 未配置维度的默认模式（SURFACE → 仅地表；CAVE → 单层洞穴，见 `default_cave_start`） |
| `default_cave_start` | 63 | `default_scan_mode=CAVE` 时未配置维度的洞穴起始 Y |

**维度配置格式**（新格式，推荐）：

```toml
dimension_configs = [
    "minecraft:overworld|SURFACE|true|false|-64|384|384",
    "minecraft:the_nether|SURFACE,63|false|true|0|256|128",
    "minecraft:the_end|SURFACE|false|false|0|256|256"
]
```

格式：`维度ID|layerPlan|有天空光|有顶棚|minY|高度|逻辑高度`

**layerPlan**（逗号分隔，可组合）：

| 值 | 说明 |
|----|------|
| `SURFACE` | 仅地表。无顶盖维度为全列扫描；有顶盖维度（地狱）为逻辑顶以上（Y≥128） |
| `ALL` | 生成维度高度范围内的全部洞穴层 |
| `63` / `63,127` | 仅指定洞穴层（caveStart Y 坐标），不含地表 |
| `SURFACE,63` | 地表 + 洞穴层 63 |
| `SURFACE,ALL` / `ALL,63` | 组合；`ALL` 与显式 Y 按层号自动去重 |

- 仅写 `SURFACE` 时**不会**自动生成洞穴层；需要洞穴须显式写 Y 或 `ALL`
- 地狱默认 `SURFACE,63`：逻辑顶以上地表 + 洞穴层 63
- 旧格式 `维度|SURFACE|63|…` / `维度|CAVE|63|…` 仍可读取，会自动合并为 layerPlan

**dim_type_info** 字段：`hasSkylight|hasCeiling|minY|height|logicalHeight`。地狱 `logicalHeight=128` 表示逻辑顶在 Y127，其以上为地表区。

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
└── common/             客户端/服务端共享逻辑（同步、缓存、自动同步管理器）

mc-1.20.1/              1.20.1 版本
├── shared/             源码复用层（由平台模块 sourceSet 引用）
├── fabric/             平台实现层（编译产出最终 mod JAR）
└── forge/

mc-1.21.1/              1.21.1 版本
├── shared/
├── fabric/
├── forge/
└── neoforge/

mc-1.21.11/             1.21.11 版本
├── shared/
├── fabric/
├── forge/
└── neoforge/

mc-26.1/                26.1 版本
├── shared/
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
| 洞穴内容异常 | 洞穴模式下部分内容不准确 | 基本上只有地狱受影响，看情况优化 |

---

**许可证**：GPL-3.0

**致谢**：Xaero's World Map & Minimap
