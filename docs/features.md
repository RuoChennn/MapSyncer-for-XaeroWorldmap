# MapSyncer 功能实现文档

本文档列出了 MapSyncer for XaeroWorldmap 模组的功能实现状态。

**状态标记**: ✅ 已实现已测试 | 🧪 已实现未测试 | ⚠️ 已知问题 | 📝 规划中

---

## 一、核心功能

MapSyncer 是 Minecraft 多平台地图同步模组，将服务端已探索区域同步到客户端 Xaero's World Map。

**适用场景**: 玩家首次进入已开放服务器，或服务器已用 Chunky 预生成地图。

### 平台支持

| 平台 | Minecraft 版本 | 加载器版本 | Java | 状态 |
|------|----------------|------------|------|------|
| **Forge** | 1.20.1 | 47+ | 17 | ✅ |
| **Fabric** | 1.20.1 | Fabric API 0.83+ | 17 | ✅ |
| **Forge** | 1.21.1 | 52+ | 21 | ✅ |
| **Fabric** | 1.21.1 | Fabric API 0.107+ | 21 | ✅ |
| **NeoForge** | 1.21.1 | 21.1+ | 21 | ✅ |
| **Forge** | 1.21.11 | 61+ | 21 | ✅ |
| **Fabric** | 1.21.11 | Fabric API 0.141+ | 21 | ✅ |
| **NeoForge** | 1.21.11 | 21.1+ | 21 | ✅ |
| **Fabric** | 26.1 | Fabric API 0.149+ | 25 | ✅ |
| **NeoForge** | 26.1 | 26.1+ | 25 | ✅ |

### 架构分层

```
libs/               抽象库层（平台无关，编译为独立 JAR）
├── core/           纯 Java 核心（MCA/NBT 解析、工具类、MapPackager）
├── platform-api/   平台抽象接口、网络 Payload 定义
└── common/         客户端/服务端共享逻辑（同步、缓存、自动同步）

mc-1.20.1/          MC 1.20.1 版本
├── shared/         源码复用层（不独立编译，由平台模块 sourceSet 引用）
├── fabric/         平台实现层（编译产出最终 mod JAR）
└── forge/

mc-1.21.1/          MC 1.21.1 版本
├── shared/
├── fabric/
├── forge/
└── neoforge/

mc-1.21.11/         MC 1.21.11 版本
├── shared/
├── fabric/
├── forge/
└── neoforge/

mc-26.1/            MC 26.1 版本
├── shared/
├── fabric/
└── neoforge/
```

---

## 二、命令系统

### 服务端命令（需 OP 权限等级 4）

| 命令 | 状态 | 说明 |
|------|------|------|
| `/mapsyncer generate` | ✅ | 生成所有维度缓存 |
| `/mapsyncer generate <dim>` | ✅ | 生成指定维度（增量模式） |
| `/mapsyncer generate <dim> <x> <z>` | ✅ | 生成单个区域 |
| `/mapsyncer generate <dim> --force` | ✅ | 强制生成（清除缓存后重新生成） |
| `/mapsyncer status` | ✅ | 查看生成进度和缓存统计（区域数量、各维度大小） |
| `/mapsyncer incremental off` | ✅ | 关闭增量更新 |
| `/mapsyncer incremental tick [interval]` | ✅ | Tick 模式（interval: 20-72000 ticks） |
| `/mapsyncer incremental scheduled [hour] [minute]` | ✅ | 定时模式（hour: 0-23, minute: 0-59） |

### 客户端命令

| 命令 | 状态 | 说明 |
|------|------|------|
| `/mapsyncer sync` | ✅ | 同步当前维度 |
| `/mapsyncer sync <dim>` | ✅ | 同步指定维度（支持 `overworld`/`the_nether`/`the_end` 及 Mod 维度 ID） |
| `/mapsyncer sync all` | ✅ | 同步所有维度 |
| `/mapsyncer clearstate` | ✅ | 清除同步恢复状态（忽略断点续传） |

**维度自动补全**: 包含 `overworld`、`the_nether`、`the_end`、`all`、当前维度、所有注册的 Mod 维度、已有 Xaero 地图目录。

---

## 三、同步系统

### 传输优化

| 功能 | 状态 | 说明 |
|------|------|------|
| CRC32 哈希比对 | ✅ | 哈希一致跳过同步，流式计算避免内存峰值 |
| 时间戳比对 | ✅ | 客户端旧于服务端才同步 |
| 分批传输 | ✅ | 默认 256KB，可配置 64KB-1MB |
| 速率限制 | ✅ | 默认 1MiB/s，可配置（0=不限） |
| 断点续传 | ✅ | 中断后重连可恢复（基于哈希比对） |
| 流式加载 | ✅ | 边接收边写入 Xaero 目录，立即触发重载 |
| 带宽感知限速 | ✅ | 动态调整避免阻塞游戏网络 |
| 视距优先排序 | ✅ | 玩家视距范围内的区域优先传输 |

### Payload 分片传输

| 功能 | 状态 | 说明 |
|------|------|------|
| 双向自动分片 | ✅ | 所有 >28KB 的数据在发送前拆分为小包（~1KB each） |
| 接收端组装 | ✅ | 客户端自动识别分片并重新组装，支持乱序到达 |
| 版本隔离 | ✅ | 分片处理逻辑在所有 MC 版本的 shared 层统一实现 |

### 同步冲突防护

| 功能 | 状态 | 说明 |
|------|------|------|
| 同步进行中拒绝 | ✅ | 同步进行中时拒绝新请求，避免并发同步冲突 |
| 过期检测 | ✅ | 10 分钟超时自动清除残留状态 |
| 离线玩家清理 | ✅ | 60 秒周期检查并清理已离线玩家的同步状态 |

### 客户端增量更新功能

| 功能 | 状态 | 说明 |
|------|------|------|
| 双重比对 | ✅ | 哈希+时间戳选择性更新 |
| 时间戳缓存 | ✅ | 持久化（`sync_timestamps.cache`），秒级精度，服务器切换自动重初始化 |
| Chunk Update 控制 | ✅ | 同步时暂停 Xaero 区块写入 |
| 选择性重载 | ✅ | 仅重置视距范围内区域 |

### 服务端同步规则

1. 哈希匹配 → 跳过（内容相同）
2. 哈希不匹配 + 客户端时间戳较旧 → 同步
3. 哈希不匹配 + 客户端时间戳较新 → 跳过（客户端更新）
4. 客户端无元数据 → 同步（新区域）

---

## 四、地图生成系统

| 功能 | 状态 | 说明 |
|------|------|------|
| 全维度生成 | ✅ | 自动扫描所有已存在区块的维度 |
| Mod 维度支持 | ✅ | 支持 ResourceLocation 格式（暮光森林测试通过） |
| 洞穴模式 | ✅ | CAVE 模式从固定高度向下扫描 |
| 增量更新 | ✅ | TICK 周期模式 + SCHEDULED 定时模式 |
| 强制保存机制 | ✅ | 读取前调用 `server.saveEverything()` 确保数据一致性，兼容 C2ME |
| 并发转换 | ✅ | 可配置线程池（默认 4 线程，最大 16），线程使用 MIN_PRIORITY 降低对服务端 tick 的 CPU 争用 |
| 两遍处理 | ✅ | 第一遍处理时间戳变更区域，第二遍捕获新增区域 |
| 世界格式自适应 | ✅ | 自动检测新格式（MC 26.1+）和传统格式 |
| Xaero 路径自适应 | ✅ | 优先使用新版 `xaero/world-map`，旧版 `XaeroWorldMap` 目录存在时自动 fallback |
| 内置服务器支持 | ✅ | 检测到内置服务器时复用主机 Xaero 存档目录作为地图缓存 |
| 单独区域生成 | ✅ | 支持 `generate <dim> <x> <z>` 指定单个区域 |
| 高度图优先级 | ✅ | WORLD_SURFACE 优先，对齐 Xaero 的树冠表面计算 |

---

## 五、配置系统

**配置文件位置**：Forge → `world/serverconfig/mapsyncer-server.toml`（每个世界独立）；NeoForge → `config/mapsyncer-server.toml`；Fabric → `config/mapsyncer-server.properties`

### 客户端配置

| 配置项 | 类型 | 默认值 | 范围 | 说明 |
|--------|------|--------|------|------|
| `hashThreads` | int | CPU 核心数/2 | 1~核心数 | CRC32 哈希计算并行线程数 |

### 服务端配置

**通用设置:**

| 配置项 | 类型 | 默认值 | 范围 | 说明 |
|--------|------|--------|------|------|
| `enableDebugLogging` | boolean | false | -- | 地图生成调试日志 |
| `maxConcurrentRegions` | int | 4 | 1-16 | 并发区域转换线程数 |
| `maxSyncPacketSize` | int | 262144 (256KB) | 64KB-1MB | 单包最大字节数 |
| `syncSpeedLimitKBps` | int | 1024 (1MiB/s) | 0-10240 | 同步速率限制（0=不限） |

**增量更新设置:**

| 配置项 | 类型 | 默认值 | 范围 | 说明 |
|--------|------|--------|------|------|
| `incrementalUpdateMode` | UpdateMode | DISABLED | DISABLED/TICK/SCHEDULED | 增量更新触发模式 |
| `incrementalUpdateIntervalTicks` | int | 200 (10s) | 20-72000 | TICK 模式间隔 |
| `scheduledUpdateHour` | int | 4 | 0-23 | 定时模式小时 |
| `scheduledUpdateMinute` | int | 0 | 0-59 | 定时模式分钟 |

**维度扫描设置:**

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `default_scan_mode` | ScanMode | SURFACE | 未配置维度的默认扫描模式 |
| `default_cave_start` | int | 63 | CAVE 模式默认起始高度 |
| `dimension_configs` | List | 3 个原版预设 | 每维度扫描配置 |

维度配置格式: `"dimension|scan_mode|cave_start|dim_type_info"`，其中 `dim_type_info` 为 `"hasSkylight|hasCeiling|minY|height|logicalHeight"`

默认预设:
- 主世界: SURFACE, hasSkylight=true, hasCeiling=false, minY=-64, height=384
- 地狱: CAVE, hasSkylight=false, hasCeiling=true, minY=0, height=256
- 末地: SURFACE, hasSkylight=false, hasCeiling=false, minY=0, height=256

### 缓存常量 (`CacheConfig`)

| 常量 | 值 | 说明 |
|------|-----|------|
| MAX_REGION_META_CACHE | 50000 条 | 区域元数据缓存上限 |
| MAX_REGION_TIMESTAMP_CACHE | 50000 条 | 时间戳缓存上限 |
| MAX_BLOCK_COLOR_CACHE | 5000 条 | 方块颜色缓存上限 |
| MAX_BLOCK_PROPERTIES_CACHE | 10000 条 | 方块属性缓存上限 |

### 超时常量 (`TimeoutConfig`)

| 常量 | 值 | 说明 |
|------|-----|------|
| TASK_TIMEOUT_SECONDS | 60s | 单区域转换超时 |
| SAVE_TIMEOUT_MS | 60s | 区块保存超时 |
| STALE_SYNC_TIMEOUT_MS | 10min | 同步过期检测 |
| SERVER_RESPONSE_TIMEOUT_MS | 5s | 服务端响应超时 |

---

## 六、网络协议

6 种 Payload，全部平台无关:

| Payload | 方向 | 说明 |
|---------|------|------|
| `ServerInstalledPayload` | 服务端→客户端 | 玩家加入时通知服务端已安装 MapSyncer，包含最后地图生成时间和自动同步间隔 |
| `SyncRequestPayload` | 客户端→服务端 | 发送区域元数据（路径→时间戳+哈希）用于差异比对 |
| `SyncResponsePayload` | 服务端→客户端 | 传输区域数据批次（状态: ok/uptodate/no_cache/dim_not_available/in_progress） |
| `ChunkMapData` | 嵌入 SyncResponse | 单区域压缩地图数据，含 caveLayer 字段 |
| `SyncProgressPayload` | 服务端→客户端 | 进度更新（已处理/总数/状态），客户端以 Action Bar 形式显示 |
| `ClientMeta` | 嵌入 SyncRequest | 区域元数据: 秒级时间戳 + CRC32 哈希 |

### 握手保护

| 功能 | 状态 | 说明 |
|------|------|------|
| Forge 握手保护 | ✅ | 服务端检查客户端 mod 列表，未安装 MapSyncer 则跳过发送自定义 payload |
| NeoForge 双向握手 | ✅ | 握手阶段检查 `mods()` 返回值，未安装则拒发 payload |
| 重复注册防护 | ✅ | NetworkHandler 幂等防护，防止 payload 重复注册 |

---

## 七、维度路径映射

| 维度 | Minecraft ID | Xaero 目录 |
|------|--------------|------------|
| 主世界 | `minecraft:overworld` | `null` |
| 地狱 | `minecraft:the_nether` | `DIM-1` |
| 末地 | `minecraft:the_end` | `DIM1` |
| Mod 维度 | `namespace:path` | `namespace$path` |

---

## 八、文件存储结构

```
服务端: <server>/server_map_cache/
├── null/, DIM-1/, DIM1/           # 原版维度
├── namespace$path/                 # Mod 维度
├── caves/<layer>/                  # 洞穴模式输出
└── generation_cache.properties     # 时间戳+哈希缓存

客户端: <client>/xaero/world-map/Multiplayer_<serverIP>/  # 新版统一路径（优先）
       <client>/XaeroWorldMap/Multiplayer_<serverIP>/      # 旧版路径（兼容 fallback）
├── null/mw$<worldId>/             # 主世界
├── DIM-1/mw$<worldId>/            # 地狱
├── DIM1/mw$<worldId>/             # 末地
├── caves/<layer>/                 # 洞穴层
└── sync_timestamps.cache          # 同步时间戳缓存
```

---

## 九、MCA 解析系统

| 功能 | 状态 | 说明 |
|------|------|------|
| 独立解析器 | ✅ | 纯 Java，少量方块查询依赖 Minecraft API，不加载区块到内存 |
| GZIP/ZLIB | ✅ | 支持压缩类型 1、2 |
| NBT 解析 | ✅ | 全标签类型（0-12），嵌套结构，含大小限制防恶意数据 |
| 方块状态解析 | ✅ | 调色板、属性、位数组 |
| 生物群系解析 | ✅ | 4x4x4 voxel 格式 |
| 表面扫描 | ✅ | SURFACE/CAVE 模式，支持 WORLD_SURFACE 高度图优先 |
| Xaero 格式输出 | ✅ | 版本 6.8，TileChunk/Tile 结构 |
| MCA/MCR 兼容 | ✅ | 正则匹配 `r.<x>.<z>.mca/mcr` |

---

## 十、方块系统

| 功能 | 状态 | 说明 |
|------|------|------|
| 属性查询 | ✅ | isAir/isWater/isLava/isFluid/isTransparent/isInvisible 等 15 字段 |
| 含水检测 | ✅ | waterlogged 属性 + 名称匹配 |
| Mod 方块识别 | ✅ | 注册表 API、RenderShape、BlockTags |
| 颜色映射 | ✅ | MapColor API + 纹理提取 + 启发式规则 |
| 植物/花卉检测 | ✅ | isFlower/isPlant/isGrassBlock |
| 发光检测 | ✅ | isGlowing/lightBlock/lightEmission |
| 透明方块处理 | ✅ | checkTransparency 包含 SnowLayerBlock 等透明覆盖物 |
| 彩色玻璃处理 | ✅ | StainedGlassPaneBlock 作为 overlay 处理，普通玻璃保持隐形 |
| 平台适配 | ✅ | 通过 `BlockPropertyResolver` 桥接 MC API 和纯 Java 核心 |

---

## 十一、MapPackager — 离线地图打包工具

独立 CLI 工具（`libs/core/.../tool/MapPackager.java`），将服务器缓存目录打包为客户端可直接使用的 Xaero 地图 zip 包。

| 功能 | 状态 | 说明 |
|------|------|------|
| 维度扫描 | ✅ | 自动扫描 `server_map_cache/` 下所有维度目录 |
| Zip 打包 | ✅ | 按 `Multiplayer_<name>/<dim>/mw$<worldId>/` 路径结构组织 |
| 时间戳转换 | ✅ | `generation_cache.properties` → `sync_timestamps.cache` |
| World ID 检测 | ✅ | 自动从 `xaeromap.txt` 读取，支持手动指定 |
| CLI 参数 | ✅ | `-c` 缓存目录, `-o` 输出文件, `-s` 服务器名, `-w` World ID, `-d` 世界目录 |
| Gradle 构建 | ✅ | `./gradlew buildPackager` 输出到 `output/` 目录 |

用法: `java -jar mapsyncer-packager.jar -c ./cache -o ./output.zip`

---

## 十二、安全与稳定性

| 功能 | 状态 | 说明 |
|------|------|------|
| 并发保护 | ✅ | volatile、ConcurrentHashMap、锁 |
| 内存管理 | ✅ | 流式 CRC32 + 稀疏 overlay 存储 + 缓存上限 + 时间戳缓存无限增长修复 |
| NBT 大小限制 | ✅ | array/list/depth 限制防恶意数据（MAX_LIST_SIZE 100000） |
| 错误处理 | ✅ | 单区块失败不中断 |
| C2ME 兼容 | ✅ | 主线程调度保存 |
| 异步同步 | ✅ | 独立守护线程，不阻塞服务端主线程/Watchdog |
| 断开处理 | ✅ | 玩家断开/切维度立即中断同步线程 |
| 过期清理 | ✅ | 60 秒周期检查离线玩家残留状态 |
| 服务器重启安全 | ✅ | 关闭时清理线程池、重置单例缓存、清除静态缓存 |
| 光照双重存储修复 | ✅ | overlay 数据不重复存储，避免 OOM |

---

## 十三、客户端特性

| 功能 | 状态 | 说明 |
|------|------|------|
| 同步进度显示 | ✅ | Action Bar 动态刷新百分比 + 完成后总耗时 |
| 断点续传提示 | ✅ | 重连后检测未完成同步，显示可点击的继续/忽略按钮 |
| **自动同步** | ✅ | 加入服务器时自动比对服务端地图生成时间，根据服务端增量更新策略自动计算同步间隔（DISABLED→禁用, TICK→≥60min, SCHEDULED→24h），静默完成 |
| 流式写入 | ✅ | 边接收边写入 Xaero 目录，每区域写入后清除 `.xwmc` 缓存并触发重载 |
| 视距优先 | ✅ | 优先加载视距范围内区域 |
| 过期检测 | ✅ | 10 分钟超时自动清除累积数据 |
| 多线程哈希 | ✅ | ForkJoinPool 并行计算 CRC32，线程数可配置 |
| Xaero 路径兼容 | ✅ | 优先新版 `xaero/world-map`，旧版 `XaeroWorldMap` 目录存在时自动 fallback |
| 单机目录命名 | ✅ | 对齐 Xaero 单机目录命名，使用存档文件夹名 |
