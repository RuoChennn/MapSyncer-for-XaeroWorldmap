# Xaero's World Map 未加载区块地图生成机制分析

## 概述

Xaero's World Map 对于**未加载区块（unloaded chunks）**的地图生成，采用的核心策略是：**直接从服务端世界存档（region 文件）中读取区块 NBT 数据来构建地图，而无需区块被加载到内存中**。

---

## 两种地图数据来源

### 1. 多人游戏模式（Normal Map Data）
- 数据来源：客户端实际探索到的区块
- 通过 `MapTileChunk` 实时记录玩家视野内的方块信息
- 保存为 `.zip` 格式的地图文件（`region.xaero`）

### 2. 单机/世界存档模式（World Save Mode）
- 数据来源：服务端世界存档的 `.mca`（Anvil）region 文件
- **即使区块未被加载到客户端内存，也能生成地图**
- 这是处理未加载区块的核心机制

---

## 核心处理流程

### 步骤一：区域检测（Region Detection）

**文件**: `MapSaveLoad.java` (行 361-431)

```
detectRegions() 方法扫描世界存档的 region 文件夹
→ 匹配正则: ^r\.(-{0,1}[0-9]+)\.(-{0,1}[0-9]+)\.mc[ar]$
→ 为每个 .mca/.mcr 文件创建 RegionDetection 对象
→ 添加到 LayeredRegionManager 中
```

关键逻辑：
- 如果 `isUsingWorldSave()` 为 true，则扫描世界存档目录的 `region/` 文件夹
- 如果 `isNormalMapData()` 为 true（多人模式），则扫描已保存的 `.zip` 地图文件

### 步骤二：通过 WorldDataHandler 构建区域

**文件**: `WorldDataHandler.java` (行 74-102)

```
buildRegion() 方法:
  1. 通过 CapabilityGetter 获取 ServerWorldCapabilities
  2. 检查 serverCaps.loaded 确保服务端世界已加载
  3. 调用 WorldDataReader.buildRegion() 实际读取区块数据
```

关键判断：
```java
if (serverCaps.loaded) {
    return reader.buildRegion(...);
} else {
    return Result.CANCEL;  // 服务端世界未加载则取消
}
```

### 步骤三：从存档读取区块 NBT 数据

**文件**: `WorldDataReader.java` (行 174-259)

这是最核心的方法 `buildRegion()`，流程如下：

```
1. 提交 save 操作确保存档数据已刷新到磁盘:
   serverWorld.getServer().submit(() -> serverWorld.getChunkSource().save(false)).join()

2. 获取 ChunkMap (chunkManager):
   ChunkMap chunkManager = serverWorld.getChunkSource().chunkMap

3. 遍历区域周围 10x10 的 chunk（含边界外扩）:
   for (int i = -1; i < 9; ++i)
     for (int j = -1; j < 9; ++j)

4. 通过 ChunkStorage.read() 异步读取每个区块的 NBT 数据:
   chunkNBTCompounds[i] = chunkManager.read(new ChunkPos(...))

5. 等待 CompletableFuture 获取 NBT compound
6. 调用 buildTile() 解析 NBT 并填充 MapTile
```

### 步骤四：解析 NBT 构建地图方块

**文件**: `WorldDataReader.java` (行 328-569)

`buildTile()` 方法处理单个区块 NBT：

```
1. 检查区块状态 (ChunkStatus):
   - 区块生成按顺序有多个状态：
     empty → structure_starts → structure_references → biomes → noise → surface → ...
   - 只有 surface 及之后的状态才有实际地形数据
   - 如果 status < BIOMES → 返回 false（不生成地图）
   - 如果 status < FEATURES → 返回 false（地形特征未生成）

2. 读取 Heightmap:
   - 优先使用 MOTION_BLOCKING_NO_LEAVES（包含水方块）
   - 备用 WORLD_SURFACE（不包含水方块）
   - Wiki 编码公式：i = x + 16*z
   - 确定每个 x,z 位置的地表最高 y 坐标

3. 遍历 sections（区块段）:
   - 从最高 section 向下遍历
   - 解析 palette（调色板）和 block_states（方块状态位数组）

4. 从上往下扫描每个方块:
   - 如果方块是空气 → 标记 underair[pos] = true
   - 如果 underair 为 false 且在洞穴模式 → 跳过（已在地面以下）
   - 找到第一个可见的非空气方块 → 写入 MapBlock

5. 特殊处理:
   - 液体覆盖层（Overlay）：水、岩浆等作为半透明层叠加
   - 亮度读取：Wiki 公式 (data[yzx >> 1] >> (4 * (yzx & 1))) & 0xF
   - 发光方块：标记 glowing
   - 生物群系：从 NBT 的 sections.biomes 读取
```

**区块状态详细说明**（Wiki 规范）：

| 状态索引 | 状态名称 | 说明 | 是否处理 |
|---------|---------|------|---------|
| 0 | empty | 空 | 跳过 |
| 1 | structure_starts | 结构开始 | 跳过 |
| 2 | structure_references | 结构引用 | 跳过 |
| 3 | biomes | 生物群系 | 跳过 |
| 4 | noise | 噪声（无地形） | 跳过 |
| 5 | surface | 表面（开始有地形） | ✓ |
| 6 | carvers | 雕刻器 | ✓ |
| 7 | features | 特性 | ✓ |
| 8 | light | 光照 | ✓ |
| 9 | spawn | 生成点 | ✓ |
| 10 | heightmaps | 高度图 | ✓ |
| 11 | full | 完成 | ✓ |

### 步骤五：异步填充与渲染

**文件**: `WorldDataReader.java` (行 237-245)

```java
lastFuture = renderExecutor.submit(() -> {
    transferFilledBiomes(topLeftTileChunk, biomeZoomer, biomeRegistry);
    topLeftTileChunk.setToUpdateBuffers(true);
    topLeftTileChunk.setChanged(false);
    topLeftTileChunk.setLoadState((byte)2);
});
```

生物群系的填充通过 `renderExecutor` 异步提交到渲染线程执行。

---

## 占位符机制（Placeholder）

对于确实无法获取数据的区块，Xaero 使用占位符机制：

### PlaceholderBlockGetter
**文件**: `PlaceholderBlockGetter.java`

```java
public class PlaceholderBlockGetter implements BlockGetter {
    private BlockState placeholderState;

    public BlockState getBlockState(BlockPos pos) {
        return this.placeholderState;  // 始终返回占位方块状态
    }
}
```

当需要查询一个未加载的方块状态时，返回预设的 placeholder BlockState。

### Placeholder Biome
**文件**: `BiomeGetter.java` (行 26-30)

```java
public final ResourceKey<Biome> PLACEHOLDER_BIOME = 
    ResourceKey.create(Registries.BIOME, "xaeroworldmap:placeholder_biome");
public final ResourceKey<Biome> UNKNOWN_BIOME = 
    ResourceKey.create(Registries.BIOME, "xaeroworldmap:unknown_biome");
```

当无法从世界获取生物群系时，回退到 `UNKNOWN_BIOME`。

---

## 缓存机制

**文件**: `MapSaveLoad.java` (行 212-234)

未加载区块的地图数据一旦生成，会缓存到：

```
{世界目录}/XaerosWorldMap/{server}/cache_{globalVersion}/
  caves/{layer}/
    {regionX}_{regionZ}.xwmc     ← 缓存文件
    {regionX}_{regionZ}.xwmc.outdated  ← 过期缓存标记
```

加载优先级：
1. 先检查缓存文件（`.xwmc`）
2. 如果没有缓存，从世界存档的 `.mca` 文件读取
3. 如果世界存档也没有，则显示为空白

---

## 关键判断流程图

```
是否需要显示地图区域?
  │
  ├── 有缓存文件 (.xwmc)?
  │     └── YES → 加载缓存 (loadCacheTextures)
  │     └── NO ↓
  │
  ├── 是世界存档模式 (isUsingWorldSave)?
  │     └── YES → 扫描 region/*.mca 文件
  │               → buildRegion() 读取 NBT
  │               → 构建 MapTile → 可显示
  │     └── NO ↓
  │
  └── 是多人游戏模式?
        └── 只加载已探索的 .zip 地图数据
              → 未探索区域 = 空白
```

---

## 总结

| 方面 | 实现方式 |
|------|----------|
| **数据源** | 服务端世界存档的 Anvil region 文件 (.mca) |
| **读取方式** | `ChunkStorage.read(ChunkPos)` 异步读取区块 NBT |
| **是否需要区块加载** | **不需要** — 直接从磁盘 NBT 解析 |
| **高度确定** | 优先 MOTION_BLOCKING_NO_LEAVES，备用 WORLD_SURFACE |
| **区块状态检查** | 只处理 surface 及之后的状态（索引 ≥5） |
| **亮度读取** | Wiki 规范公式 `(data[yzx>>1]>>(4*(yzx&1)))&0xF` |
| **高度图解码** | Wiki 规范公式 `i=x+16*z`，`value=(data[i/u]>>((i%u)*b))&mask+low` |
| **生物群系** | 从 sections 的 biomes 调色板读取 |
| **占位符** | PlaceholderBlockGetter 返回预设方块状态 |
| **缓存格式** | `.xwmc` 自定义压缩格式 |
| **线程模型** | CompletableFuture 异步读取 + renderExecutor 异步处理 |

Xaero 的这个设计使其在单机模式下可以"预览"整个世界的地图（包括未探索区域），而在多人游戏模式下则仅显示玩家实际探索过的区域（除非服务端也安装了 Xaero 模组并提供了缓存下发）。

---

*更新: 2026/05/13 — 基于 Wiki 规范更新高度图解码、亮度读取、区块状态检查*
