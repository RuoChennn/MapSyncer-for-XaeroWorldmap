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
| 单维度生成-主世界 | ✅ | 支持 `overworld`/`nether`/`end` 及完整维度 ID |
| 单维度生成-地狱 | ⚠️ | 生成的文件存在差异，区块中间出现竖线（有待改进） |
| 单维度生成-末地 | ✅ | 末地虚空区域正确渲染为深紫色（已修复边缘空白填充问题） |
| Mod 维度生成 | ✅ | 支持 mod 维度 ResourceLocation 格式，使用 namespace$path 格式保存（暮光森林测试通过） |
| 单区域生成 | ✅ | 指定坐标生成单个区域，用于测试或针对性更新 |
| 强制生成 | ✅ | 强制重新生成，忽略缓存 |
| 强制保存机制 | ✅ | 生成前强制保存区块，兼容 C2ME |
| 维度目录建议 | ✅ | 动态列出已加载维度作为命令建议 |
| 洞穴模式生成 | ✅ | CAVE 模式从固定高度向下扫描，支持 caves/<layer> 目录（内容有一定异常） |
| MCA 路径配置 | 🧪 | region_folder 配置支持自定义 MCA 存放目录 |

### 2.2 增量更新系统

| 功能 | 状态 | 描述 |
|------|------|------|
| 批量检测模式 | ✅ | 自动检测未变化 MCA，跳过已处理区域 |
| TICK 周期模式 | ⚠️ | 可配置 tick 间隔自动扫描（使用指令后未同步更改配置文件） |
| SCHEDULED 定时模式 | ⚠️ | 每日指定时间自动更新（使用指令后未同步更改配置文件） |
| 禁用增量更新 | ✅ | `/mapsyncer incremental off` 停止自动更新 |
| 时间戳缓存 | ✅ | MCA/生成时间戳持久化，秒级精度 |
| 洞穴模式增量更新 | 🧪 | 增量更新支持 caves/<layer> 目录检测 |

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
| 洞穴层同步 | 🧪 | 支持 caves/<layer> 目录结构同步 |
| 维度不存在提示 | 🧪 | 客户端请求维度不存在时提示需先生成 |

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
| `/mapsyncer sync` | ⚠️ | 同步当前维度（存在问题：无法确定目录） |
| `/mapsyncer sync <dim>` | ⚠️ | 同步指定维度（存在问题：无法确定目录） |
| `/mapsyncer sync all` | ✅ | 同步所有维度（应提示具体同步了哪些维度） |
| 维度名称别名 | ⚠️ | 支持 `overworld`/`null`/`nether`/`dim-1` 等（存在问题） |
| Mod 维度同步 | ✅ | 支持任意维度 ID（暮光森林测试通过） |
| 维度目录建议 | ✅ | 动态扫描 Xaero 目录列出已有维度（原版通过） |
| 单维度精确同步 | 🧪 | 不 fallback 到其他维度 |

### 3.2 元数据计算系统

| 功能 | 状态 | 描述 |
|------|------|------|
| 时间戳计算 | ✅ | 本地文件修改时间，秒级精度 |
| CRC32 哈希 | ✅ | 并行计算（限制 2 线程避免卡顿） |
| 时间戳缓存 | ✅ | `sync_timestamps.cache` 持久化 |
| 洞穴层路径解析 | 🧪 | 支持 caves/<layer> 目录路径解析 |

### 3.3 数据接收与处理

| 功能 | 状态 | 描述 |
|------|------|------|
| 分批接收 | ✅ | 累积区域数据用于选择性刷新 |
| 增量比对 | ✅ | 双重比对（哈希+时间戳）选择性更新 |
| Chunk Update 控制 | ✅ | 同步时暂停 Xaero 区块写入 |
| 地图重载触发 | ✅ | 同步完成后刷新地图 |
| 洞穴层数据写入 | 🧪 | 根据 caveLayer 写入 caves/<layer> 目录 |

### 3.4 Xaero 集成

| 功能 | 状态 | 描述 |
|------|------|------|
| 服务器 IP 定位 | ✅ | 自动识别服务器地址，处理 IPv4/IPv6/端口 |
| 维度目录映射 | ✅ | 主世界→`null`，地狱→`DIM-1`，末地→`DIM1` |
| 文件写入 | ✅ | 临时文件+原子替换，自动创建目录 |
| 选择性重载 | ✅ | 仅重置视距范围内区域 |
| 缓存清理 | ✅ | 清理 `.xwmc` 渲染缓存 |
| 反射操作 | ✅ | 操作 `WorldMapSession`、`MapProcessor` 等内部类 |
| 洞穴层目录写入 | 🧪 | 写入 mw$worldId/caves/<layer>/ 目录 |

### 3.5 进度追踪

| 功能 | 状态 | 描述 |
|------|------|------|
| 进度显示 | ✅ | 聊天栏显示百分比、区域数量、耗时 |
| 状态追踪 | ✅ | 实时追踪，支持状态字符串 |

---

## 四、配置系统

### 4.1 通用配置（mapsyncer-common.toml）

| 配置项 | 状态 | 默认值 | 说明 |
|--------|------|--------|------|
| `syncSpeedLimitKBps` | ✅ | 0 | 同步速率限制 |
| `enableResumeSync` | ✅ | true | 断点续传开关 |
| `maxSyncPacketSize` | ✅ | 1MB | 最大同步包大小 |
| `maxConcurrentRegions` | ✅ | 4 | 最大并发转换数 |
| `enableDebugLogging` | ✅ | false | 调试日志开关 |

### 4.2 服务端配置（mapsyncer-server.toml）

#### 增量更新配置

| 配置项 | 状态 | 默认值 | 说明 |
|--------|------|--------|------|
| `incrementalUpdateMode` | ✅ | DISABLED | 增量更新模式 |
| `incrementalUpdateIntervalTicks` | ✅ | 200 | TICK 模式间隔 |
| `scheduledUpdateHour/Minute` | ✅ | 4/0 | 定时模式时间 |

#### 维度扫描配置（🧪 待测试）

| 配置项 | 状态 | 默认值 | 说明 |
|--------|------|--------|------|
| `default_scan_mode` | 🧪 | SURFACE | 默认扫描模式 |
| `default_cave_start` | 🧪 | 63 | 默认洞穴起始高度 |
| `dimension_configs` | 🧪 | [] | 维度配置列表 |

**维度配置项说明**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `dimension` | String | 维度 ID（如 `minecraft:the_nether`） |
| `region_folder` | String | MCA 文件存放目录（可选，如 `DIM-1`） |
| `scan_mode` | Enum | `SURFACE`（地表）或 `CAVE`（洞穴） |
| `cave_start` | Int | 洞穴起始高度（仅 CAVE 模式有效） |

**配置示例**：
```toml
[dimension_scan]
    default_scan_mode = "SURFACE"
    default_cave_start = 63

    [[dimension_scan.dimension_configs]]
        dimension = "minecraft:the_nether"
        region_folder = "DIM-1"      # 可选：指定 MCA 文件存放目录
        scan_mode = "CAVE"
        cave_start = 120             # 从 Y=120 向下扫描

    [[dimension_scan.dimension_configs]]
        dimension = "minecraft:overworld"
        scan_mode = "SURFACE"

    [[dimension_scan.dimension_configs]]
        dimension = "minecraft:the_end"
        scan_mode = "SURFACE"
```

---

## 五、网络协议系统

| 功能 | 状态 | 描述 |
|------|------|------|
| SyncRequestPayload | ✅ | 客户端→服务端：时间戳+哈希 Map |
| SyncResponsePayload | ✅ | 服务端→客户端：区域数据+worldId+完成标记 |
| SyncProgressPayload | ✅ | 服务端→客户端：进度+总数+状态 |
| ChunkMapData 编码 | ✅ | 坐标+维度+压缩数据 |
| caveLayer 字段 | 🧪 | 新增洞穴层信息（向后兼容） |
| StreamCodec 实现 | ✅ | NeoForge 自定义 encode/decode |
| 协议注册 | ✅ | PayloadRegistrar，optional 双向通道 |

---

## 六、维度路径映射系统

| 功能 | 状态 | 描述 |
|------|------|------|
| 原版维度映射 | ✅ | the_nether → DIM-1, the_end → DIM1, overworld → null |
| Mod 维度映射 | ✅ | namespace:path → namespace$path（动态检测新格式） |
| 双向转换 | ✅ | 文件系统目录 ↔ Xaero 目录 ↔ ResourceLocation path |
| 统一映射类 | ✅ | DimensionPathMapping 类管理所有映射逻辑 |
| 新旧格式兼容 | ✅ | 支持 Minecraft 26.1+ 新格式和传统格式自动检测 |
| 原版维度新格式 | 🧪 | dimensions/minecraft/overworld 等 26.1+ 格式支持 |
| 首次转换检测 | 🧪 | 首次执行地图转换时自动检测维度路径并写入配置 |

---

## 七、文件存储结构

### 7.1 服务端缓存目录

```
<server>/server_map_cache/
├── null/                         # 主世界 ✅
├── DIM-1/                        # 下界 ✅
│   └── caves/                    # 洞穴层 🧪
│       └── <layer>/              # layer = caveStart >> 4
│           └── regionX_regionZ.zip
├── DIM1/                         # 末地 ✅
├── <mod_dimension>/              # Mod 维度 🧪
├── mca_timestamps.cache          # MCA 时间戳 ✅
└── generation_cache.properties   # 生成时间戳+哈希 ✅
```

### 7.2 客户端地图目录

```
<client>/xaero/world-map/
├── Multiplayer_<serverIP>/       ✅
│   ├── null/mw$<worldId>/        ✅ 主世界
│   │   └── caves/<layer>/        🧪 洞穴层
│   ├── DIM-1/                    ✅ 下界
│   │   └── mw$<worldId>/         ✅
│   │       └── caves/<layer>/    🧪 洞穴层
│   └── DIM1/                     ✅ 末地
└── world/                        ⚠️ 单人世界未测试
```

### 7.3 缓存文件格式

| 格式 | 状态 | 描述 |
|------|------|------|
| 时间戳缓存（旧） | ✅ | 纯文本，`<路径>: <时间戳毫秒>` |
| 生成缓存（新） | ✅ | Properties，`<路径> = <秒>:<CRC32>` |
| 区域 ZIP 文件 | ✅ | 单个 `region.xaero`，标准 ZIP 压缩 |
| caves 层路径 | 🧪 | `dim/caves/layer/regionX_regionZ` 格式 |

---

## 八、MCA 文件解析系统

### 8.1 独立解析器

| 功能 | 状态 | 描述 |
|------|------|------|
| 零依赖设计 | ✅ | 纯 Java 标准库，无 Minecraft API 依赖 |
| 文件格式解析 | ✅ | 位置表、时间戳表、区块数据扇区 |
| GZIP/ZLIB 压缩 | ✅ | 支持类型 1、2 |
| LZ4 压缩 | ⏳ | 检测但不支持，抛出提示异常 |
| 区块位置计算 | ✅ | 32x32 网格索引、偏移扇区计算 |

### 8.2 NBT 解析

| 功能 | 状态 | 描述 |
|------|------|------|
| 全标签类型 | ✅ | TAG_End 到 TAG_LongArray（0-12） |
| 嵌套结构 | ✅ | Compound、List 嵌套 |
| 大端字节序 | ✅ | UTF-8 字符串、长数组位数组 |

### 8.3 区块数据解析

| 功能 | 状态 | 描述 |
|------|------|------|
| 高度图解析 | ✅ | `WORLD_SURFACE` 类型，计算扫描起始高度 |
| 区块段解析 | ✅ | 16x16x16 section，Y 范围 -64 到 320 |
| 方块状态解析 | ✅ | `block_states` 调色板、属性、位数组 |
| 生物群系解析 | ✅ | 4x4x4 voxel 格式，边界平滑 |
| 光照数据解析 | ✅ | BlockLight/SkyLight nibble 数组 |

### 8.4 表面扫描与转换

| 功能 | 状态 | 描述 |
|------|------|------|
| 扫描策略 | ✅ | SURFACE：从高度图向下，CAVE：从固定高度向下 |
| 表面识别 | ✅ | 含水→流体→隐形→透明→实体方块 |
| 单方块 palette | ✅ | 无 data 数组 section 处理 |
| Xaero 格式输出 | ✅ | 版本 6.8，TileChunk/Tile 结构 |
| 调色板系统 | ✅ | 方块/生物群系动态索引，压缩策略 |
| Overlay 机制 | ✅ | 多层叠加，opacity 累加 |
| 光照模式切换 | ✅ | SURFACE（地表）/CAVE（洞穴/地狱） |
| CaveModeParams | 🧪 | 洞穴起始高度和深度参数 |

---

## 九、方块属性与颜色系统

### 9.1 属性查询

| 功能 | 状态 | 描述 |
|------|------|------|
| 基础属性 | ✅ | `isAir`/`isWater`/`isTransparent`/`isInvisible` 等 |
| 植物检测 | ✅ | 30+ 种植物方块基类检查 |
| 含水检测 | ✅ | `waterlogged` 属性+名称模式匹配 |
| Mod 方块自动识别 | ✅ | 注册表 API、RenderShape、BlockTags |
| 问题方块处理 | ✅ | MapColor 异常记录，启发式兜底 |
| 属性缓存 | ✅ | ConcurrentHashMap 缓存查询结果 |

### 9.2 颜色映射

| 功能 | 状态 | 描述 |
|------|------|------|
| 纹理颜色提取 | ✅ | 客户端：上表面纹理平均颜色 |
| MapColor API | ✅ | `state.getMapColor()`，ID 到 RGB 映射 |
| 原版精确颜色 | ✅ | 答编码常见方块颜色 |
| 启发式规则 | ✅ | 名称模式推断（矿石→金色，树叶→绿色等） |

---

## 十、安全与稳定性

| 功能 | 状态 | 描述 |
|------|------|------|
| 并发保护 | ✅ | volatile、ConcurrentHashMap、锁、Atomic |
| 错误处理 | ✅ | 单区块失败不中断，异常捕获记录 |
| 内存管理 | ✅ | 服务器停止清理缓存，避免泄漏 |
| C2ME 兼容 | ✅ | 主线程调度保存操作 |

---

## 十一、已知问题与限制

### 11.1 渲染差异

| 问题 | 状态 | 描述 |
|------|------|------|
| 地狱渲染问题 | ⚠️ | 区块中间出现竖线，像被拦腰斩断 |
| 末地渲染问题 | ✅ | 虚空区域正确显示深紫色（已修复空白像素写入逻辑） |
| 洞穴内容异常 | ⚠️ | 洞穴模式生成的文件存在一定异常 |
| 含水方块渲染 | ⚠️ | 水下方块、含水方块存在差异 |
| 树木渲染 | ⚠️ | 某些树木类型不完整 |
| 水体色彩 | ⚠️ | 服务端生成与客户端略有差异 |
| Mod 方块颜色 | ⚠️ | 启发式推断可能不准确 |
| Biome 精度 | ⚠️ | MCA 4x4x4 精度，低于原生（不修复） |

### 11.2 功能限制

| 限制 | 状态 | 说明 |
|------|------|------|
| LZ4 压缩 | ⏳ | 不支持 |
| 大地图耗时 | ⚠️ | 同步需要时间，增量较快 |
| 单人世界 | 🧪 | 主要针对多人服务器 |
| Mod 维度 | ✅ | 暮光森林测试通过，路径格式正确 |
| 其他维度测试 | ⚠️ | 地狱、末地生成存在渲染问题 |
| 洞穴模式测试 | ✅ | 基本功能通过，内容有一定异常 |
| 增量更新指令 | ⚠️ | 使用指令后未同步更改配置文件 |
| 客户端同步目录 | ⚠️ | 存在问题：无法确定目录，是否已连接至服务器 |

---

## 十二、技术实现亮点

| 亮点 | 状态 | 描述 |
|------|------|------|
| 独立解析器 | ✅ | 纯 Java，无 API 依赖，GZIP/ZLIB 支持 |
| Xaero 格式兼容 | ✅ | 版本 6.8，调色板压缩，Overlay 累加 |
| 网络优化 | ✅ | 分批传输、速率限制、断点续传 |
| 客户端集成 | ✅ | 反射操作，选择性重载，缓存清理 |
| Mod 方块支持 | ✅ | 自动识别，RenderShape/Tags 检测 |
| 增量同步 | ✅ | CRC32 哈希比对，保留客户端探索成果 |
| 洞穴模式配置 | 🧪 | 灵活的维度扫描配置系统 |
| MCA 路径配置 | 🧪 | region_folder 支持自定义维度文件路径 |

---

## 十三、未来规划

| 功能 | 状态 | 描述 |
|------|------|------|
| 区块级智能合并 | 📝 | 完善合并逻辑，强制更新选项 |
| 完整性检查优化 | 📝 | 数据验证，部分区块补全 |

---

**文档版本**: 3.5
**最后更新**: 2026-05-20
**模组版本**: MapSyncer for XaeroWorldmap NeoForge 1.21.X

---

### 历史更新记录

**2026-05-20 更新 (v3.5)**:

- ✅ **服务端命令使用 DimensionArgument**: 解决 Mod 维度 ID 解析问题
  - 问题：使用 `StringArgumentType.word()` 时，包含冒号的维度 ID（如 `twilightforest:twilight_forest`）无法正确解析
  - 修复：使用 Minecraft 提供的 `DimensionArgument.dimension()` 正确解析维度参数
  - 自动提供维度建议：显示所有已加载维度的完整 ID（包括 Mod 维度）
  - 支持命令：`/mapsyncer generate twilightforest:twilight_forest` 正常工作

- ✅ **客户端命令添加 Mod 维度建议**: 从注册表获取已知维度
  - 从客户端维度注册表获取已安装的 Mod 维度
  - 从 Xaero 目录扫描已有同步数据
  - 命令建议显示完整维度 ID（如 `twilightforest:twilight_forest`）

**2026-05-20 更新 (v3.4)**:

- ✅ **统一维度指令格式**: Mod 维度统一使用 `namespace:path` 格式
  - 服务端命令建议：原版维度显示简化名称（overworld, the_nether, the_end），Mod 维度显示完整 ID（如 twilightforest:twilight_forest）
  - 客户端命令建议：同样规则，从 Xaero 目录名（`namespace$path`）转换为完整维度 ID（`namespace:path`）
  - `normalizeDimension` 方法：支持完整 ID 格式输入，原版维度返回简化名称，Mod 维度保持完整 ID
  - `resolveCorrectXaeroDim` 方法简化：完整维度 ID 直接转换，缺少 namespace 时从缓存/目录反向查找
  - 用户输入体验改进：输入 `twilightforest:twilight_forest` 而不是模糊的 `twilight_forest`

**2026-05-20 更新 (v3.3)**:

- ✅ **客户端增量同步维度名格式修复**: 确保客户端发送正确的 Xaero 格式维度名
  - 问题：用户输入 `twilight_forest` 时，客户端无法转换为正确的 `twilightforest$twilight_forest` 格式
  - 修复 `MapSyncerCommand.sendSyncRequest`: 添加 `resolveCorrectXaeroDim` 方法从缓存和目录反向查找正确格式
  - 修复 `ClientHashManager.buildRelativePath`: 添加 `ensureCorrectXaeroFormat` 方法确保目录名转换为正确格式
  - 修复单维度同步时使用已解析的 `xaeroDim` 作为目录名，不再调用可能失败的 `getDimensionDir`
  - 从缓存键（`namespace$path/regionX_regionZ`）和目录结构反向查找正确的 Xaero 格式
  - 兼容旧的错误目录格式（如只有 path 部分），能正确识别并转换为完整格式

**2026-05-20 更新 (v3.2)**:

- ✅ **末地虚空渲染修复**: 空白像素和空白 Tile 正确写入数据
  - 空白像素写入完整 AIR 方块状态 + null biome
  - 空白 Tile 也写入 256 个像素数据，不再跳过
  - 客户端渲染时使用 VOID_COLOR（深紫色）显示虚空区域
  - 参考 Xaero 的 `prepareForWriting` 和 `savePixel` 逻辑

- ✅ **Mod 维度路径映射修复**: 清理预设 DIM{id} 映射
  - 移除预设的 Mod 维度 DIM{id} 映射（如 DIM7）
  - 统一使用动态检测的 namespace$path 格式（如 twilightforest$twilight_forest）
  - 服务端缓存和客户端保存路径保持一致
  - 暮光森林维度生成与同步测试通过

---

**2026-05-19 更新（待测试）**:

- 🧪 **维度扫描配置系统**: 通过 `mapsyncer-server.toml` 配置各维度的扫描模式
  - 新增 `ScanMode` 枚举：SURFACE（地表）/CAVE（洞穴）
  - 新增 `DimensionScanConfig` record：维度扫描配置记录
  - 支持维度列表配置，每个维度可指定 scan_mode 和 cave_start
  - 新增 `region_folder` 字段：指定 MCA 文件存放目录

- 🧪 **洞穴模式生成**: CAVE 模式从固定高度向下扫描
  - 地狱默认使用 CAVE 模式，cave_start=90
  - 生成文件存放至 caves/<layer> 目录（layer = caveStart >> 4）
  - 支持负高度：-64 → layer -4

- 🧪 **网络协议扩展**: ChunkMapData 新增 caveLayer 字段
  - 使用标记位实现向后兼容
  - 旧客户端默认为地表层

- 🧪 **维度路径映射**: DimensionPathMapping 统一管理路径转换
  - 文件系统目录 ↔ Xaero 目录 ↔ ResourceLocation path
  - 原版维度映射：the_nether → DIM-1, the_end → DIM1, overworld → null
  - Mod 维度：namespace:path → dimensions/<namespace>/<path>

- 🧪 **Minecraft 26.1 新格式支持**: 原版维度路径格式更新
  - 新格式：dimensions/minecraft/overworld/region, dimensions/minecraft/the_nether/region
  - 传统格式：region/, DIM-1/region/, DIM1/region/
  - 自动检测实际使用的格式，优先检查新格式
  - 首次执行地图转换时自动检测并写入配置文件

- 🧪 **维度注册系统**: DimensionRegistry 首次转换时自动注册
  - 扫描服务器所有已加载维度
  - 自动检测维度路径格式并写入配置
  - 支持原版维度和 Mod 维度

- 🧪 **增量更新支持洞穴模式**: performIncrementalScan 读取配置
  - 根据维度配置选择 LightMode 和 CaveModeParams
  - 更新 GenerationCache 包含 caves 层信息

- 🧪 **单区域生成支持洞穴模式**: generateSingleRegion 读取配置
  - 根据 scanConfig 计算 outputDir
  - 支持 region_folder 配置

- 🧪 **同步维度过滤**: 只处理客户端请求的维度
  - 从客户端元数据键提取维度列表
  - 维度不存在时提示需先生成

- 🧪 **客户端 caves 目录写入**: 根据 caveLayer 写入正确目录
  - 地表：mw$worldId/regionX_regionZ.zip
  - 洞穴：mw$worldId/caves/layer/regionX_regionZ.zip

- 🧪 **客户端缓存路径解析**: 支持 caves/<layer> 格式
  - 时间戳缓存路径与服务端一致

---

### 2026-05-19 之前的更新

- feat(client): 客户端 sync 指令支持动态列出已有维度数据
- feat(client): 支持同步任意 mod 维度名称
- feat(server): 改进 generate 指令结构，支持 `<dim> [x] [z]` 和 `force` 参数
- feat(server): 动态列出所有已加载维度作为指令建议（支持 mod 维度）
- feat(client): 实现客户端时间戳缓存与精准地图刷新机制
- feat(server): 在转换日志中显示空MCA文件计数和总region数
- feat(server): 扩展植物方块基类检查支持更多类型
- fix(memory): 修复多处潜在内存溢出问题
- perf(client): 限制哈希计算并行度为2避免卡住游戏
- fix(client): 修正客户端哈希缓存路径格式匹配服务端
- refactor(cache): 改用 Properties 格式保存缓存文件，时间戳精度改为秒级

---

**注意事项**:

1. **洞穴模式配置**: 需要在 `mapsyncer-server.toml` 中为地狱维度设置 CAVE 模式才能生成洞穴地图
2. **旧缓存清理**: 此次更新后旧的 `server_map_cache/` 目录结构可能不兼容，建议删除旧缓存重新生成
3. **region_folder 用途**: 用于指定 MCA 文件在 world 目录下的存放位置，适配某些修改维度 ID 的 mod

**已知问题**:

1. **地狱/末地渲染问题**: 地狱区块出现竖线分割，末地虚空区域渲染异常（边缘绿色填充）
2. **客户端同步目录问题**: 单维度同步时提示"无法确定目录"，需要检查 XaeroMapIntegrator 的服务器目录识别逻辑
3. **增量更新配置同步**: 使用 `/mapsyncer incremental tick/scheduled` 指令后未同步更改配置文件值