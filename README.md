# MapSyncer-for-XaeroWorldmap

一个完全由AI编写的 Minecraft NeoForge 1.21.1 模组，用于将服务端已探索的区域地图数据同步到客户端的 Xaero's World Map。

## 功能特性

### 核心功能

- **服务端独立 MCA 解析器**：零依赖实现，不依赖 Minecraft API，直接使用 Java 标准库解析 `.mca` 区域文件格式，支持 GZIP/ZLIB/LZ4 压缩
- **时间戳比对同步**：客户端发送本地文件修改时间戳，服务端比对后只发送更新时间晚于客户端的区域，保留客户端新探索的数据
- **智能跳过机制**：如果客户端数据比服务端新（玩家在服务端生成后继续探索），则跳过该区域同步
- **直接写入模式**：服务端数据直接覆盖客户端过时文件，确保数据一致性
- **同步期间暂停写入**：同步期间禁用 Xaero 的 chunk 更新，防止数据冲突
- **选择性重载机制**：同步完成后只重置玩家视距范围内的区域，而非全部区域，减少性能开销
- **缓存清除与强制重载**：清除视距范围内的 .xwmc 渲染缓存，确保从新文件加载
- **分批传输**：大数据按1MB分批传输，避免网络包过大导致失败
- **进度显示**：实时显示同步进度
- **同步中止保护**：玩家断开连接或跨维度时自动中止同步，避免数据混乱
- **限速功能**：可配置同步速率限制（KB/s），避免网络拥堵导致玩家超时掉线
- **断点续传**：玩家因网络问题断开重连后，可从上次中断的位置继续同步，无需重新开始

### 命令功能

**客户端命令** `/mapsyncer`：
- `/mapsyncer sync` - 同步当前维度
- `/mapsyncer sync overworld` - 同步主世界
- `/mapsyncer sync nether` - 同步下界
- `/mapsyncer sync end` - 同步末地
- `/mapsyncer sync all` - 同步所有维度

**服务端命令** `/mapsyncer`（需要 OP 权限）：
- `/mapsyncer generate` - 生成所有维度的地图缓存
- `/mapsyncer generate <dimension>` - 生成指定维度缓存（支持原版维度：overworld/the_nether/the_end，或任意 mod 维度 ID，如 modid:custom_dim）
- `/mapsyncer generate --region <x> <z>` - 生成单个区域
- `/mapsyncer status` - 查看生成进度
- `/mapsyncer incremental off` - 禁用增量更新
- `/mapsyncer incremental tick [interval]` - TICK 模式（周期更新）
- `/mapsyncer incremental scheduled [hour] [minute]` - SCHEDULED 模式（每日定时）
- `/mapsyncer incremental status` - 查看增量更新状态

### 服务端功能

- 增量更新检测，自动更新已修改的region
- 支持多维度同步

### Mod 支持

本模组支持非原版 mod 添加的方块和维度：

- **方块属性自动识别**：使用 Minecraft API 查询方块属性，自动识别 mod 方块的透明性、流体状态、含水属性、发光特性等
  - 使用 `BlockTags.FLOWERS` 标签识别 mod 花
  - 使用 `RenderShape.INVISIBLE` 判断隐形方块
  - 使用 `AirBlock`/`TransparentBlock` 类判断透明方块
  - 使用 `getLightEmission()` API 判断发光方块
- **未知方块包装器**：对于未注册的方块，保存原始 NBT 数据以便后续处理（参考 Xaero UnknownBlockState）
- **维度命令支持任意维度**：服务端 `/mapsyncer generate` 命令支持任意维度 ID（如 `modid:custom_dim`）
- **方块颜色四层策略**：
  1. 纹理颜色提取（客户端，从方块纹理提取平均颜色）
  2. MapColor API（服务端，使用 Minecraft MapColor）
  3. 原版方块精确颜色（保持视觉效果一致性）
  4. 启发式规则（基于名称模式推断，如 `_ore` → 金色）
- **生物群系完整支持**：mod 生物群系数据完整写入 Xaero 格式

## 环境要求

- Minecraft 1.21.1
- NeoForge 21.1.77+
- Java 21

**客户端额外要求**：
- Xaero's World Map 1.40.11+
- Xaero's Minimap 25.3.10+（可选，推荐安装）

**服务端**：无需安装 Xaero 模组，可独立运行

**服务端 Mod 兼容性**：
- 支持 C2ME（Concurrent Chunk Map Engine）：地图生成时会自动将保存操作调度到主线程执行，避免并发冲突
- 兼容其他优化类 mod（Lithium、FerriteCore 等）

## 安装

### 1. 安装 Xaero's World Map（客户端）

从 CurseForge 下载：
- [Xaero's World Map - CurseForge](https://www.curseforge.com/minecraft/mc-mods/xaeros-world-map)
- [Xaero's Minimap - CurseForge](https://www.curseforge.com/minecraft/mc-mods/xaeros-minimap)

**注意**：Xaero 模组仅在客户端需要，服务端无需安装。

### 2. 安装本模组

将 jar 文件放入：
- **客户端**：`mods` 目录（需配合 Xaero's World Map）
- **服务端**：`mods` 目录（可独立运行，无需 Xaero）

### 3. 构建项目

```bash
./gradlew build
```

构建产物位于 `build/libs/mapsyncer-1.0.0.jar`。

## 使用方法

### 服务端预生成地图缓存

```bash
/mapsyncer generate
```

此命令会扫描服务端的 world 目录，将所有已探索区块转换为 Xaero 格式的地图文件。

### 同步地图（客户端）

1. 加入服务器后，使用命令 `/mapsyncer`
2. 客户端计算本地region文件的修改时间戳并发送到服务端
3. 服务端比对时间戳，只返回服务端更新时间晚于客户端的区域
4. 客户端直接写入服务端数据（覆盖本地过时文件）
5. 清除渲染缓存，触发地图重载

**智能同步策略**：
- 如果客户端时间戳 ≥ 服务端时间戳，跳过同步（保留客户端数据）
- 如果客户端时间戳 < 服务端时间戳，执行同步（更新过时数据）
- 如果客户端不存在该region，执行同步（新区域）

### 同步限速

服务端支持同步速率限制，避免大量数据传输导致网络拥堵和玩家超时掉线。

**配置方式**：
```json
// config/mapsyncer-common.json
{
  "syncSpeedLimitKBps": 1000  // 限制 1000KB/s
}
```

- `0` = 不限速（默认）
- `500-2000` = 推荐范围，根据服务器带宽调整
- 限速应用于每个批次发送之间，自动控制发送速率

### 断点续传

当玩家因网络问题在同步过程中断开连接时，服务端会保留同步进度。玩家重新连接并再次发起同步请求时，服务端会自动从上次中断的位置继续传输，无需重新开始。

**工作机制**：
1. 同步开始后，服务端记录所有待传输的 region 数据
2. 玩家断开连接时，进度数据保留在内存中
3. 玩家重连后再次执行 `/mapsyncer`，服务端检测到未完成的进度
4. 从上次中断的索引位置继续发送剩余数据
5. 同步完成或再次断开时清理进度数据

**注意事项**：
- 断点续传仅在同一服务器会话内有效
- 服务器重启后进度数据会清除
- 可通过 `enableResumeSync` 配置项禁用此功能

### 同步流程图

```
客户端                                    服务端
  │                                        │
  │  执行 /mapsyncer 命令                   │
  │                                        │
  │  禁用 chunk 更新                        │
  │                                        │
  │  计算本地region时间戳                    │
  │  (文件修改时间)                          │
  │                                        │
  │ ──── SyncRequestPayload (时间戳) ────> │
  │                                        │
  │                              比对时间戳 │
  │                              (客户端早于服务端才同步) │
  │                              读取region │
  │                              分批打包   │
  │                              (检测玩家状态) │
  │                                        │
  │ <─── SyncResponsePayload (数据) ───── │
  │ <─── SyncProgressPayload (进度) ───── │
  │                                        │
  │  直接写入服务端数据                      │
  │  (仅覆盖过时文件)                        │
  │                                        │
  │  累积更新的region列表               │
  │                                        │
  │  清除视距范围内缓存                     │
  │  detectRegions 扫描新文件               │
  │  selectiveResetRegionLoadStates        │ ← 仅重置必要区域
  │  startFullMapReload                    │
  │                                        │
  │  恢复 chunk 更新                        │
  │                                        │
```

### 同步中止条件

服务端会在以下情况下中止同步：

**1. 玩家断开连接**
- **触发时机**：`PlayerLoggedOutEvent` 事件
- **机制**：维护 `syncingPlayers` 集合，玩家断开时移除标记
- **效果**：后续批次发送检查会提前终止

**2. 玩家跨维度**
- **触发时机**：每批次发送前检查
- **机制**：记录同步开始时的维度，检查当前维度是否一致
- **效果**：维度变化时立即中止同步，避免数据混乱

**3. 玩家连接失效**
- **触发时机**：每批次发送前检查
- **机制**：检查 `player.connection` 是否有效
- **效果**：连接失效时立即中止

## 文件存储结构

### 服务端缓存目录

```
<server>/server_map_cache/
├── overworld/
│   ├── -1_-1.zip
│   ├── 0_0.zip
│   └── ...
├── DIM-1/  (下界)
├── DIM1/   (末地)
├── mca_timestamps.cache          # MCA文件时间戳（增量检测）
└── generation_timestamps.cache   # 生成时间戳（同步比对）
```

### 客户端地图目录

```
<client>/xaero/world-map/
├── Multiplayer_<serverIP>/
│   ├── null/              (主世界)
│   │   ├── mw$<worldId>/
│   │   │   ├── -1_-1.zip
│   │   │   ├── 0_0.zip
│   │   │   ├── cache/        ← 地表渲染缓存 (.xwmc)
│   │   │   ├── cache_1/      ← 地表渲染缓存
│   │   │   └── ...
│   ├── DIM-1/             (下界)
│   └── DIM1/              (末地)
└── world/                 (单人世界)
```

### 缓存文件说明(Xaero原生)

`.xwmc` 文件是 Xaero 的渲染缓存，存储已渲染的地图纹理：
- `cache/` - 地表层缓存
- `cache_1/` - 地表层备用缓存
- 同步完成后会清除地表缓存，确保从新文件重新渲染

## 项目结构

```
src/main/java/com/mapsyncer/
├── MapSyncer.java                      # 主类
│
├── client/                           # 客户端模块
│   ├── ClientHashManager.java        # 本地文件时间戳计算
│   ├── CompletedRegionsCache.java    # 完整region缓存（保留）
│   ├── MapPacketReceiver.java        # 网络包接收+刷新地图
│   ├── MapSyncerCommand.java         # 同步命令处理
│   ├── RegionMerger.java             # 区块级增量合并（保留）
│   ├── SyncProgressTracker.java      # 进度追踪
│   ├── SyncProgressBarRenderer.java  # 进度条渲染
│   └── XaeroMapIntegrator.java       # Xaero集成+数据写入+loadState重置
│
├── server/                           # 服务端模块
│   ├── ServerSyncHandler.java        # 同步处理+分批传输+状态检查
│   ├── ConversionOrchestrator.java   # MCA转换协调
│   ├── GenerationTimestampCache.java # 生成时间戳缓存（同步比对）
│   ├── RegionScanner.java            # 区域扫描
│   ├── XaeroWriter.java              # Xaero格式写入
│   ├── CacheGenerateCommand.java     # 缓存生成命令
│   ├── IncrementalUpdateHandler.java # 增量更新
│   ├── PlayerJoinHandler.java        # 玩家加入事件
│   ├── McaTimestampCache.java        # 时间戳缓存
│   └── BlockPropertyResolver.java    # 方块属性解析（Minecraft API）
│
├── mca/                              # MCA文件解析
│   ├── McaReader.java                # MCA文件读取
│   ├── ChunkDataParser.java          # 区块数据解析
│   ├── ChunkSectionParser.java       # 区块段解析
│   ├── BlockClassifier.java          # 方块分类（已弃用，保留备用）
│   ├── LightMode.java                # 光照模式
│   ├── RegionConverterStandalone.java # 区域转换
│   └── UnknownBlockStateWrapper.java  # 未知方块包装器
│
├── nbt/                              # NBT解析
│   ├── NbtReader.java                # NBT读取
│   └── Tag.java                      # NBT标签类型
│
├── network/                          # 网络协议
│   ├── PacketHandler.java            # 数据包注册
│   └── ChunkMapData.java             # 区域数据结构
│
├── config/                           # 配置
│   └── ModConfig.java                # 模组配置
│
└── util/                             # 工具
    └── BlockColorMapper.java         # 方块颜色映射
```

## 网络协议

| 数据包 | 方向 | 说明 |
|-------|------|-----|
| SyncRequestPayload | C→S | 发送本地region时间戳列表（文件修改时间） |
| SyncResponsePayload | S→C | 返回region数据（分批，isComplete标记最后一批） |
| SyncProgressPayload | S→C | 同步进度更新 |

**时间戳比对逻辑**：
- 客户端时间戳 ≥ 服务端时间戳 → 跳过（保留客户端新数据）
- 客户端时间戳 < 服务端时间戳 → 同步（更新过时数据）
- 客户端无时间戳 → 同步（新区域）

## Xaero 内部机制

### 区域层级结构

Xaero 使用分层区域管理：
- `BranchLeveledRegion` (level 3→1) - 分支节点，2×2 子节点数组
- `MapRegion` (level 0) - 叶节点，存储实际地图数据

### loadState 状态

| 值 | 状态 | 说明 |
|---|------|-----|
| 0 | UNLOADED | 未加载，需要从磁盘读取 |
| 2 | LOADED | 已加载，数据在内存中 |
| 4 | CLEARED | 已清除 |

同步时将 loadState 从 2 重置为 0，强制从新文件重新加载。

### 重载流程

1. `clearXaeroCacheSelective()` - 清除视距范围内的渲染缓存
2. `detectRegions(20)` - 扫描磁盘上的新区域文件
3. `selectiveResetRegionLoadStates()` - **选择性重置**：仅重置必要的区域
4. `startFullMapReload(Integer.MAX_VALUE, false, mapProcessor)` - 触发重载（只有loadState=0的区域会重新加载）

## 客户端同步机制

### 同步流程概览

```
客户端                                    服务端
  │                                        │
  │  1. /mapsyncer 命令                     │
  │     → disableChunkUpdates()            │
  │     → 发送时间戳请求                     │
  │                                        │
  │  2. 接收 SyncResponsePayload (分批)    │
  │     → 禁用 chunk 更新                   │
  │     → 累积更新的region列表               │
  │     → 直接写入 .zip 文件                │
  │                                        │
  │  3. isComplete=true 时                  │
  │     → recordUpdatedRegions()            │
  │     → clearXaeroCacheSelective()        │
  │     → detectRegions(20)                 │
  │     → selectiveResetRegionLoadStates()  │ ← 仅重置必要区域
  │     → startFullMapReload()              │
  │     → enableChunkUpdates()              │
  │                                        │
```

### Chunk 更新暂停机制

同步期间暂停 Xaero 的 chunk 写入，防止数据冲突：

- **禁用时机**：发送同步请求时、接收数据时
- **恢复时机**：地图重载触发后
- **实现方式**：
  - 设置 `chunkUpdatesDisabled` 标志
  - 通过反射设置 `MapWriter.paused = true`
  - 或中断 MapWriter 线程

### 数据写入策略

采用**直接覆盖模式**（无增量合并）：

- 服务端数据直接写入客户端文件
- 覆盖本地同名 region 文件
- 使用临时文件 + `REPLACE_EXISTING` 确保原子写入

### 选择性重载机制

**优化后的重载流程（不再重置所有区域）**：

**重置范围**：只重置以下三类区域的交集
1. **本次同步更新的region** - 从服务端接收的区域列表
2. **玩家视距范围内的region** - 基于renderDistance计算
3. **玩家当前位置所在的region** - 确保立即看到变化

**计算逻辑**：
```
playerChunkX = player.getBlockX() >> 4       // 玩家所在chunk
playerRegionX = playerChunkX >> 5            // 玩家所在region
regionRadius = (renderDistance >> 5) + 2     // 视距region半径

regionsToReset = updatedRegions ∩ viewRegions
regionsToReset += playerRegion              // 确保玩家位置
```

**性能优势**：
- 传统方式：重置所有已加载区域（可能数百个）
- 优化方式：重置约10-25个区域（视距范围内）
- 减少内存操作和渲染开销

### 强制刷新四步流程

当收到最后一批数据 (`isComplete=true`) 时执行：

**Step 1: 记录更新的region**
```java
recordUpdatedRegions(allReceivedChunks);
// 累积所有接收的chunk，提取region坐标
```

**Step 2: 清除视距范围内的缓存**
```java
clearXaeroCacheSelective();
// 删除视距范围内的 .xwmc 文件
```

**Step 3: 扫描新文件**
```java
MapSaveLoad.detectRegions(20);
// 让 Xaero 发现磁盘上的新 region 文件
```

**Step 4: 选择性重置内存状态**
```java
selectiveResetRegionLoadStates();
// 仅重置：更新region + 视距范围 + 玩家位置
// 跳过远离玩家的region，保留内存缓存
```

### loadState 重置原理

通过反射遍历 Xaero 的区域层级结构：

```
MapWorld → MapDimension → LayeredRegionManager
    ↓
MapLayer → LeveledRegionManager → regionTextureMap (2D Map)
    ↓
BranchLeveledRegion (level 3→1) → children[2][2] 递归
    ↓
MapRegion (level 0) → loadState 字段
```

对每个 `MapRegion` 执行：
- `loadState = 0` (从 LOADED 重置为 UNLOADED)
- `hasHadTerrain = false` (强制重新生成地形)

### 关键代码位置

| 功能 | 文件 | 方法 |
|-----|------|------|
| 同步触发 | [MapPacketReceiver.java](src/main/java/com/mapsyncer/client/MapPacketReceiver.java) | `handleSyncResponse()` |
| 数据写入 | [XaeroMapIntegrator.java](src/main/java/com/mapsyncer/client/XaeroMapIntegrator.java) | `writeMapDataAndReturnDir()` |
| 记录更新region | [XaeroMapIntegrator.java](src/main/java/com/mapsyncer/client/XaeroMapIntegrator.java) | `recordUpdatedRegions()` |
| 视距范围计算 | [XaeroMapIntegrator.java](src/main/java/com/mapsyncer/client/XaeroMapIntegrator.java) | `getViewDistanceRegions()` |
| 选择性缓存清除 | [MapPacketReceiver.java](src/main/java/com/mapsyncer/client/MapPacketReceiver.java) | `clearXaeroCacheSelective()` |
| 选择性状态重置 | [XaeroMapIntegrator.java](src/main/java/com/mapsyncer/client/XaeroMapIntegrator.java) | `selectiveResetRegionLoadStates()` |
| Chunk暂停 | [XaeroMapIntegrator.java](src/main/java/com/mapsyncer/client/XaeroMapIntegrator.java) | `disableChunkUpdates()` / `enableChunkUpdates()` |

## 开发

```bash
# 构建
./gradlew build

# 客户端测试
./gradlew runClient

# 服务端测试
./gradlew runServer
```

## 注意事项

- **客户端**：需要安装 Xaero's World Map 才能显示地图
- **服务端**：可独立运行，无需安装 Xaero 模组
- 服务端需先执行 `/mapsyncer generate` 生成地图缓存
- 同步期间客户端的 chunk 更新会被暂停
- 同步会覆盖客户端本地的地图数据（增量合并功能暂时禁用）

## 已知问题

| 问题 | 描述 |
|-----|------|
| 含水方块渲染异常 | 水下方块、含水方块（如海带、海草）的渲染存在异常 |
| 树木渲染缺失 | 某些树木类型的渲染存在缺失或不完整 |
| 水体色彩差异 | 服务端生成的水体颜色与客户端探索时记录的颜色有微小差异 |
| 视觉效果不一致 | 因 MCA 文件精度限制，无法实现与客户端原生探索完全一致的视觉效果 |
| Mod 方块颜色近似 | Mod 方块颜色使用启发式规则推断，可能与实际颜色有差异 |

## 增量更新机制

服务端支持三种增量更新模式：

### 1. 批量生成时的增量检测

执行 `/mapsyncer generate` 时自动检测变化：
- 使用时间戳缓存记录每个 MCA 文件的修改时间
- 只重新生成有变化的区域，跳过未变化的
- 处理新增区域（之前未生成过的）
- 缓存持久化到 `mca_timestamps.cache`

### 2. 定时周期更新（TICK 模式）

按固定 tick 间隔自动扫描更新：
- 每隔指定 tick 数自动扫描所有维度
- 检测并更新有变化的区域
- 默认间隔 200 ticks（10 秒），可配置 20-72000 ticks

### 3. 每日定时更新（SCHEDULED 模式）

在指定时间每日自动更新一次：
- 使用服务器本地时区
- 默认时间 04:00，可配置任意时刻
- 适合在服务器低负载时段自动更新

### 通用配置项

| 配置项 | 默认值 | 说明 |
|-------|-------|------|
| `enableDebugLogging` | `false` | 启用调试日志 |
| `maxConcurrentRegions` | `4` | 最大并发转换区域数 |
| `maxSyncPacketSize` | `1048576` | 最大同步包大小（字节） |
| `syncSpeedLimitKBps` | `0` | 同步限速（KB/s），0=不限速，建议 500-2000 |
| `enableResumeSync` | `true` | 启用断点续传功能 |

### 增量更新配置项

| 配置项 | 默认值 | 说明 |
|-------|-------|------|
| `incrementalUpdateMode` | `DISABLED` | 更新模式：DISABLED/TICK/SCHEDULED |
| `incrementalUpdateIntervalTicks` | `200` | TICK 模式间隔（1秒=20 ticks） |
| `scheduledUpdateHour` | `4` | SCHEDULED 模式小时（0-23） |
| `scheduledUpdateMinute` | `0` | SCHEDULED 模式分钟（0-59） |

### 命令控制

```bash
/mapsyncer incremental off                  # 禁用增量更新
/mapsyncer incremental tick                 # 启用 TICK 模式（使用配置的间隔）
/mapsyncer incremental tick <interval>      # 启用 TICK 模式并设置间隔
/mapsyncer incremental scheduled            # 启用 SCHEDULED 模式（使用配置的时间）
/mapsyncer incremental scheduled <hour>     # 启用 SCHEDULED 模式并设置小时
/mapsyncer incremental scheduled <hour> <minute>  # 启用 SCHEDULED 模式并设置完整时间
/mapsyncer incremental status               # 查看当前状态和下次更新时间
```

**注意**: 定时增量更新会定期扫描文件系统，建议根据服务器负载选择合适的模式。TICK 模式适合频繁更新，SCHEDULED 模式适合低负载时段更新。

## 暂时禁用的功能

以下功能已实现但暂时禁用，将在未来版本启用：

| 功能 | 说明 |
|-----|------|
| 增量合并 | 区块级别的增量合并，保留客户端已探索区块 |
| 完整性检查 | 检测region是否完全生成，跳过完整region |
| 同步按钮 | 在 Xaero UI 中的同步按钮 |

## 技术细节

### 方块属性解析

服务端使用 Minecraft API 查询方块属性，参考 Xaero WorldMap 的实现：

- **自动识别**：通过 `BuiltInRegistries.BLOCK` 获取方块定义
- **流体判断**：通过 `FluidState` 和 `LiquidBlock` 类识别水/熔岩
- **含水方块**：检查 `waterlogged` 属性，支持 mod 的含水方块
- **透明性判断**：使用 `AirBlock`、`TransparentBlock` 类和 `getLightBlock()` API
- **隐形方块**：使用 `RenderShape.INVISIBLE` + `BlockTags.FLOWERS` 标签 + 类判断
- **花识别**：使用 `BlockTags.FLOWERS` 标签 + `FlowerBlock`/`TallFlowerBlock` 类
- **发光判断**：使用 `getLightEmission()` API
- **问题方块处理**：记录 MapColor 抛出异常的方块，自动跳过

### 方块颜色映射

采用四层颜色获取策略：

1. **纹理颜色提取**（仅客户端）：从方块纹理图片提取平均颜色，最精确
2. **MapColor API**：使用 Minecraft 官方 MapColor 定义
3. **原版方块精确颜色**：保持与原版一致的视觉效果
4. **启发式规则**：基于名称模式推断（`_ore` → 金色、`_log` → 棕色、`_leaves` → 绿色等）

### 未知方块处理

参考 Xaero UnknownBlockState 实现：

- 对于未在注册表中找到的方块，创建 `UnknownBlockStateWrapper` 包装原始 NBT 数据
- 支持序列化写入文件，保存完整方块信息
- 后续可在客户端正确处理未知方块

### 独立 MCA 解析器

本项目的 MCA 解析器完全独立实现，不依赖 Minecraft API：

- **零依赖**：仅使用 Java 标准库（`java.io`、`java.util.zip`）
- **支持压缩**：GZIP、ZLIB、LZ4、无压缩
- **解析流程**：
  1. 读取位置表（32×32区块位置，每扇区4KB）
  2. 读取时间戳表（区块修改时间）
  3. 读取区块数据扇区
  4. 解压并解析 NBT 数据

```
MCA文件结构:
┌─────────────────────────────┐
│ 0-4KB:  位置表 (1024 entries)│
│ 4-8KB:  时间戳表             │
│ 8KB+:   区块数据扇区         │
└─────────────────────────────┘
```

### C2ME 线程安全保存

为了兼容 C2ME 等优化 mod，地图生成时的区块保存操作使用线程安全方式：

- 使用 `server.execute()` 将保存操作调度到主线程执行
- 异步线程等待保存完成（最多60秒）
- 避免触发 C2ME 的 `ConcurrentModificationException` 保护机制

详见 `.plan/` 目录下的文档：

- [client-sync-requirements.md](.plan/client-sync-requirements.md) - 客户端同步需求
- [mca-to-xaero-conversion.md](.plan/mca-to-xaero-conversion.md) - MCA到Xaero转换
- [Xaero未加载区块地图生成机制.md](.plan/Xaero未加载区块地图生成机制.md) - 未加载区块处理

## 许可证

MIT License

## 致谢

- [Xaero's World Map](https://www.curseforge.com/minecraft/mc-mods/xaeros-world-map) - 原版地图模组
- [Xaero's Minimap](https://www.curseforge.com/minecraft/mc-mods/xaeros-minimap) - 小地图模组
