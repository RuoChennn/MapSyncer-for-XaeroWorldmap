# Xaero 客户端 "Convert All Region" 分析与服务器端策略设计

## 一、客户端 "Convert All Region" 实现分析

### 1.1 功能本质

客户端的 "Convert All Region" 实际上是 **"Full Resave"（完全重保存）** 功能，作用是将 Minecraft 原始世界保存文件（`.mca` 区域文件）转换为 Xaero 专属地图格式（`.zip` 文件）。

**关键入口点**：
- 文件位置：`xaero/map/gui/GuiWorldMapSettings.java`
- 菜单项：`gui.xaero_full_resave`（完全重保存）
- 工具提示：`gui.xaero_box_full_resave`

### 1.2 核心执行链路

```
用户点击按钮
    │
    ▼
MapDimension.startFullMapReload(caveLayer, resave=true, mapProcessor)
    │  创建 MapFullReloader 实例，传入所有已检测的 RegionDetection 迭代器
    │
    ▼
MapFullReloader.onRenderProcess()   [每渲染帧调用一次]
    │
    ├── 获取下一个 RegionDetection
    ├── 获取/创建对应的 MapRegion
    ├── 设置 resaving=true, beingWritten=true
    ├── MapSaveLoad.requestLoad(region, "full reload")
    │       │
    │       ▼
    │   MapSaveLoad.loadRegion()
    │       │
    │       ├── 多人模式: 从 .zip 文件加载
    │       └── 单人模式(resave): WorldDataHandler.buildRegion()
    │               │
    │               ▼
    │           WorldDataReader.buildRegion()
    │               ├── 读取 ServerLevel 的 RegionFile (.mca)
    │               ├── 遍历 8x8 tile chunks
    │               ├── 解析 NBT chunk data
    │               ├── 构建 MapBlock (方块状态/高度/光照/生物群系)
    │               └── 填充 MapRegion 内存结构
    │
    ▼ (加载完成后)
MapSaveLoad.run() 的保存阶段
    │
    ├── saveRegion()
    │   ├── 创建 .zip.temp 临时文件
    │   ├── 写入 version header (0xFF + major<<16|minor, 当前 6.8)
    │   ├── 构建 BlockState palette 和 Biome palette
    │   ├── 遍历 8x8 chunks → 4x4 tiles → 16x16 blocks
    │   ├── 序列化每个 MapBlock (parameters + state + overlays + biome)
    │   └── .zip.temp → .zip (原子替换)
    │
    ▼ (所有 region 完成后)
MapDimension
    ├── addMultiworldChecked("cm$converted")
    ├── setMultiworldName("cm$converted", "Converted World Save")
    └── saveConfigUnsynced()
```

### 1.3 关键类关系图

```
MapProcessor (主处理器)
    ├── MapSaveLoad (保存/加载管理器)
    │   ├── RegionDetection (区域检测数据)
    │   ├── MapRegion (地图区域)
    │   └── cacheToConvertFromTemp (临时缓存转换队列)
    │
    ├── MapFullReloader (完全重加载器)
    │   └── CONVERTED_WORLD_SAVE_MW = "cm$converted"
    │
    ├── MapWorld (世界数据管理)
    │   └── MapDimension (维度数据)
    │       ├── LayeredRegionManager (层级区域管理器)
    │       │   └── MapLayer (地图层级)
    │       │       ├── LeveledRegionManager (分级区域管理器)
    │       │       └── RegionHighlightExistenceTracker (区域高亮追踪器)
    │       └── MapFullReloader (当前激活的重加载器)
    │
    └── WorldDataHandler (世界数据处理)
        └── WorldDataReader (世界数据读取器)
            └── RegionFile (Minecraft 区域文件)
```

### 1.4 Region 层级结构

```
Level 3: BranchLeveledRegion (根分支) - 4096x4096 方块范围
    ↓
Level 2: BranchLeveledRegion - 2048x2048 方块范围
    ↓
Level 1: BranchLeveledRegion - 1024x1024 方块范围
    ↓
Level 0: MapRegion (叶子区域) - 512x512 方块
    ├── MapTileChunk (8x8 块) - 64x64 方块
    │   └── MapTile (4x4 块) - 16x16 方块
    │       └── MapBlock (像素数据)
    └── LeafRegionTexture (纹理数据)
```

### 1.5 MapRegion 数据结构

```
MapRegion (512x512 方块, 一个 region)
  └── MapTileChunk[8][8] (64x64 方块)
        └── MapTile[4][4] (16x16 方块, 一个 chunk section)
              └── MapBlock[16][16] (1x1 方块, 一个像素)
                    ├── BlockState state    — 方块状态
                    ├── short height        — 底部高度
                    ├── short topHeight     — 顶部高度
                    ├── ArrayList<Overlay>  — 覆盖层（水、透明方块）
                    ├── ResourceKey<Biome>  — 生物群系
                    ├── byte light          — 光照等级
                    └── byte glowing        — 是否发光
```

### 1.6 关键类文件路径汇总

| 类名 | 文件路径 |
|------|---------|
| MapFullReloader | `xaero/map/MapFullReloader.java` |
| MapSaveLoad | `xaero/map/file/MapSaveLoad.java` |
| MapProcessor | `xaero/map/MapProcessor.java` |
| MapRegion | `xaero/map/region/MapRegion.java` |
| RegionDetection | `xaero/map/file/RegionDetection.java` |
| WorldDataReader | `xaero/map/file/worldsave/WorldDataReader.java` |
| WorldDataHandler | `xaero/map/file/worldsave/WorldDataHandler.java` |
| LeveledRegion | `xaero/map/region/LeveledRegion.java` |
| BranchLeveledRegion | `xaero/map/region/BranchLeveledRegion.java` |
| LayeredRegionManager | `xaero/map/region/LayeredRegionManager.java` |
| MapLayer | `xaero/map/region/MapLayer.java` |
| MapDimension | `xaero/map/world/MapDimension.java` |
| GuiWorldMapSettings | `xaero/map/gui/GuiWorldMapSettings.java` |
| RegionTexture | `xaero/map/region/texture/RegionTexture.java` |
| MapBlock | `xaero/map/region/MapBlock.java` |
| Misc | `xaero/map/misc/Misc.java` |

---

## 二、数据流向完整链路

### 2.1 转换流程（单人模式 → Xaero 格式）

#### 阶段 1: 检测 Regions
```
World Save Dir (region/*.mca)
    → detectRegions()
        ├── 匹配文件名模式: "^r\\.(-{0,1}[0-9]+)\\.(-{0,1}[0-9]+)\\.mc[ar]$"
        ├── 创建 RegionDetection 对象
        └── 存入 MapLayer.detectedRegions
```

#### 阶段 2: 启动 Full Resave
```
GuiWorldSettings (用户点击按钮)
    → mapDimension.startFullMapReload(resave=true)
        └── 创建 MapFullReloader
```

#### 阶段 3: 遍历处理每个 Region
```
MapFullReloader.onRenderProcess() [每帧]
    ├── 获取 RegionDetection
    ├── 创建/获取 MapRegion
    ├── setResaving(true), setBeingWritten(true)
    └── requestLoad(region, "full reload")
```

#### 阶段 4: 加载 Region 数据
```
MapSaveLoad.loadRegion()
    ├── 获取 RegionFile (.mca)
    └── WorldDataHandler.buildRegion()

WorldDataReader.buildRegion()
    ├── 读取 Chunk NBT 数据
    ├── 解析 sections (方块状态 palette)
    ├── 解析高度图和光照数据
    ├── 构建 MapBlock (每个像素)
    └── 填充生物群系数据
```

#### 阶段 5: 保存为 Xaero 格式
```
MapSaveLoad.saveRegion()
    ├── 创建临时 .zip.temp 文件
    ├── 写入版本信息 (major=6, minor=8)
    ├── 构建 palette (方块状态、生物群系)
    ├── 序列化每个 MapBlock
    │     ├── state (NBT 或 palette index)
    │     ├── height, topHeight
    │     ├── light, overlays
    │     └── biome (ResourceKey)
    └── 移动到最终 .zip 文件
```

#### 阶段 6: 完成转换
```
MapFullReloader 完成所有 regions 后
    ├── mapDimension.addMultiworldChecked("cm$converted")
    ├── setMultiworldName("gui.xaero_converted_world_save")
    └── saveConfigUnsynced()
```

### 2.2 缓存转换机制

```
原始缓存: *.xwmc
           ↓
convertCacheToOutdated()
           ↓
转换后: *.xwmc.outdated
           ↓
用途: 当检测到缓存过期时，将缓存文件重命名为 .outdated
      触发重新生成缓存
```

---

## 三、关键数据结构详解

### 3.1 RegionDetection — 区域检测信息

```java
public class RegionDetection implements MapRegionInfo, ILinkedChainNode<RegionDetection> {
    private int initialVersion;     // 初始版本
    private String worldId;         // 世界标识
    private String dimId;           // 维度标识
    private String mwId;            // 多世界标识
    private int regionX;            // 区域 X 坐标
    private int regionZ;            // 区域 Z 坐标
    private boolean hasHadTerrain;  // 是否有地形数据
    private File regionFile;        // 区域文件
    private File cacheFile;         // 缓存文件
    private int[][] cachedTextureVersions; // 缓存纹理版本
}
```

### 3.2 MapBlock — 像素数据

```java
public class MapBlock extends MapPixel {
    private BlockState state;           // 方块状态
    private short height;               // 底部高度
    private short topHeight;            // 顶部高度
    private ArrayList<Overlay> overlays;// 覆盖层（水、透明方块）
    private ResourceKey<Biome> biome;   // 生物群系
    private byte light;                 // 光照等级
    private boolean glowing;            // 是否发光
    private byte verticalSlope;         // 垂直斜率
    private byte diagonalSlope;         // 对角斜率
}
```

### 3.3 MapFullReloader — 完全重加载器

```java
public class MapFullReloader {
    public static final String CONVERTED_WORLD_SAVE_MW = "cm$converted";
    private final int caveLayer;
    private final boolean resave;
    private final Iterator<RegionDetection> regionDetectionIterator;
    private final Deque<RegionDetection> retryLaterDeque;
    private final MapDimension mapDimension;
    private final MapProcessor mapProcessor;
    private MapRegion lastRequestedRegion;

    // 核心方法：每帧处理一个 region
    public void onRenderProcess() {
        // 1. 检查是否可以加载下一个 region
        // 2. 获取或创建 MapRegion
        // 3. 设置 resaving 和 beingWritten 标志
        // 4. 触发加载请求
        // 5. 完成后添加 "cm$converted" 多世界标识
    }
}
```

---

## 四、文件格式详解

### 4.1 Xaero 地图文件格式 (.zip)

```
文件结构:
├── region.xaero (ZipEntry)
│   ├── 版本头
│   │   ├── byte: 0xFF (版本标记)
│   │   └── int: fullVersion (major << 16 | minor)
│   │       └── current: 0x00060008 (major=6, minor=8)
│   │
│   ├── Chunk 数据 (8x8)
│   │   ├── byte: chunkCoords (o << 4 | p)
│   │   ├── Tile 数据 (4x4 per chunk)
│   │   │   ├── int: tileIndex (-1 表示空)
│   │   │   ├── MapBlock 数据 (16x16 per tile)
│   │   │   │   ├── int: parameters
│   │   │   │   │   ├── bit 0: isGrass
│   │   │   │   │   ├── bit 1: hasOverlays
│   │   │   │   │   ├── bits 8-11: light
│   │   │   │   │   ├── bits 12-23: height
│   │   │   │   │   ├── bit 20: paletteNew
│   │   │   │   │   ├── bit 22: biomePaletteNew
│   │   │   │   │   └── bit 24: hasTopHeight
│   │   │   │   ├── BlockState 数据
│   │   │   │   │   ├── palette index 或 NBT 数据
│   │   │   │   ├── Overlay 数据
│   │   │   │   ├── Biome 数据
│   │   │   │   │   ├── palette index 或 UTF string
│   │   │   │   ├── byte: worldInterpretationVersion
│   │   │   │   ├── int: caveStart
│   │   │   │   └── byte: caveDepth
│   │   │   └── ...
│   │   └── ...
│   └── ...
└── [文件结束]
```

### 4.2 缓存文件格式 (.xwmc)

```
文件结构:
├── cache.xaero (ZipEntry)
│   ├── int: fullVersion (0x00010024)
│   ├── 元数据
│   │   ├── byte: textureCoords (x << 4 | z)
│   │   ├── int: cachedTextureVersion
│   │   └── ...
│   ├── 生物群系 Palette
│   ├── 纹理数据
│   │   ├── byte: isCompressed
│   │   ├── int: format
│   │   ├── int: length
│   │   ├── byte[]: colorBuffer
│   │   ├── boolean: hasLight
│   │   ├── long[]: heightValues (1024)
│   │   ├── long[]: topHeightValues (1024)
│   │   └── biomeIndexStorage
│   └── ...
```

### 4.3 MapSaveLoad 核心方法签名

```java
public class MapSaveLoad {
    private static final int currentSaveMajorVersion = 6;
    private static final int currentSaveMinorVersion = 8;
    public static final int currentCacheSaveMajorVersion = 1;
    public static final int currentCacheSaveMinorVersion = 24;

    private boolean saveRegion(MapRegion region, boolean debugConfig, int extraAttempts);
    public boolean loadRegion(MapRegion region, HolderLookup<Block> blockLookup,
                              Registry<Block> blockRegistry, Registry<Fluid> fluidRegistry,
                              BiomeGetter biomeGetter, boolean debugConfig, int extraAttempts);
    public void detectRegions(int attempts);
    public void detectRegionsFromFiles(...);
    public void requestLoad(MapRegion region, String reason);
}
```

---

## 五、客户端核心设计特点总结

| 特点 | 说明 |
|------|------|
| **逐帧处理** | `onRenderProcess()` 每帧只处理 1 个 region，避免阻塞渲染 |
| **重试机制** | 获取不到 MapRegion 时放入 `retryLaterDeque`，后续重试 |
| **Palette 压缩** | BlockState 和 Biome 都使用 palette 索引压缩，减少文件大小 |
| **原子写入** | 先写 `.zip.temp`，成功后替换 `.zip`，避免中间状态 |
| **标识系统** | 转换后的数据用 `"cm$converted"` 作为 multiworld 标识 |
| **缓存管理** | `.xwmc.outdated` 后缀标记过期缓存 |
| **版本控制** | 使用 major/minor 版本号管理数据格式兼容性 |

---

## 六、服务器端调用策略设计

### 6.1 核心设计原则

**不需要复用客户端的完整对象模型**。客户端的 `MapFullReloader` 依赖大量客户端特有类（`Minecraft`、渲染系统、UI 系统），服务器端应该提取核心逻辑，设计为**纯服务端独立转换工具**。

### 6.2 整体架构

```
┌────────────────────────────────────────────────────────────────┐
│                      服务器端                                   │
│                                                                │
│  ┌──────────────┐    ┌──────────────────┐    ┌──────────────┐  │
│  │ RegionScanner│───►│ RegionConverter  │───►│ XaeroWriter  │  │
│  │ 扫描.mca文件  │    │ 读取→转换数据     │    │ 写入.zip文件  │  │
│  └──────────────┘    └──────────────────┘    └──────────────┘  │
│         │                    │                       │          │
│         ▼                    ▼                       ▼          │
│   文件发现             NBT→MapBlock              序列化输出      │
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │              ConversionOrchestrator (编排器)               │  │
│  │  - 控制转换进度/并发/错误恢复                                │  │
│  │  - 管理 ServerLevel 生命周期                                │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │              CacheGenerateCommand (命令入口)                │  │
│  │  - 玩家/控制台触发 /xaeromap generate                      │  │
│  │  - 支持全维度/指定region/增量转换                            │  │
│  └──────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────┘
```

### 6.3 各组件职责

#### 6.3.1 RegionScanner — 区域文件扫描器

```java
public class RegionScanner {
    // 输入: 服务器世界目录 (e.g., world/region/)
    // 输出: List<RegionCoords> (regionX, regionZ 列表)

    // 扫描规则: 匹配 r.{x}.{z}.mca 或 r.{x}.{z}.mcr
    // 参考客户端: MapSaveLoad.detectRegionsFromFiles()
    // 正则: "^r\\.(-{0,1}[0-9]+)\\.(-{0,1}[0-9]+)\\.mc[ar]$"
}
```

#### 6.3.2 RegionConverter — 核心转换器

```java
public class RegionConverter {
    // 输入: ServerLevel + RegionCoords
    // 输出: MapRegion 内存对象 (或更简化的中间表示)

    // 核心逻辑参考: WorldDataReader.buildRegion()
    // 1. 通过 ServerLevel.getChunkSource().getRegionFile() 获取 RegionFile
    // 2. 遍历 region 内 32x32 个 chunk (8x8 tile chunks × 4×4 tiles)
    // 3. 读取每个 chunk 的 NBT 数据
    // 4. 解析 BlockState palette (直接/间接)
    // 5. 提取高度图 (MOTION_BLOCKING_NO_LEAVES)
    // 6. 提取生物群系数据 (3D 或柱状)
    // 7. 构建方块级别的 MapBlock 数据
}
```

#### 6.3.3 XaeroWriter — Xaero 格式写入器

```java
public class XaeroWriter {
    // 输入: MapRegion 数据
    // 输出: {x}_{z}.zip 文件 (包含 region.xaero 条目)

    // 格式完全参考客户端 MapSaveLoad.saveRegion()
    // 1. 创建 {x}_{z}.zip.temp
    // 2. 写入 ZipEntry "region.xaero"
    // 3. 写入 header: 0xFF + 0x00060008 (v6.8)
    // 4. 构建 BlockState palette (HashMap<BlockState, Integer>)
    // 5. 构建 Biome palette (HashMap<ResourceKey<Biome>, Integer>)
    // 6. 遍历 chunks → tiles → blocks 序列化
    // 7. 原子替换 .zip.temp → .zip
}
```

#### 6.3.4 ConversionOrchestrator — 转换编排器

```java
public class ConversionOrchestrator {
    // 职责:
    // 1. 控制转换流程 (扫描 → 转换 → 写入 的编排)
    // 2. 并发控制 (使用线程池, 限制同时处理的 region 数量)
    // 3. 进度追踪 (已处理/总数/错误数)
    // 4. 错误恢复 (单个 region 失败不中断整体流程)
    // 5. 增量转换 (跳过已存在的 .zip 文件)
    // 6. 输出目录管理 (写入到 server_map_cache/{dim}/)
}
```

### 6.4 触发方式设计

#### 方式一: 服务器命令 (推荐)

```
/xaeromap generate              — 转换当前世界所有维度
/xaeromap generate overworld    — 仅转换主世界
/xaeromap generate --force      — 强制重新转换 (覆盖已有缓存)
/xaeromap generate --region 0 0 — 转换指定 region
/xaeromap status                — 查看转换进度
```

#### 方式二: 服务器启动时自动转换

在 `ServerStartingEvent` 中检查缓存完整性，缺失时自动触发转换。

#### 方式三: 玩家加入时按需转换

玩家首次加入时，检测其位置附近的 region 是否已转换，未转换则触发局部转换。

### 6.5 与客户端通信策略

转换完成后，服务器需要告知客户端缓存已就绪：

```
服务器启动/转换完成
    │
    ▼
将 .zip 文件放入标准目录:
  server_map_cache/{dim}/{x}_{z}.zip
    │
    ▼
客户端请求地图时 (已有的 SyncHandler 流程):
  1. 客户端发送 SyncRequest
  2. 服务器找到对应 .zip 文件
  3. 通过网络协议发送 map data (已有的 ChunkMapData 协议)
  4. 客户端接收并写入 Xaero 格式
```

### 6.6 与现有 Mod 代码的集成点

| 现有类 | 集成方式 |
|--------|----------|
| `CacheGenerateCommand` | 添加命令入口，调用 `ConversionOrchestrator` |
| `ChunkScanner` | 替换为新的 `RegionScanner`，扫描服务端 `.mca` 文件 |
| `MapDataConverter` | 替换为新的 `RegionConverter`，处理 NBT→MapBlock |
| `MapFileCache` | 替换为新的 `XaeroWriter`，写入 .zip 格式 |
| `ServerSyncHandler` | 直接复用，从缓存目录读取 .zip 发送给客户端 |

### 6.7 关键注意事项

1. **BlockState 颜色映射**: 服务端需要内置方块→颜色的映射表（参考 `BlockColorMapper`），因为转换的核心目的是将方块状态转为可视化的地图像素颜色

2. **无需完整复制 MapBlock**: 服务端只需要提取转换所需的最少数据（方块类型→颜色、高度、生物群系），不需要客户端 MapBlock 的所有字段（如 slope、glowing 等渲染相关属性）

3. **并发安全**: 服务端可能有多个玩家同时请求，需要保证转换过程的线程安全

4. **版本兼容**: 输出的 `.zip` 文件格式必须严格匹配客户端期望的 v6.8 格式（major=6, minor=8）

5. **文件路径规范**:
   - 输入: `{worldDir}/region/r.{x}.{z}.mca`
   - 输出: `{cacheDir}/{dim}/{x}_{z}.zip`
   - 临时: `{cacheDir}/{dim}/{x}_{z}.zip.temp`

6. **WorldDataReader.buildRegion 的核心逻辑**（服务端需要复用的部分）:
   - 通过 `ChunkMap` 和 `RegionFile` 读取 chunk 数据
   - 遍历 8x8 的 tile chunks
   - 解析 chunk sections 的方块状态 palette
   - 构建高度和光照信息
   - 填充生物群系数据
