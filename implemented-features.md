# MapSyncer 功能实现文档

本文档详细列出了 MapSyncer for XaeroWorldmap 模组的全部功能及其实现状态。

**状态标记说明**：✅ 已实现 | ⏳ 未实现/部分实现 | 📝 规划中

---

## 一、核心功能概述 ✅

MapSyncer 是一个 Minecraft NeoForge 1.21.X 模组，核心功能是将服务端已探索的区域地图数据同步到客户端的 Xaero's World Map。模组完全由 AI 编写，实现了从 MCA 文件解析、Xaero 格式转换、网络同步到客户端地图刷新的完整流程。

### 设计目标
- 服务端地图预生成后同步到客户端
- 适用于玩家初次进入已开放很久的服务器
- 适用于服务器使用 Chunky 预生成地图的场景
- 减少客户端手动探索的时间

---

## 二、服务端功能 ✅

### 2.1 地图缓存生成系统 ✅

#### ✅ 全维度生成
- 自动扫描服务端所有已加载维度（主世界、下界、末地及 mod 维度）
- 遍历各维度的 region 目录，识别所有 `.mca` 文件
- 将每个 region 文件转换为 Xaero 格式的 `.zip` 文件
- 输出到统一的缓存目录 `server_map_cache/`

#### ⏳ 单维度生成
- 支持指定维度单独生成
- 支持快捷名称：`overworld`、`nether`、`end`
- 支持完整维度 ID：`minecraft:the_nether`
- 支持 mod 维度的 ResourceLocation 格式（未测试）
- 动态列出所有已加载维度（包括 mod 维度）作为指令建议（未测试）

#### ✅ 单区域生成
- 支持生成指定坐标的单个区域
- 用于测试或针对性更新
- 自动检测当前维度并使用正确的路径

#### ✅ 强制保存机制
- 生成前强制将所有区块保存到磁盘
- 确保 `.mca` 文件包含最新的世界数据
- 兼容 C2ME 等并发优化 mod（通过主线程调度避免冲突）
- 60 秒超时保护机制

### 2.2 增量更新系统 ✅

#### ✅ 三种更新模式

**✅ 批量检测模式**
- `/mapsyncer generate` 时自动检测未变化的 MCA 文件
- 通过时间戳缓存比对跳过已处理区域
- 仅生成有变化的区域

**✅ TICK 周期模式**
- 按固定 tick 间隔自动扫描更新
- 默认间隔 200 ticks（约 10 秒）
- 可配置范围 20-72000 ticks
- 适合频繁更新需求

**✅ SCHEDULED 定时模式**
- 每日指定时间自动更新一次
- 使用服务器本地时区
- 默认时间 04:00
- 可配置小时（0-23）和分钟（0-59）
- 适合低负载时段更新

#### ✅ 时间戳缓存机制
- MCA 文件修改时间缓存（`mca_timestamps.cache`）
- 生成时间戳缓存（`generation_cache.properties`）
- 时间戳精度为秒级（避免毫秒级精度损失）
- 缓存使用 Properties 格式持久化保存
- 用于增量检测和同步比对
- 服务器重启后有效

### 2.3 同步处理系统 ✅

#### ✅ 哈希值比对同步
- 使用 CRC32 哈希值比对文件内容
- 哈希值一致 → 跳过同步（文件内容完全相同）
- 哈希值不一致 + 客户端时间戳更旧 → 同步
- 客户端时间戳不比服务端旧 → 保留客户端数据（避免覆盖客户端探索成果）
- 服务端 GenerationCache 存储 `时间戳秒:哈希值`
- 客户端 ClientHashManager 计算本地文件 CRC32 哈希
- 哈希计算并行度限制为 2，避免卡住游戏

#### ✅ 时间戳比对同步
- 接收客户端发送的本地文件时间戳和哈希列表
- 与服务端生成时间戳和哈希比对
- 仅发送客户端不存在或时间戳更旧且哈希不一致的区域
- 跳过客户端已有更新数据的区域（避免覆盖客户端探索成果）

#### ✅ 分批传输
- 按 1MB 分批传输数据（默认配置）
- 可配置最大包大小 64KB - 10MB
- 防止大地图同步导致玩家超时掉线

#### ✅ 速率限制
- 可配置同步速率限制（KB/s）
- 默认不限速，建议 500-2000 KB/s
- 通过计算预期传输时间并延迟实现
- 避免网络拥塞

#### ✅ 断点续传
- 记录玩家同步进度
- 玩家断线重连后可从上次中断位置继续
- 可通过配置开关启用/禁用
- 进度数据在内存中保存，服务器重启后重新开始

#### ✅ 同步保护
- 玩家断开连接时自动中止同步
- 玩家跨维度时自动中止同步（维度变化检测）
- 同步过程中持续验证玩家状态
- 保留进度数据用于续传

#### ✅ World ID 读取
- 从服务端 `xaeromap.txt` 读取世界 ID
- 确保客户端写入到正确的世界目录
- 支持多世界服务器场景

### 2.4 服务端命令系统 ✅

| 命令 | 功能 | 权限要求 |
|------|------|----------|
| `/mapsyncer generate` | 生成所有维度地图缓存 | OP (level 4) |
| `/mapsyncer generate <dimension>` | 生成指定维度缓存（增量模式） | OP |
| `/mapsyncer generate <dimension> <x> <z>` | 生成指定维度的单个区域 | OP |
| `/mapsyncer generate <dimension> force` | 强制生成维度（无视已有缓存） | OP |
| `/mapsyncer status` | 查看生成进度和状态 | OP |
| `/mapsyncer incremental off` | 禁用增量更新 | OP |
| `/mapsyncer incremental tick [interval]` | 设置 TICK 模式 | OP |
| `/mapsyncer incremental scheduled [hour] [minute]` | 设置定时模式 | OP |
| `/mapsyncer incremental status` | 查看增量更新状态 | OP |

**注**：mod 维度生成功能未测试，指令建议已支持动态列出所有已加载维度。

---

## 三、客户端功能 ✅

### 3.1 同步命令系统 ✅

| 命令 | 功能 |
|------|------|
| `/mapsyncer sync` | 同步当前所在维度 |
| `/mapsyncer sync overworld` | 同步主世界 |
| `/mapsyncer sync nether` | 同步下界 |
| `/mapsyncer sync end` | 同步末地 |
| `/mapsyncer sync <dimension>` | 同步指定维度（支持 mod 维度） |
| `/mapsyncer sync all` | 同步所有维度 |

#### ✅ 维度名称支持
- 支持多种别名：`overworld`/`null`/`minecraft:overworld`
- 支持快捷名称：`nether`/`the_nether`/`dim-1`
- 支持末地别名：`end`/`the_end`/`dim1`
- 支持通配符：`all`/`*`
- 支持 mod 维度名称（直接使用维度 ID 或 Xaero 目录名）
- 动态扫描 Xaero 目录列出已有维度数据作为建议

### 3.2 时间戳与哈希计算系统 ✅

- 计算客户端本地文件的修改时间戳（秒级）和 CRC32 哈希
- 扫描 Xaero 地图目录中的 `.zip` 文件
- 提取区域坐标、修改时间和 CRC32 哈希值
- 发送给服务端用于比对
- 哈希计算并行度限制为 2，避免卡住游戏

### 3.3 地图数据接收系统 ✅

#### ✅ 数据接收流程
- 接收服务端分批发送的区域数据
- 累积所有接收的区域用于选择性刷新
- 支持区块级增量合并（RegionMerger）（暂未实现）
- 仅添加客户端不存在的区块数据
- 同步完成后触发地图重载

#### ✅ 增量合并机制
- 检测客户端区域文件与服务器记录的差异
- 通过CRC32和时间戳双重比对服务端数据与客户端现有数据，选择性进行更新
- 优先保留客户端已探索区块
- 统计合并结果（新增区块数）

#### ✅ Chunk Update 控制
- 同步开始时暂停 Xaero 的区块更新处理，防止同步过程中 Xaero 写入数据冲突
- 同步完成后恢复区块更新
- 通过反射暂停/恢复 MapWriter 线程

### 3.4 Xaero 集成系统 ✅

#### ✅ 地图目录定位
- 自动识别连接的服务器 IP
- 处理 IPv4/IPv6 地址格式
- 处理端口号和特殊字符
- 定位到正确的 `Multiplayer_<server>/null/mw$<worldId>` 目录

#### ✅ 维度目录映射
- 主世界 → `null`
- 下界 → `DIM-1`
- 末地 → `DIM1`
- 其他维度保持原名称

#### ✅ 文件写入
- 将服务端数据写入正确的位置
- 使用临时文件+原子替换确保完整性
- 自动创建缺失的目录结构

#### ✅ 选择性重载系统
- 仅重置玩家视距范围内的区域
- 避免全地图刷新的巨大性能开销
- 计算玩家当前区域和视距范围
- 结合已更新区域列表确定重载范围
- 通过反射操作 Xaero 内部 `loadState` 字段

#### ✅ 缓存清理
- 同步完成后清理 Xaero 的渲染缓存（`.xwmc` 文件）
- 清理 `cache/` 和 `cache_1/` 目录
- 确保新数据被正确渲染

#### ✅ Xaero 内部操作
- 通过反射调用 `WorldMapSession`、`MapProcessor`、`MapDimension`
- 调用 `detectRegions` 扫描新文件
- 调用 `startFullMapReload` 触发重载
- 操作 `LeveledRegionManager` 的区域数据结构

### 3.5 进度追踪系统 ✅

#### ✅ 进度显示
- 聊天栏显示同步进度百分比
- 每 10% 进度更新一次显示
- 显示已处理/总数区域数量
- 显示同步耗时统计

#### ✅ 进度状态
- 实时追踪已处理数量和总数量
- 记录同步开始时间
- 支持状态字符串显示（如 "waiting..."、"completed"）

---

## 四、MCA 文件解析系统 ✅

### 4.1 独立 MCA 解析器 ✅

#### ✅ 零依赖设计
- 仅使用 Java 标准库，不依赖 Minecraft API
- 直接读取 `.mca` 文件二进制格式
- 支持服务端独立运行（无需安装 Xaero）

#### ✅ 文件格式解析
- 解析 MCA 文件头部位置表（0-4KB）
- 解析时间戳表（4-8KB）
- 解析区块数据扇区（8KB+）
- 每个 region 包含 32x32 个区块

#### ⏳ 压缩算法支持
- GZIP 压缩（类型 1）
- ZLIB 压缩（类型 2）
- 无压缩（类型 3）
- LZ4 压缩检测但暂不支持（抛出提示异常）

#### ✅ 区块位置计算
- 计算 32x32 网格中的区块位置索引
- 解析偏移扇区和扇区数量
- 计算实际数据偏移位置
- 处理不存在区块的检测

### 4.2 NBT 解析系统 ✅

#### ✅ NBT 标签类型支持
- TAG_End (0)
- TAG_Byte (1)
- TAG_Short (2)
- TAG_Int (3)
- TAG_Long (4)
- TAG_Float (5)
- TAG_Double (6)
- TAG_ByteArray (7)
- TAG_String (8)
- TAG_List (9)
- TAG_Compound (10)
- TAG_IntArray (11)
- TAG_LongArray (12)

#### ✅ 解析特性
- 支持嵌套 Compound 和 List 结构
- 支持大端字节序读取
- 支持字符串 UTF-8 解码
- 支持长数组位数组解析

### 4.3 区块数据解析 ✅

#### ✅ 高度图解析
- 解析 `Heightmaps` NBT 数据
- 支持 `WORLD_SURFACE` 高度图类型
- 计算扫描起始高度（高度图值 + 3）
- 处理高度图缺失或部分缺失的情况

#### ✅ 区块段解析
- 解析 `sections` 数组
- 处理每个 16x16x16 区块段
- 支持 Y 坐标范围 -64 到 320

#### ✅ 方块状态解析
- 解析 `block_states` 调色板
- 解析方块属性（如 `snowy`、`waterlogged`）
- 解析位数组数据索引
- 支持 YZX 编码顺序

#### ✅ 生物群系解析
- 解析 `biomes` 调色板
- 支持 4x4x4 voxel 格式
- 生物群系边界平滑处理
- 支持 Xaero 十字形采样兼容

#### ✅ 光照数据解析
- 解析 `BlockLight` nibble 数组
- 解析 `SkyLight` nibble 数组
- 支持 2048 字节存储 4096 个值格式
- Wiki 规范公式实现

### 4.4 方块表面扫描算法 ✅

#### ✅ 扫描策略
- 从高度图计算的起始高度向下扫描
- 按 section Y 从高到低排序处理
- 每个 section 内从最高层向下扫描

#### ✅ 表面识别流程
1. 检查含水方块（作为表面 + 添加水 overlay）
2. 检查流体方块（作为 overlay，继续向下）
3. 检查隐形方块（跳过）
4. 检查透明方块（作为 overlay，继续向下）
5. 检查有地图颜色的实体方块（确定为表面）

#### ✅ 单方块 palette 处理
- 识别单方块 section（无 data 数组）
- 从 section 最高层逐层扫描
- 正确处理全填充 section

### 4.5 Xaero 格式转换 ✅

#### ✅ 输出格式
- 版本头部：`0xFF` + major/minor 版本
- 当前版本：6.8（与 Xaero 兼容）
- 8x8 TileChunks 结构
- 每个 TileChunk 包含 4x4 Tiles
- 每个 Tile 包含 16x16 像素

#### ✅ 数据编码
- 高度值编码到 params 的特定位
- 光照值存储在 params 的 8-11 位
- overlay 数量和数据独立编码
- 调色板索引优化（避免重复写入）

#### ✅ 调色板系统
- 方块调色板：动态索引映射
- 生物群系调色板：UTF 字符串写入
- 新条目标记（用于 Xaero 解析）
- 遵循 Xaero 的调色板压缩策略

#### ✅ Overlay 机制
- 支持多层 overlay（水、玻璃等）
- 实现同类型 overlay 的 opacity 累加
- 不同类型 overlay 创建新层
- 使用 lightBlock 值作为 opacity（与 Xaero 一致）

#### ✅ 特殊参数标记
- `isGrass` 标志（草方块特殊处理）
- `hasOverlays` 标志
- `biomePresent` 标志
- `topHeightDifferent` 标志
- 新调色板条目标记

---

## 五、方块属性解析系统 ✅

### 5.1 Minecraft API 查询 ✅

#### ✅ 属性查询接口
- 通过 `BuiltInRegistries.BLOCK` 获取方块
- 使用 `BlockState` 查询属性
- 支持默认状态和属性解析

#### ✅ 支持的属性类型
- `isAir` - 空气检测
- `isWater` / `isLava` - 流体检测
- `isTransparent` - 透明性检测
- `isInvisible` - 隐形性检测
- `isFlower` - 花朵检测
- `isPlant` - 植物检测（新增：覆盖更多类型）
- `isGrassBlock` - 草方块检测
- `isGlowing` - 发光检测（光照发射 >= 15）
- `lightBlock` - 光照遮挡值
- `lightEmission` - 光照发射值
- `canBeWaterlogged` - 含水能力检测
- `hasVanillaColor` - 地图颜色检测

#### ✅ 植物方块基类检查（扩展）
- `BushBlock` - 基础灌木类
- `FlowerBlock` / `TallFlowerBlock` - 花类
- `CropBlock` / `StemBlock` / `AttachedStemBlock` - 作物类
- `SaplingBlock` - 树苗类
- `MushroomBlock` - 蘑菇类
- `DoublePlantBlock` - 双高植物类
- `TallGrassBlock` - 高草类
- `SeagrassBlock` / `TallSeagrassBlock` - 海草类
- `KelpBlock` / `KelpPlantBlock` - 海带类
- `CactusBlock` / `SugarCaneBlock` - 仙人掌/甘蔗类
- `BambooSaplingBlock` / `BambooStalkBlock` - 竹子类
- `NetherWartBlock` - 地狱疣类
- `ChorusFlowerBlock` / `ChorusPlantBlock` - 紫颂类
- `CaveVinesBlock` / `CaveVinesPlantBlock` - 洞穴藤蔓类
- `GrowingPlantBlock` / `GrowingPlantHeadBlock` - 生长植物类
- `BaseCoralPlantBlock` - 珊瑚类
- `BigDripleafBlock` / `SmallDripleafBlock` - 滴滴叶类
- `PitcherCropBlock` / `TorchflowerCropBlock` - 瓶子草/火把花类
- `TwistingVinesBlock` / `WeepingVinesBlock` - 扭曲/垂泪藤类
- `WaterlilyBlock` - 睡莲类
- `DeadBushBlock` - 枯萎灌木类

### 5.2 透明性检测算法 ✅

#### ✅ 检测方法
1. AirBlock 或 TransparentBlock 类
2. 光照遮挡值 0 < value < 15
3. 特定方块名称匹配（海带、海草等）
4. 支持 mod 方块自动识别（通过 RenderShape）

### 5.3 隐形性检测算法 ✅

#### ✅ 检测规则
1. RenderShape.INVISIBLE（mod 方块自动支持）
2. 火把类方块（名称匹配）
3. 矮草（SHORT_GRASS）
4. 玻璃类方块（作为隐形处理）
5. 花（通过 BlockTags.FLOWERS + 类判断）
6. DoublePlantBlock 非花类型
7. MapColor 抛异常的问题方块

### 5.4 含水检测 ✅

#### ✅ 检测方法
- 检查 BlockState 属性定义中的 `waterlogged` 属性
- 名称模式匹配（fence_gate、stairs、slab 等）
- 支持常见可含水方块类型

### 5.5 Mod 方块支持 ✅

#### ✅ 自动识别机制
- 通过注册表 API 自动查询 mod 方块
- 使用 `RenderShape` 检测隐形 mod 方块
- 使用 `BlockTags` 检测 mod 花
- 使用 `getLightBlock` API 获取光照遮挡

#### ✅ 问题方块处理
- 记录 MapColor 抛异常的方块
- 使用备用启发式规则推断属性
- 避免崩溃，保证转换继续

### 5.6 缓存系统 ✅

- 属性查询结果缓存（ConcurrentHashMap）
- 避免重复查询提高性能
- 问题方块集合缓存
- 清除缓存接口

---

## 六、方块颜色映射系统 ✅

### 6.1 四层颜色获取策略 ✅

#### ✅ 第一层：纹理颜色提取
- 仅客户端可用
- 获取方块模型的上表面纹理
- 计算纹理平均颜色
- 支持粒子纹理作为备用

#### ✅ 第二层：MapColor API
- 使用 `state.getMapColor()` API
- 支持 MapColor ID 到 RGB 映射
- 处理 MapColor 抛异常的方块
- 记录问题方块避免后续查询

#### ✅ 第三层：原版精确颜色
- 硬编码常见原版方块的精确颜色
- 保证视觉效果一致性
- 包含石头、泥土、沙子、水、矿石等

#### ✅ 第四层：启发式规则
- 基于方块名称模式推断颜色
- 支持矿石类、原木类、树叶类等
- 支持下界方块、末地方块
- 默认灰色兜底

### 6.2 预定义颜色规则 ✅

| 类型 | 模式示例 | 颜色 |
|------|----------|------|
| 矿石类 | `_ore` | 金色 0xFDF546 |
| 原木类 | `_log`, `_wood` | 棕色 0x6B5231 |
| 树叶类 | `_leaves` | 绿色 0x3A7D23 |
| 石头类 | `stone`, `_deepslate` | 灰色 0x808080 |
| 水类 | `water` | 蓝色 0x3344FF |
| 熔岩类 | `lava` | 橙色 0xFF6600 |
| 下界类 | `netherrack` | 红色 0x723131 |
| 末地类 | `end_stone` | 末地色 0xD6D69D |
| 冰类 | `ice` | 冰蓝 0xA0D0FF |
| 发光类 | `glowstone` | 亮黄 0xFFCC66 |
| 金属类 | `iron`, `gold`, `diamond` | 各金属色 |

### 6.3 纹理颜色提取算法 ✅

- 加载纹理资源文件
- 遍历纹理像素采样
- 计算平均 RGB 值
- 处理透明度通道
- 缓存纹理颜色避免重复计算

### 6.4 MapColor ID 映射 ✅

| ID | 类型 | RGB 颜色 |
|----|------|----------|
| 0 | NONE | 0x808080 |
| 1 | GRASS | 0x5B8731 |
| 2 | SAND | 0x866043 |
| 7 | WATER | 0x3344FF |
| 8 | PLANT | 0x7ABD47 |
| 15 | STONE | 0x808080 |
| 20 | END_STONE | 0xD6D69D |
| 21 | NETHERRACK | 0x723131 |
| 33 | LAVA | 0xFF6600 |
| ... | ... | ... |

---

## 七、网络协议系统 ✅

### 7.1 数据包类型 ✅

#### ✅ SyncRequestPayload (客户端 → 服务端)
- 包含客户端本地文件时间戳 Map
- 格式：`<路径>: <时间戳毫秒>`
- 用于比对确定需要同步的区域

#### ✅ SyncResponsePayload (服务端 → 客户端)
- 包含区域数据列表（ChunkMapData）
- `isComplete` 标记是否为最后一批
- 包含服务端 worldId 用于目录定位

#### ✅ SyncProgressPayload (服务端 → 客户端)
- 已处理数量 `processed`
- 总数量 `total`
- 状态字符串 `status`

### 7.2 数据编码格式 ✅

#### ✅ ChunkMapData 编码
- 区域坐标 X/Z（int）
- 维度名称（UTF 字符串）
- 压缩的字节数据

#### ✅ StreamCodec 实现
- 使用 NeoForge 的 StreamCodec 系统
- 自定义 encode/decode 方法
- 支持 RegistryFriendlyByteBuf

### 7.3 协议注册 ✅

- 使用 `PayloadRegistrar` 注册
- 协议版本标识 "1"
- optional() 允许客户端/服务端单侧安装
- playToServer/playToClient 双向通道

---

## 八、配置系统 ✅

### 8.1 通用配置 (mapsyncer-common.json) ✅

| 配置项 | 默认值 | 范围 | 说明 |
|--------|--------|------|------|
| `syncSpeedLimitKBps` | 0 | 0-10000 | 同步速率限制 KB/s |
| `enableResumeSync` | true | - | 断点续传开关 |
| `maxSyncPacketSize` | 1048576 | 65536-10485760 | 最大同步包大小 |
| `maxConcurrentRegions` | 4 | 1-16 | 最大并发转换数 |
| `enableDebugLogging` | false | - | 调试日志开关 |

### 8.2 服务端配置 (mapsyncer-server.json) ✅

| 配置项 | 默认值 | 范围 | 说明 |
|--------|--------|------|------|
| `incrementalUpdateMode` | DISABLED | - | 增量更新模式 |
| `incrementalUpdateIntervalTicks` | 200 | 20-72000 | TICK 模式间隔 |
| `scheduledUpdateHour` | 4 | 0-23 | 定时模式小时 |
| `scheduledUpdateMinute` | 0 | 0-59 | 定时模式分钟 |

### 8.3 配置加载 ✅

- NeoForge ModConfigSpec 系统
- COMMON 和 SERVER 两层配置
- 配置文件自动生成
- 配置热加载支持

---

## 九、光照处理系统 ✅

### 9.1 光照模式 ✅

#### ✅ SURFACE 模式（地表）
- 仅使用 BlockLight
- 完全忽略 SkyLight
- 所有区域使用方块光照值
- 适用于地表地图生成

#### ✅ CAVE 模式（洞穴）
- 同时使用 BlockLight 和 SkyLight
- 露天区域（高于高度图）：SkyLight = 15
- 水下区域：使用 BlockLight
- 其他地下区域：取 max(BlockLight, SkyLight)

### 9.2 有效光照计算 ✅

#### ✅ 计算因素
- 发光方块检测（BlockLight >= 15 直接返回）
- 天空访问检测（位置高于高度图）
- 流体 overlay 检测（水下等）

#### ✅ 边界情况处理
- 无光照数据时返回 0
- 部分 section 无光照时使用默认值
- 兼容缺失 SkyLight 的情况

---

## 十、文件存储结构 ✅

### 10.1 服务端缓存目录 ✅

```
<server>/server_map_cache/
├── overworld/                    # 主世界
│   ├── -1_-1.zip                 # 区域文件
│   ├── 0_0.zip
│   └── ...
├── DIM-1/                        # 下界
│   └── ...
├── DIM1/                         # 末地
│   └── ...
├── <mod_dimension>/              # Mod 维度（未测试）
│   └── ...
├── mca_timestamps.cache          # MCA 修改时间缓存（旧格式）
└── generation_cache.properties   # 生成时间戳+哈希缓存（新格式）
```

### 10.2 客户端地图目录 ✅

```
<client>/xaero/world-map/
├── Multiplayer_<serverIP>/
│   ├── null/                     # 主世界
│   │   └── mw$<worldId>/         # 世界 ID 目录
│   │       ├── -1_-1.zip         # 区域文件
│   │       ├── 0_0.zip
│   │       ├── cache/            # 渲染缓存 (.xwmc)
│   │       └── cache_1/
│   ├── DIM-1/                    # 下界
│   └── DIM1/                     # 末地
└── world/                        # 单人世界
```

### 10.3 缓存文件格式 ✅

#### ✅ 时间戳缓存格式（旧格式）
- 纯文本格式，每行一个条目
- 格式：`<路径>: <时间戳毫秒>`
- 服务端重启后持久化

#### ✅ 生成缓存格式（新格式）
- Properties 格式
- 格式：`<路径> = <时间戳秒>:<CRC32哈希>`
- 哈希为 8 位十六进制字符串
- 服务端重启后持久化

#### ✅ 区域 ZIP 文件格式
- 包含单个 `region.xaero` 文件
- 使用标准 ZIP 压缩
- 文件命名：`<regionX_regionZ>.zip`

---

## 十一、安全与稳定性 ✅

### 11.1 并发保护 ✅

- volatile 变量确保可见性
- ConcurrentHashMap 保证线程安全
- 同步锁保护关键操作
- Atomic 变量用于计数器

### 11.2 错误处理 ✅

- 单个区块解析失败不中断整体流程
- 区域转换失败记录并继续
- 异常捕获并记录日志
- 备用策略处理未知方块

### 11.3 内存管理 ✅

- 服务器停止时清理缓存实例
- 避免单例模式的内存泄漏
- 及时清理临时集合
- 使用弱引用或及时释放大对象

### 11.4 兼容性处理 ✅

- C2ME 兼容：主线程调度保存操作
- 其他并发 mod 的潜在冲突规避
- 不同 Minecraft 版本的适配预留

---

## 十二、已知问题与限制 ⚠️

### 12.1 渲染差异问题 ⚠️

| 问题 | 描述 |
|------|------|
| 含水方块渲染异常 | 水下方块、含水方块（海带、海草）渲染存在差异 |
| 树木渲染缺失 | 某些树木类型渲染不完整 |
| 水体色彩差异 | 服务端生成水体颜色与客户端略有差异 |
| Mod 方块颜色近似 | 启发式规则推断的颜色可能与实际有差异 |
| Biome精度问题 | MCA文件保存的精度为4x4x4，xaero使用游戏内置函数获取，此处精度固然低于原生生成，所以不会修复此问题 |

### 12.2 功能限制 ⚠️

| 限制 | 说明 |
|------|------|
| LZ4 压缩不支持 | 暂不支持 LZ4 压缩的 MCA 文件 |
| 大地图处理耗时 | 非常大的地图同步需要大量时间，但是增量挺快的 |
| 单人世界未测试 | 主要针对多人服务器场景，我猜你单人也用不上 |
| Mod 维度未测试 | 理论支持但未实际测试，建议先在小范围测试 |
| 其他维度有限支持 | 主要测试主世界 |

### 12.3 性能考虑 ⚠️

- 生成大量区域需要时间
- 同步期间建议不进行其他操作
- 视距范围内选择性重载优化
- 分批传输避免网络拥塞

---

## 十三、已实现的功能（近期更新）✅

以下功能已在近期版本中实现：

### ✅ 13.1 哈希值同步机制
- CRC32 哈希值比对文件内容
- 哈希匹配 → 跳过同步（文件完全相同）
- 哈希不匹配 + 客户端旧 → 同步
- 客户端新 → 保留客户端数据
- 哈希计算并行度限制为 2（避免卡住游戏）

### ✅ 13.2 增量合并
- `RegionMerger` 实现区块级合并
- 合并服务端数据与客户端现有数据
- 仅添加客户端不存在的区块
- 检测区域完整性（64区块判断）
- 合并结果统计（客户端区块数、服务端区块数、新增区块数）

### ✅ 13.3 植物方块扩展支持
- 扩展植物方块基类检查
- 支持 30+ 种植物方块类型
- 珊瑚、藤蔓、滴水叶、紫颂等特殊植物
- 作物类方块完整支持

### ✅ 13.4 性能优化与内存修复
- 限制哈希计算并行度为 2
- 修复多处潜在内存溢出问题
- 避免重复显示 Chunk updates paused 消息
- 时间戳精度从毫秒改为秒级（避免精度损失）

### ✅ 13.5 客户端时间戳缓存机制
- ClientTimestampCache 类存储服务端同步的时间戳和哈希值
- 解决因文件写入导致修改时间变化而误判同步的问题
- 缓存持久化到 `sync_timestamps.cache` 文件（Properties 格式）
- 格式：`dimension/regionX_regionZ = timestamp_seconds:hash`
- ClientHashManager 优先使用缓存时间戳而非文件修改时间
- XaeroMapIntegrator 写入数据后更新并保存时间戳缓存
- 服务器切换时自动重置缓存实例

### ✅ 13.6 精准地图刷新机制
- 仅对无缓存的 region 触发加载（新同步的 region）
- 使用 requestLoad 直接请求加载替代 startFullMapReload 全量刷新
- clearXaeroCacheSelective 返回需要重新加载的 region 集合
- 查找所有版本缓存目录（cache, cache_1, cache_*）
- 删除已同步 region 的缓存文件，识别无缓存 region
- 重置 loadState 和 hasHadTerrain 字段强制重新加载
- 避免全量刷新带来的性能开销

---

## 十四、未来规划功能 📝

以下功能已规划但暂未实现：

### 📝 14.1 区块级智能合并
- 完善区块级别的合并逻辑
- 支持强制更新覆盖选项
- 更智能的合并策略（检测区块更新时间）

### 📝 14.2 完整性检查优化
- 验证数据完整性
- 部分区块补全策略

---

## 十五、技术实现亮点 ✅

### ✅ 15.1 独立解析器
- 纯 Java 标准库实现
- 无 Minecraft API 依赖（服务端）
- 支持 GZIP/ZLIB 压缩
- 高效的位数组解析

### ✅ 15.2 Xaero 格式兼容
- 完整的格式版本支持（6.8）
- 调色板压缩策略
- Overlay 层累加机制
- 光照值编码

### ✅ 15.3 网络优化
- 分批传输避免超时
- 速率限制可配置
- 断点续传支持
- 进度实时反馈

### ✅ 15.4 客户端集成
- 反射操作 Xaero 内部类
- 选择性重载优化
- Chunk Update 暂停/恢复
- 缓存自动清理

### ✅ 15.5 Mod 方块支持
- 注册表 API 自动识别
- 渲染形状检测
- 标签检测
- 启发式规则兜底

### ✅ 15.6 增量合并机制
- 区块级增量合并
- 保留客户端已探索区块
- 区域完整性检测
- 合并统计反馈

### ✅ 15.7 哈希值同步
- CRC32 哈希比对
- 内容一致性检测
- 避免重复传输相同文件
- 保留客户端探索成果

---

**文档版本**: 1.3
**最后更新**: 2026-05-19
**模组版本**: MapSyncer for XaeroWorldmap NeoForge 1.21.X

**状态标记说明**：✅ 已实现 | ⏳ 未实现/部分实现 | 📝 规划中 | ⚠️ 已知问题 | 🧪 未测试 | 🧪 未测试

**近期更新**:
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

### 🧪 2026-05-19 更新（未测试）

- 🧪 feat(server): 地狱维度使用分层洞穴模式（CAVE mode），起始高度 Y=90
  - RegionConverterStandalone 支持 CaveModeParams 参数
  - ConversionOrchestrator 根据维度类型选择光照模式和洞穴参数
  - 地狱使用 LightMode.CAVE，其他维度使用 LightMode.SURFACE
- 🧪 refactor: 创建统一的 DimensionPathMapping 维度路径映射类
  - 支持文件系统目录、Xaero 目录、ResourceLocation path 双向转换
  - 原版维度映射：the_nether → DIM-1, the_end → DIM1, overworld → .
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