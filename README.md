# MapSyncer for Xaero's World Map

一个 Minecraft 多平台地图同步模组，将服务端已探索区域同步到客户端的 Xaero's World Map。

> **适用场景**：玩家首次进入已开放服务器，或服务器已用 Chunky 预生成地图，需要将地图同步给玩家，减少重复跑图时间成本。

---

## 运行环境

### 平台支持

| 平台 | Minecraft 版本 | 加载器版本 | Java | 状态 |
|------|----------------|------------|------|------|
| **NeoForge** | 1.21.1 | 21.1+ | 21 | ✅ |
| **NeoForge** | 1.21.11 | 21.11+ | 21 | ✅ |
| **Forge** | 1.20.1 | 47+ | 17 | ✅ |
| **Forge** | 1.20.4 | 49+ | 17 | ✅ |
| **Forge** | 1.21.1 | 52+ | 21 | ✅ |
| **Fabric** | 1.20.1 | Fabric API 0.83+ | 17 | ✅ |
| **Fabric** | 1.20.4 | Fabric API 0.92+ | 17 | ✅ |
| **Fabric** | 1.21.1 | Fabric API 0.107+ | 21 | ✅ |

### 客户端依赖

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
| **增量更新** | 服务端可配置周期性/定时自动更新地图缓存 |
| **洞穴模式** | 支持从指定高度向下扫描，输出到 caves 子目录 |
| **多线程哈希** | 客户端 CRC32 计算支持并行，线程数可配置 |

---

## 命令清单

### 客户端命令

| 命令 | 说明 |
|------|------|
| `/mapsyncer` | 显示帮助 |
| `/mapsyncer sync` | 同步当前维度 |
| `/mapsyncer sync <维度>` | 同步指定维度 |
| `/mapsyncer sync all` | 同步所有维度 |

**维度参数支持**：
- 原版：`overworld`、`the_nether`、`the_end`
- Mod 维度：完整 ID，如 `twilightforest:twilight_forest`

### 服务端命令（需 OP 权限）

| 命令 | 说明 |
|------|------|
| `/mapsyncer generate` | 生成所有维度缓存 |
| `/mapsyncer generate <维度>` | 生成指定维度（增量模式） |
| `/mapsyncer generate <维度> <x> <z>` | 生成单个区域 |
| `/mapsyncer generate <维度> force` | 强制重新生成（清除缓存） |
| `/mapsyncer status` | 查看生成进度和缓存统计 |
| `/mapsyncer incremental off` | 禁用增量更新 |
| `/mapsyncer incremental tick [间隔]` | 启用周期更新（20-72000 ticks） |
| `/mapsyncer incremental scheduled [时] [分]` | 启用定时更新（默认 04:00） |

---

## 配置文档

### 客户端配置

| 配置项 | 默认值 | 范围 | 说明 |
|--------|--------|------|------|
| `hashThreads` | CPU 核心数/2 | 1~核心数 | CRC32 哈希计算并行线程数 |

### 服务端配置

配置文件位于 `config/mapsyncer-server.toml`。

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
| `incrementalUpdateIntervalTicks` | 200 | TICK 模式间隔（20 ticks = 1 秒） |
| `scheduledUpdateHour` | 4 | 定时更新小时（0-23） |
| `scheduledUpdateMinute` | 0 | 定时更新分钟（0-59） |

**维度扫描 `[dimension_scan]`**

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `default_scan_mode` | SURFACE | 未配置维度的默认扫描模式 |
| `default_cave_start` | 63 | CAVE 模式的起始高度 |

**维度配置格式**：
```toml
dimension_configs = [
    "minecraft:overworld|SURFACE|63|true|false|-64|384|384",
    "minecraft:the_nether|CAVE|63|false|true|0|256|256",
    "minecraft:the_end|SURFACE|63|false|false|0|256|256"
]
```

格式：`维度ID|扫描模式|洞穴起始高度|有天空光|有顶棚|minY|高度|逻辑高度`

- **SURFACE**：从高度图向下扫描，适用于主世界、末地，使用此模式会忽略洞穴高度
- **CAVE**：从固定高度向下扫描，适用于地狱

---

## 项目结构

```
libs/                   抽象库层（平台无关，编译为独立 JAR）
├── core/               纯 Java 核心：MCA/NBT 解析、工具类
└── platform-api/       平台抽象接口、网络 Payload 定义

shared/                 源码复用层（由平台模块 sourceSet 引用）
├── common/             全版本共享的 MC API 代码
├── mc-1.20/            MC 1.20.x 版本特定代码
└── mc-1.21/            MC 1.21.x 版本特定代码

platforms/              平台实现层（编译产出最终 mod JAR）
├── neoforge/           NeoForge 平台实现
├── forge/              Forge 平台实现
└── fabric/             Fabric 平台实现
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
   区域转换 (RegionConverter)
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
  <client>/xaero/world-map/Multiplayer_<IP>/
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

## 已知问题

| 问题 | 说明 | 影响 |
|------|------|------|
| 地狱区块竖线分割 | CAVE 模式渲染存在分割线 | 显示异常 |
| 洞穴内容异常 | 洞穴模式下部分内容不准确 | 显示异常 |
| 含水方块渲染 | 海带、海草等含水方块颜色异常 | 显示为深蓝色 |
| Mod 方块颜色 | 部分 Mod 方块颜色为近似值 | 可能与实际有偏差 |

---

## 快速上手

1. **服务端预生成**
   ```
   /mapsyncer generate
   ```

2. **客户端同步**
   ```
   /mapsyncer sync all
   ```

3. **配置增量更新（可选）**
   ```
   /mapsyncer incremental scheduled 4 0
   ```

---

## 构建

```bash
# 构建所有平台
./gradlew build -x test --parallel collectJars

# 构建单个平台
./gradlew :platforms:forge:1.21.1:build -x test

# 快捷脚本（scripts/fastbuild/）
build-all.bat                    # 构建全部
build-forge-1.21.1.bat           # 构建指定平台
```

产物输出到 `build/lib/`。

---

**许可证**：GPL-3.0

**致谢**：Xaero's World Map & Minimap
