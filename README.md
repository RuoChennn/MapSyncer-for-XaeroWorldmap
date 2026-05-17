# MapSyncer-for-XaeroWorldmap

一个完全由AI编写的 Minecraft NeoForge 1.21.1 模组，用于将服务端已探索的区域地图数据同步到客户端的 Xaero's World Map。

## 说在前头

虽然生成的文件可以被客户端的XaeroWorldmap读取，但是从实现方式上来说存在一定差异，例如部分mod中的方块在地图上的颜色与客户端直接生成的不符，部分水草会被错误的渲染成地表；如果是非常大的地图同步和生成仍然需要大量的时间，但是这个mod从设计思路上来说是为了玩家初次进一个开放很久的服务器，或者服务器用chunky进行的地图预生成，所以同步的频率并不会很高，而且也有对同步和生成做一定限制，大部分情况下应该不会出太多问题，测试只针对了主世界进行，其他维度和mod维度因为不在我的设计目标内，所以暂时没有做测试（有问题提issue再慢慢修吧，欸嘿~

## 已知问题

| 问题 | 描述 |
|-----|------|
| 含水方块渲染异常 | 水下方块、含水方块（如海带、海草）的渲染存在异常 |
| 树木渲染缺失 | 某些树木类型的渲染存在缺失或不完整 |
| 水体色彩差异 | 服务端生成的水体颜色与客户端探索时记录的颜色有微小差异 |
| Mod 方块颜色近似 | Mod 方块颜色使用启发式规则推断，可能与实际颜色有差异 |
| 同步功能测试量不足 | 目前我只在本地和自己的服务器完成了测试 |

## 计划中但暂未实现的功能

| 功能 | 说明 |
|-----|------|
| 增量合并 | 区块级别的增量合并，保留客户端已探索区块，除非强制更新 |
| 完整性检查 | 检测region是否完全生成，跳过完整region |


## 功能特性

- **独立 MCA 解析器**：仅使用 Java 标准库解析 `.mca` 文件，支持 GZIP/ZLIB/LZ4 压缩，（mod方块会调用mcapi解析颜色和类型）
- **时间戳比对同步**：只同步服务端比客户端更新的区域，尽可能不覆盖客户端原生生成的数据
- **选择性重载**：同步完成后只重置玩家视距范围内的区域，减少性能开销
- **分批传输 + 限速**：按 1MB 分批传输，可配置速率限制，避免玩家超时掉线
- **断点续传**：玩家断线重连后可从上次中断位置继续，无需重新开始
- **同步保护**：玩家断开连接或跨维度时自动中止同步
- **进度显示**：实时显示同步进度
- **增量更新**：支持批量检测/TICK周期/SCHEDULED定时三种增量更新模式
- **多维度支持**：支持原版维度及任意 mod 维度
- **Mod 方块兼容**：自动识别 mod 方块的透明性、流体状态、含水属性、发光特性等
- **方块颜色四层策略**：纹理提取 → MapColor API → 原版精确颜色 → 启发式规则
- **C2ME 兼容**：保存操作自动调度到主线程，避免并发冲突

## 环境要求

- Minecraft 1.21.1
- NeoForge 21.1.77+
- Java 21

**客户端额外要求**：
- Xaero's World Map 1.40.11+
- Xaero's Minimap 25.3.10+（可选，推荐安装）

**服务端**：无需安装 Xaero 模组，可独立运行

## 安装

### 1. 安装 Xaero's World Map（客户端）

从 CurseForge 下载：
- [Xaero's World Map](https://www.curseforge.com/minecraft/mc-mods/xaeros-world-map)
- [Xaero's Minimap](https://www.curseforge.com/minecraft/mc-mods/xaeros-minimap)

> Xaero 模组仅在客户端需要。服务端可能需要手动在 world 目录下创建 `xaero.txt` 存放世界 ID。

### 2. 安装本模组

将 jar 文件放入客户端或服务端的 `mods` 目录。


## 使用方法

### 命令

**客户端命令** `/mapsyncer`：

| 命令 | 说明 |
|------|------|
| `/mapsyncer sync` | 同步当前维度 |
| `/mapsyncer sync overworld` | 同步主世界 |
| `/mapsyncer sync nether` | 同步下界 |
| `/mapsyncer sync end` | 同步末地 |
| `/mapsyncer sync all` | 同步所有维度 |

**服务端命令** `/mapsyncer`（需要 OP 权限）：

| 命令 | 说明 |
|------|------|
| `/mapsyncer generate` | 生成所有维度地图缓存 |
| `/mapsyncer generate <dimension>` | 生成指定维度缓存 |
| `/mapsyncer generate --region <x> <z>` | 生成单个区域 |
| `/mapsyncer status` | 查看生成进度 |
| `/mapsyncer incremental off` | 禁用增量更新 |
| `/mapsyncer incremental tick [interval]` | TICK 模式（周期更新） |
| `/mapsyncer incremental scheduled [hour] [minute]` | SCHEDULED 模式（每日定时） |
| `/mapsyncer incremental status` | 查看增量更新状态 |

### 快速开始

1. **服务端预生成**：执行 `/mapsyncer generate` 将已探索区块转换为 Xaero 格式
2. **客户端同步**：加入服务器后执行 `/mapsyncer sync`，自动比对时间戳并同步更新的区域

### 配置

配置文件位于 `config/mapsyncer-common.json`：

| 配置项 | 默认值 | 说明 |
|-------|-------|------|
| `syncSpeedLimitKBps` | `0` | 同步限速（KB/s），0=不限速，建议 500-2000 |
| `enableResumeSync` | `true` | 启用断点续传 |
| `maxSyncPacketSize` | `1048576` | 最大同步包大小（字节） |
| `maxConcurrentRegions` | `4` | 最大并发转换区域数 |
| `enableDebugLogging` | `false` | 启用调试日志 |
| `incrementalUpdateMode` | `DISABLED` | 增量更新模式：DISABLED/TICK/SCHEDULED |
| `incrementalUpdateIntervalTicks` | `200` | TICK 模式间隔（1秒=20 ticks） |
| `scheduledUpdateHour` | `4` | SCHEDULED 模式小时（0-23） |
| `scheduledUpdateMinute` | `0` | SCHEDULED 模式分钟（0-59） |

## 文件存储结构

### 服务端缓存目录

```
<server>/server_map_cache/
├── overworld/
│   ├── -1_-1.zip
│   └── 0_0.zip
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
│   │   └── mw$<worldId>/
│   │       ├── -1_-1.zip
│   │       ├── 0_0.zip
│   │       └── cache/        ← 地表渲染缓存 (.xwmc)
│   ├── DIM-1/             (下界)
│   └── DIM1/              (末地)
└── world/                 (单人世界)
```

## 项目结构

```
src/main/java/com/mapsyncer/
├── MapSyncer.java                      # 主类
├── client/                             # 客户端模块
│   ├── ClientHashManager.java          # 本地文件时间戳计算
│   ├── MapPacketReceiver.java          # 网络包接收 + 刷新地图
│   ├── MapSyncerCommand.java           # 同步命令处理
│   ├── SyncProgressTracker.java        # 进度追踪
│   ├── SyncProgressBarRenderer.java    # 进度条渲染
│   └── XaeroMapIntegrator.java         # Xaero集成 + 数据写入 + loadState重置
├── server/                             # 服务端模块
│   ├── ServerSyncHandler.java          # 同步处理 + 分批传输
│   ├── ConversionOrchestrator.java     # MCA转换协调
│   ├── RegionScanner.java              # 区域扫描
│   ├── XaeroWriter.java                # Xaero格式写入
│   ├── CacheGenerateCommand.java       # 缓存生成命令
│   ├── IncrementalUpdateHandler.java   # 增量更新
│   └── BlockPropertyResolver.java      # 方块属性解析
├── mca/                                # MCA文件解析
│   ├── McaReader.java                  # MCA文件读取
│   ├── ChunkDataParser.java            # 区块数据解析
│   └── ChunkSectionParser.java         # 区块段解析
├── nbt/                                # NBT解析
│   ├── NbtReader.java
│   └── Tag.java
├── network/                            # 网络协议
│   ├── PacketHandler.java
│   └── ChunkMapData.java
├── config/
│   └── ModConfig.java
└── util/
    └── BlockColorMapper.java           # 方块颜色映射
```

## 网络协议

| 数据包 | 方向 | 说明 |
|-------|------|-----|
| SyncRequestPayload | C→S | 发送本地region时间戳列表 |
| SyncResponsePayload | S→C | 返回region数据（分批，isComplete标记最后一批） |
| SyncProgressPayload | S→C | 同步进度更新 |

**时间戳比对逻辑**：客户端时间戳 ≥ 服务端 → 跳过；客户端时间戳 < 服务端 → 同步；客户端无时间戳 → 同步（新区域）

### 同步流程

```
客户端                                    服务端
  │                                        │
  │  执行 /mapsyncer sync                   │
  │  禁用 chunk 更新                        │
  │  发送本地region时间戳                   │
  │                                        │ ──── SyncRequestPayload ────> │
  │                              比对时间戳 │
  │                              分批打包   │
  │ <─── SyncResponsePayload (数据) ───── │
  │ <─── SyncProgressPayload (进度) ───── │
  │                                        │
  │  直接写入过时文件                       │
  │  清除缓存 + 选择性重载                   │
  │  恢复 chunk 更新                        │
```


## 增量更新机制

服务端支持三种增量更新模式：

- **批量检测**：`/mapsyncer generate` 时自动跳过未变化的 MCA 文件
- **TICK 模式**：按固定 tick 间隔自动扫描所有维度
- **SCHEDULED 模式**：每日指定时间自动更新一次

> TICK 模式适合频繁更新，SCHEDULED 模式适合低负载时段更新。

## 开发

```bash
./gradlew build       # 构建
./gradlew runClient   # 客户端测试
./gradlew runServer   # 服务端测试
```

## 许可证

GPL-3.0 License

## 致谢

- [Xaero's World Map](https://www.curseforge.com/minecraft/mc-mods/xaeros-world-map) - 原版地图模组
- [Xaero's Minimap](https://www.curseforge.com/minecraft/mc-mods/xaeros-minimap) - 小地图模组
