# MapSyncer 功能实现文档

本文档详细列出了 MapSyncer for XaeroWorldmap 模组的全部功能及其实现与测试状态。

**状态标记说明**：
- ✅ 已实现已测试 | 🧪 已实现未测试 | ⏳ 未实现/部分实现 | 📝 规划中 | ⚠️ 已知问题

---

## 一、核心功能概述

MapSyncer 是一个 Minecraft NeoForge 1.21.X 模组，核心功能是将服务端已探索的区域地图数据同步到客户端的 Xaero's World Map。

### 设计目标
- 服务端地图预生成后同步到客户端
- 适用于玩家初次进入已开放很久的服务器
- 适用于服务器使用 Chunky 预生成地图的场景
- 减少客户端手动探索的时间

---

## 二、服务端功能

### 2.1 地图缓存生成系统

| 功能 | 状态 | 描述 |
|------|------|------|
| 全维度生成 | ✅ | 自动扫描所有已加载维度，转换 MCA 到 Xaero 格式 |
| 单维度生成 | ✅ | 支持 `overworld`/`nether`/`end` 及完整维度 ID |
| Mod 维度生成 | 🧪 | 支持 mod 维度 ResourceLocation 格式（理论支持） |
| 单区域生成 | ✅ | 指定坐标生成单个区域，用于测试或针对性更新 |
| 强制保存机制 | ✅ | 生成前强制保存区块，兼容 C2ME |
| 维度目录建议 | 🧪 | 动态列出已加载维度作为命令建议 |

### 2.2 增量更新系统

| 功能 | 状态 | 描述 |
|------|------|------|
| 批量检测模式 | ✅ | 自动检测未变化 MCA，跳过已处理区域 |
| TICK 周期模式 | ✅ | 可配置 tick 间隔自动扫描（默认 200 ticks） |
| SCHEDULED 定时模式 | ✅ | 每日指定时间自动更新（默认 04:00） |
| 时间戳缓存 | ✅ | MCA/生成时间戳持久化，秒级精度 |

### 2.3 同步处理系统

| 功能 | 状态 | 描述 |
|------|------|------|
| CRC32 哈希比对 | ✅ | 哈希一致跳过同步，避免重复传输 |
| 时间戳比对 | ✅ | 客户端旧于服务端才同步 |
| 分批传输 | ✅ | 默认 1MB 分批，可配置 64KB-10MB |
| 速率限制 | ✅ | 可配置 KB/s 限制，避免网络拥塞 |
| 断点续传 | ✅ | 断线重连可从中断位置继续 |
| 同步保护 | ✅ | 断开/跨维度自动中止，保留进度 |
| World ID 读取 | ✅ | 从 `xaeromap.txt` 读取，支持多世界 |
| 维度过滤 | 🧪 | 仅同步客户端请求的维度 |

### 2.4 服务端命令系统

| 命令 | 状态 | 功能 |
|------|------|------|
| `/mapsyncer generate` | ✅ | 生成所有维度缓存 |
| `/mapsyncer generate <dim>` | ✅ | 生成指定维度（增量模式） |
| `/mapsyncer generate <dim> <x> <z>` | ✅ | 生成单个区域 |
| `/mapsyncer generate <dim> force` | ✅ | 强制生成（无视缓存） |
| `/mapsyncer status` | ✅ | 查看生成进度 |
| `/mapsyncer incremental off/tick/scheduled/status` | ✅ | 增量更新控制 |

---

## 三、客户端功能

### 3.1 同步命令系统

| 命令 | 状态 | 功能 |
|------|------|------|
| `/mapsyncer sync` | ✅ | 同步当前维度 |
| `/mapsyncer sync <dim>` | ✅ | 同步指定维度 |
| `/mapsyncer sync all` | ✅ | 同步所有维度 |
| 维度名称别名 | ✅ | 支持 `overworld`/`null`/`nether`/`dim-1` 等 |
| Mod 维度同步 | 🧪 | 支持任意维度 ID |
| 维度目录建议 | ✅ | 动态扫描 Xaero 目录列出已有维度 |

### 3.2 元数据计算系统

| 功能 | 状态 | 描述 |
|------|------|------|
| 时间戳计算 | ✅ | 本地文件修改时间，秒级精度 |
| CRC32 哈希 | ✅ | 并行计算（限制 2 线程避免卡顿） |
| 时间戳缓存 | ✅ | `sync_timestamps.cache` 持久化 |

### 3.3 数据接收与处理

| 功能 | 状态 | 描述 |
|------|------|------|
| 分批接收 | ✅ | 累积区域数据用于选择性刷新 |
| 增量比对 | ✅ | 双重比对（哈希+时间戳）选择性更新 |
| Chunk Update 控制 | ✅ | 同步时暂停 Xaero 区块写入 |
| 地图重载触发 | ✅ | 同步完成后刷新地图 |

### 3.4 Xaero 集成

| 功能 | 状态 | 描述 |
|------|------|------|
| 服务器 IP 定位 | ✅ | 自动识别服务器地址，处理 IPv4/IPv6/端口 |
| 维度目录映射 | ✅ | 主世界→`null`，地狱→`DIM-1`，末地→`DIM1` |
| 文件写入 | ✅ | 临时文件+原子替换，自动创建目录 |
| 选择性重载 | ✅ | 仅重置视距范围内区域 |
| 缓存清理 | ✅ | 清理 `.xwmc` 渲染缓存 |
| 反射操作 | ✅ | 操作 `WorldMapSession`、`MapProcessor` 等内部类 |

### 3.5 进度追踪

| 功能 | 状态 | 描述 |
|------|------|------|
| 进度显示 | ✅ | 聊天栏显示百分比、区域数量、耗时 |
| 状态追踪 | ✅ | 实时追踪，支持状态字符串 |

---

## 四、MCA 文件解析系统

### 4.1 独立解析器

| 功能 | 状态 | 描述 |
|------|------|------|
| 零依赖设计 | ✅ | 纯 Java 标准库，无 Minecraft API 依赖 |
| 文件格式解析 | ✅ | 位置表、时间戳表、区块数据扇区 |
| GZIP/ZLIB 压缩 | ✅ | 支持类型 1、2 |
| LZ4 压缩 | ⏳ | 检测但不支持，抛出提示异常 |
| 区块位置计算 | ✅ | 32x32 网格索引、偏移扇区计算 |

### 4.2 NBT 解析

| 功能 | 状态 | 描述 |
|------|------|------|
| 全标签类型 | ✅ | TAG_End 到 TAG_LongArray（0-12） |
| 嵌套结构 | ✅ | Compound、List 嵌套 |
| 大端字节序 | ✅ | UTF-8 字符串、长数组位数组 |

### 4.3 区块数据解析

| 功能 | 状态 | 描述 |
|------|------|------|
| 高度图解析 | ✅ | `WORLD_SURFACE` 类型，计算扫描起始高度 |
| 区块段解析 | ✅ | 16x16x16 section，Y 范围 -64 到 320 |
| 方块状态解析 | ✅ | `block_states` 调色板、属性、位数组 |
| 生物群系解析 | ✅ | 4x4x4 voxel 格式，边界平滑 |
| 光照数据解析 | ✅ | BlockLight/SkyLight nibble 数组 |

### 4.4 表面扫描与转换

| 功能 | 状态 | 描述 |
|------|------|------|
| 扫描策略 | ✅ | 从高度图向下，section Y 从高到低 |
| 表面识别 | ✅ | 含水→流体→隐形→透明→实体方块 |
| 单方块 palette | ✅ | 无 data 数组 section 处理 |
| Xaero 格式输出 | ✅ | 版本 6.8，TileChunk/Tile 结构 |
| 调色板系统 | ✅ | 方块/生物群系动态索引，压缩策略 |
| Overlay 机制 | ✅ | 多层叠加，opacity 累加 |
| 光照模式切换 | 🧪 | SURFACE（地表）/CAVE（洞穴/地狱） |

---

## 五、方块属性与颜色系统

### 5.1 属性查询

| 功能 | 状态 | 描述 |
|------|------|------|
| 基础属性 | ✅ | `isAir`/`isWater`/`isTransparent`/`isInvisible` 等 |
| 植物检测 | ✅ | 30+ 种植物方块基类检查 |
| 含水检测 | ✅ | `waterlogged` 属性+名称模式匹配 |
| Mod 方块自动识别 | ✅ | 注册表 API、RenderShape、BlockTags |
| 问题方块处理 | ✅ | MapColor 异常记录，启发式兜底 |
| 属性缓存 | ✅ | ConcurrentHashMap 缓存查询结果 |

### 5.2 颜色映射

| 功能 | 状态 | 描述 |
|------|------|------|
| 纹理颜色提取 | ✅ | 客户端：上表面纹理平均颜色 |
| MapColor API | ✅ | `state.getMapColor()`，ID 到 RGB 映射 |
| 原版精确颜色 | ✅ | 硬编码常见方块颜色 |
| 启发式规则 | ✅ | 名称模式推断（矿石→金色，树叶→绿色等） |

---

## 六、网络协议系统

| 功能 | 状态 | 描述 |
|------|------|------|
| SyncRequestPayload | ✅ | 客户端→服务端：时间戳+哈希 Map |
| SyncResponsePayload | ✅ | 服务端→客户端：区域数据+worldId+完成标记 |
| SyncProgressPayload | ✅ | 服务端→客户端：进度+总数+状态 |
| ChunkMapData 编码 | ✅ | 坐标+维度+压缩数据 |
| StreamCodec 实现 | ✅ | NeoForge 自定义 encode/decode |
| 协议注册 | ✅ | PayloadRegistrar，optional 双向通道 |

---

## 七、配置系统

| 配置项 | 状态 | 默认值 | 说明 |
|--------|------|--------|------|
| `syncSpeedLimitKBps` | ✅ | 0 | 同步速率限制 |
| `enableResumeSync` | ✅ | true | 断点续传开关 |
| `maxSyncPacketSize` | ✅ | 1MB | 最大同步包大小 |
| `maxConcurrentRegions` | ✅ | 4 | 最大并发转换数 |
| `enableDebugLogging` | ✅ | false | 调试日志开关 |
| `incrementalUpdateMode` | ✅ | DISABLED | 增量更新模式 |
| `incrementalUpdateIntervalTicks` | ✅ | 200 | TICK 模式间隔 |
| `scheduledUpdateHour/Minute` | ✅ | 4/0 | 定时模式时间 |

---

## 八、文件存储结构

### 8.1 服务端缓存目录

```
<server>/server_map_cache/
├── null/                         # 主世界 ✅
├── DIM-1/                        # 下界 ✅
├── DIM1/                         # 末地 ✅
├── <mod_dimension>/              # Mod 维度 🧪
├── mca_timestamps.cache          # MCA 时间戳 ✅
└── generation_cache.properties   # 生成时间戳+哈希 ✅
```

### 8.2 客户端地图目录

```
<client>/xaero/world-map/
├── Multiplayer_<serverIP>/       ✅
│   ├── null/mw$<worldId>/        ✅ 主世界
│   ├── DIM-1/                    ✅ 下界
│   └── DIM1/                     ✅ 末地
└── world/                        ⚠️ 单人世界未测试
```

### 8.3 缓存文件格式

| 格式 | 状态 | 描述 |
|------|------|------|
| 时间戳缓存（旧） | ✅ | 纯文本，`<路径>: <时间戳毫秒>` |
| 生成缓存（新） | ✅ | Properties，`<路径> = <秒>:<CRC32>` |
| 区域 ZIP 文件 | ✅ | 单个 `region.xaero`，标准 ZIP 压缩 |

---

## 九、安全与稳定性

| 功能 | 状态 | 描述 |
|------|------|------|
| 并发保护 | ✅ | volatile、ConcurrentHashMap、锁、Atomic |
| 错误处理 | ✅ | 单区块失败不中断，异常捕获记录 |
| 内存管理 | ✅ | 服务器停止清理缓存，避免泄漏 |
| C2ME 兼容 | ✅ | 主线程调度保存操作 |

---

## 十、已知问题与限制

### 10.1 渲染差异

| 问题 | 状态 | 描述 |
|------|------|------|
| 含水方块渲染 | ⚠️ | 水下方块、含水方块存在差异 |
| 树木渲染 | ⚠️ | 某些树木类型不完整 |
| 水体色彩 | ⚠️ | 服务端生成与客户端略有差异 |
| Mod 方块颜色 | ⚠️ | 启发式推断可能不准确 |
| Biome 精度 | ⚠️ | MCA 4x4x4 精度，低于原生（不修复） |

### 10.2 功能限制

| 限制 | 状态 | 说明 |
|------|------|------|
| LZ4 压缩 | ⏳ | 不支持 |
| 大地图耗时 | ⚠️ | 同步需要时间，增量较快 |
| 单人世界 | 🧪 | 主要针对多人服务器 |
| Mod 维度 | 🧪 | 理论支持，建议小范围测试 |
| 其他维度测试 | 🧪 | 主要测试主世界 |

---

## 十一、技术实现亮点

| 亮点 | 状态 | 描述 |
|------|------|------|
| 独立解析器 | ✅ | 纯 Java，无 API 依赖，GZIP/ZLIB 支持 |
| Xaero 格式兼容 | ✅ | 版本 6.8，调色板压缩，Overlay 累加 |
| 网络优化 | ✅ | 分批传输、速率限制、断点续传 |
| 客户端集成 | ✅ | 反射操作，选择性重载，缓存清理 |
| Mod 方块支持 | ✅ | 自动识别，RenderShape/Tags 检测 |
| 增量同步 | ✅ | CRC32 哈希比对，保留客户端探索成果 |

---

## 十二、未来规划

| 功能 | 状态 | 描述 |
|------|------|------|
| 区块级智能合并 | 📝 | 完善合并逻辑，强制更新选项 |
| 完整性检查优化 | 📝 | 数据验证，部分区块补全 |

---

**文档版本**: 2.0
**最后更新**: 2026-05-19
**模组版本**: MapSyncer for XaeroWorldmap NeoForge 1.21.X

---

### 历史更新记录

**2026-05-19 之前的更新**:
- feat(client): 客户端 sync 指令支持动态列出已有维度数据
- feat(client): 支持同步任意 mod 维度名称
- feat(server): 改进 generate 指令结构，支持 `<dim> [x] [z]` 和 `force` 参数
- feat(server): 动态列出所有已加载维度作为指令建议（支持 mod 维度）
- docs: 标记 mod 维度生成功能为未测试状态
- feat(client): 实现客户端时间戳缓存与精准地图刷新机制
- feat(server): 在转换日志中显示空MCA文件计数和总region数
- feat(server): 扩展植物方块基类检查支持更多类型
- fix(memory): 修复多处潜在内存溢出问题
- perf(client): 限制哈希计算并行度为2避免卡住游戏
- fix(client): 修正客户端哈希缓存路径格式匹配服务端
- refactor(cache): 改用 Properties 格式保存缓存文件，时间戳精度改为秒级

---

### 🧪 2026-05-19 更新（未测试）

- 🧪 refactor(server): **服务端缓存目录使用 Xaero 格式命名**
  - 服务端 `server_map_cache/` 目录结构与客户端 Xaero 目录保持一致
  - 主世界: `server_map_cache/null/`（与客户端 `null/` 对应）
  - 地狱: `server_map_cache/DIM-1/`（与客户端 `DIM-1/` 对应）
  - 末地: `server_map_cache/DIM1/`（与客户端 `DIM1/` 对应）
  - GenerationCache 键格式改为 Xaero 格式
  - 客户端元数据键发送 Xaero 格式（与服务端缓存路径一致）
  - 便于管理和调试，服务端与客户端目录命名统一

- 🧪 feat(server): 地狱维度使用分层洞穴模式（CAVE mode），起始高度 Y=90
  - RegionConverterStandalone 支持 CaveModeParams 参数
  - ConversionOrchestrator 根据维度类型选择光照模式和洞穴参数
  - 地狱使用 LightMode.CAVE，其他维度使用 LightMode.SURFACE
- 🧪 refactor: 创建统一的 DimensionPathMapping 维度路径映射类
  - 支持文件系统目录、Xaero 目录、ResourceLocation path 双向转换
  - 原版维度映射：the_nether → DIM-1, the_end → DIM1, overworld → null
  - 支持 Mod 维度动态注册映射（namespace:path → namespace$path）
- 🧪 fix(server): 修复地狱维度路径映射问题
  - RegionScanner 使用 DimensionPathMapping 获取正确目录名
  - 服务端 generate 正确写入到 DIM-1 目录
- 🧪 fix(server): generate 命令使用规范化维度名称
  - 新增 getFriendlyName() 方法
  - 命令建议和输出消息使用规范化名称（the_nether, the_end, overworld）
  - 移除 minecraft: 前缀显示
- 🧪 fix(server): 同步时根据客户端请求的维度过滤
  - 从客户端元数据键中提取请求的维度列表
  - 只处理客户端请求的维度的 region 文件
  - 避免同步客户端未请求的维度
- 🧪 fix(client): 单维度同步时不再 fallback 到所有维度
  - 客户端同步指定维度时只扫描该维度的目录
  - 移除 fallback 到 baseDir 的逻辑
  - 客户端无本地数据时发送 placeholder 标识请求维度
- 🧪 fix(server): 维度不存在提示（多语言支持）
  - 检查请求维度的缓存目录是否存在
  - 不存在时提示客户端需要先生成
  - 新增翻译键：mapsyncer.sync.dimension_not_available
- 🧪 debug(client): 添加 getCurrentServerBaseDirectory 调试日志
  - 输出 connection 和 serverData 状态
  - 便于排查地图目录获取失败问题

**注意**: 此次更新后，旧的 `server_map_cache/` 目录结构不再兼容，需要删除旧缓存并重新运行 `/mapsyncer generate`

---

### 🧪 2026-05-19 更新（未测试）- 洞穴模式配置系统

- 🧪 feat(config): **维度扫描模式配置系统**
  - 新增 `ScanMode` 枚举：SURFACE（地表模式）/ CAVE（洞穴模式）
  - 新增 `DimensionScanConfig` record：维度扫描配置记录
  - 配置文件支持维度列表配置，每个维度可指定 scan_mode 和 cave_start
  - **新增 `region_folder` 字段**：指定 MCA 文件存放目录，适配 mod 修改维度 ID 后的文件路径
  - SURFACE 模式时 cave_start 被忽略
  - 配置示例：
    ```toml
    [[dimension_scan.dimension_configs]]
        dimension = "minecraft:the_nether"
        region_folder = "DIM-1"      # 可选：指定 MCA 文件存放目录
        scan_mode = "CAVE"
        cave_start = 120             # 基岩天花板
    ```

- 🧪 feat(protocol): **网络协议扩展支持洞穴层信息**
  - ChunkMapData 新增 `caveLayer` 字段（Integer.MAX_VALUE 表示地表）
  - encode/decode 使用标记位实现向后兼容
  - 旧客户端缺少 caveLayer 字段时默认为地表层

- 🧪 feat(storage): **服务端 caves/<layer> 目录结构支持**
  - ConversionOrchestrator 从配置读取维度扫描配置
  - 洞穴模式地图存放至 `caves/<layer>/` 子目录
  - layer 计算：caveStart >> 4（支持负高度，如 -64 → layer -4）
  - GenerationCache relativePath 格式包含 caves 层信息

- 🧪 feat(sync): **服务端同步解析洞穴层路径**
  - ServerSyncHandler 解析 `dim/caves/layer/regionX_regionZ` 格式路径
  - 发送 ChunkMapData 包含正确的 caveLayer 信息

- 🧪 feat(client): **客户端 caves/<layer> 目录写入支持**
  - XaeroMapIntegrator 根据 caveLayer 写入正确目录
  - 地表：`mw$<worldId>/<regionX_regionZ>.zip`
  - 洞穴：`mw$<worldId>/caves/<layer>/<regionX_regionZ>.zip`
  - RegionCoord record 新增 caveLayer 字段

- 🧪 feat(cache): **客户端缓存路径解析支持 caves 层**
  - ClientHashManager.buildRelativePath 处理 `caves/<layer>` 目录
  - 时间戳缓存路径格式与服务端 GenerationCache 一致

**配置文件位置**: `config/mapsyncer-server.toml`

**配置示例**（新增 `region_folder` 字段）:
```toml
[[dimension_scan.dimension_configs]]
    dimension = "minecraft:the_nether"
    region_folder = "DIM-1"      # 可选：指定 MCA 文件存放目录（world 目录下的路径）
    scan_mode = "CAVE"
    cave_start = 120            # 从 Y=120 向下扫描，找到基岩天花板
```

**字段说明**：
| 字段 | 说明 |
|------|------|
| `dimension` | 维度 ID（如 `minecraft:the_nether`） |
| `region_folder` | MCA 文件存放目录（如 `DIM-1`），默认使用标准路径 |
| `scan_mode` | `SURFACE`（地表）或 `CAVE`（洞穴） |
| `cave_start` | 洞穴起始高度（仅 CAVE 模式有效） |

**文件夹结构变更**:
```
服务端 (server_map_cache/):
  DIM-1/
    └── caves/
        └── 5/            ← caveStart=90 → layer=5
            └── 0_0.zip

客户端 (xaero/world-map/Multiplayer_<server>/):
  DIM-1/
    └── mw$<worldId>/
        └── caves/
            └── 5/
                └── 0_0.zip
```

**注意**: 需要在配置文件中为地狱维度设置 CAVE 模式才能生成洞穴地图