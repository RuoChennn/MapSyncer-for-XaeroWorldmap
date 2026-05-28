# Xaero's World Map region.xaero 文件格式规范

## 概述

`region.xaero` 文件是 Xaero's World Map mod 用于存储地图区域数据的核心文件格式。每个 region 文件覆盖 512x512 个方块区域（对应 8x8 个 MapTileChunk，每个 MapTileChunk 包含 4x4 个 MapTile，每个 MapTile 包含 16x16 个像素）。

## 文件结构

### 存储容器
- **现代格式**: ZIP 文件，内部包含一个名为 `region.xaero` 的条目
- **旧格式**: 直接的 `.xaero` 二进制文件（会在加载时自动转换为 `.zip` 格式）
- **文件命名**: `{regionX}_{regionZ}.zip`，例如 `1_-2.zip`
- **存储路径**: `{worldId}/{dimId}/{mwId}/` 或 `{worldId}/{dimId}/{mwId}/caves/{layer}/`

### 坐标系统
- **Region 坐标**: 1 个 region = 8x8 TileChunks = 512x512 方块
- **TileChunk 坐标**: 1 个 TileChunk = 4x4 Tiles = 64x64 方块
- **Tile 坐标**: 1 个 Tile = 16x16 方块（对应一个 Minecraft chunk）
- **Region X/Z 计算**: `regionX = tileChunkX >> 3`, `regionZ = tileChunkZ >> 3`

## 二进制数据格式

### 文件头
| 字段 | 类型 | 值 | 说明 |
|------|------|-----|------|
| 版本标识 | byte | 0xFF (255) | 标识新版本格式开始 |
| 完整版本号 | int | 393224 (0x00060008) | major=6, minor=8，编码为 `(major << 16) | minor` |

### Chunk 数据结构
文件头之后是循环写入的 chunk 数据：

```
对于每个 chunk (o=0..7, p=0..7):
    chunkCoords: byte = o << 4 | p   // chunk 坐标编码
    如果 chunk 存在且应包含在保存中:
        对于每个 tile (i=0..3, j=0..3):
            tileIndicator: int
            如果 tile != null 且 tile.isLoaded():
                // 写入像素数据
                对于每个像素 x=0..15, z=0..15:
                    像素数据 (savePixel 格式)
                worldInterpretationVersion: byte
                writtenCaveStart: int
                writtenCaveDepth: byte
            否则:
                tileIndicator = -1  // 表示空 tile
```

### Chunk 坐标编码
- `chunkCoords = o << 4 | p`
- `o = chunkCoords >> 4` (局部 X 坐标，0-7)
- `p = chunkCoords & 0xF` (局部 Z 坐标，0-7)

---

## 像素数据格式 (MapBlock)

每个像素 (MapBlock) 的存储格式如下：

### 参数字段 (parametres)
一个 `int` (4字节) 编码多个标志位：

```
位域布局:
bit 0:        isGrass? 0=草方块, 1=非草方块
bit 1:        hasOverlays? 1=有覆盖层
bits 2-3:     savedColourType (旧版本)
bits 8-11:    light (光照值 0-15)
bits 12-19:   height 低 8 位
bit 20:       hasBiome? 1=有生物群系数据
bit 21:       blockStateNewInPalette? 1=新的方块状态调色板条目
bit 22:       biomeNewInPalette? 1=新的生物群系调色板条目
bit 23:       biomeAsInt? (旧版本编码)
bit 24:       topHeightDifferent? 1=topHeight != height
bits 25-28:   height 高 4 位扩展 (signed 12-bit)
```

### 数据写入顺序

#### 1. BlockState 数据
如果 `isGrass == 0` (非草方块):
- 如果 `blockStateNewInPalette == 1`:
  - 写入 NBT 格式的 BlockState 数据
  - 将此 BlockState 添加到调色板
- 否则:
  - 写入调色板索引 (int)

#### 2. TopHeight 数据
如果 `topHeightDifferent == 1`:
- 写入 topHeight (byte)

#### 3. Overlay 数据
如果 `hasOverlays == 1`:
- 写入 overlay 数量 (byte)
- 对于每个 overlay:
  - overlay 数据 (Overlay 格式)

#### 4. Biome 数据
如果 `hasBiome == 1`:
- 如果 `biomeNewInPalette == 1`:
  - 写入 biome 字符串 (`writeUTF`，格式为 `ResourceLocation.toString()`，如 `"minecraft:plains"`)
  - 将此 biome 添加到调色板 (`regionSaveBiomePalette.put(pixelBiome, regionSaveBiomePalette.size())`)
- 否则:
  - 写入调色板索引 (int)

> **注意**: biome 字符串不是集中存储在文件头，而是**内联写入在每个像素的数据流中**。首次出现的 biome 会在其对应像素的位置写入完整字符串，同时记录到动态构建的调色板中；后续相同 biome 的像素只写入调色板索引 (int)。

---

## Overlay (覆盖层) 数据格式

覆盖层用于表示透明方块如水、玻璃等叠加在基础方块上的效果。

### Overlay 参数字段
```
位域布局:
bit 0:        isWater? 0=水, 1=非水方块
bits 4-7:     light (光照值 0-15)
bits 8-10:    savedColourType (旧版本)
bit 11:       reserved
bits 11-14:   opacity (透明度 0-15)
bit 15:       hasExplicitOpacity? (旧版本)
```

### Overlay 数据写入
如果 `isWater == 0`:
- 如果是新调色板条目:
  - 写入 BlockState NBT 数据
- 否则:
  - 写入调色板索引 (int)

---

## Tile 元数据

每个有效的 MapTile 后面附加：

| 字段 | 类型 | 说明 |
|------|------|------|
| worldInterpretationVersion | byte | 世界解释版本，当前为 1 |
| writtenCaveStart | int | 洞穴起始高度，Integer.MAX_VALUE 表示非洞穴模式 |
| writtenCaveDepth | byte | 洞穴深度 |

---

## 调色板机制

文件使用两种调色板来压缩重复数据：

### BlockState 调色板
- 在写入过程中动态构建
- 首次出现的 BlockState 写入完整 NBT 数据
- 后续引用只写入索引 (int)

### Biome 调色板
- 在写入过程中动态构建 (`regionSaveBiomePalette: HashMap<ResourceKey<Biome>, Integer>`)
- 首次出现的 biome 写入完整字符串 (`writeUTF`，格式为 `ResourceLocation.toString()`，如 `"minecraft:plains"`)
- 后续引用只写入索引 (int)
- 字符串**内联存储在像素数据流中**，不是集中存储在文件头或文件尾

---

## 数据层级关系

```
MapRegion (512x512 方块)
├── 8x8 MapTileChunk
│   ├── 4x4 MapTile
│   │   ├── 16x16 MapBlock (像素)
│   │   │   ├── BlockState
│   │   │   ├── height
│   │   │   ├── topHeight
│   │   │   ├── light
│   │   │   ├── biome
│   │   │   └── Overlay[] (可选)
```

### 尺寸对照表
| 单位 | 方块尺寸 | 数据点数量 |
|------|----------|------------|
| MapBlock | 1x1 | 1 |
| MapTile | 16x16 | 256 |
| MapTileChunk | 64x64 | 4,096 |
| MapRegion | 512x512 | 262,144 |

---

## 缓存文件格式 (.xwmc)

缓存文件 `cache.xaero` 存储在 `.xwmc` 文件中，结构与 region 文件类似但有额外元数据。

### 缓存文件头
| 字段 | 类型 | 值 | 说明 |
|------|------|-----|------|
| 完整版本号 | int | 65560 | cache 专用版本 |

### 缓存元数据
- cacheHashCode: int
- reloadVersion: int
- highlightsHash: int
- caveStart: int
- caveDepth: int
- 纹理版本数据 (8x8)

---

## 实际生成流程

### 客户端生成 (MapWriter)
1. 从 Minecraft ClientLevel 获取 chunk 数据
2. 对每个 chunk 遍历 16x16 方块
3. 计算高度、光照、生物群系
4. 处理透明覆盖层
5. 存储到 MapTile -> MapTileChunk -> MapRegion

### 服务端兼容生成
服务端需要模拟相同流程：
1. 从 LevelChunk 获取方块数据
2. 使用 Heightmap 获取表面高度
3. 计算光照值（block light）
4. 通过 Registry 获取 biome
5. 按照 region.xaero 格式编码输出

---

## 关键常量

```java
// 版本
currentSaveMajorVersion = 6
currentSaveMinorVersion = 8
currentCacheSaveMajorVersion = 1
currentCacheSaveMinorVersion = 24

// 尺寸
MapRegion.SIDE_LENGTH = 8          // 8x8 TileChunks
MapTileChunk.SIDE_LENGTH = 4       // 4x4 Tiles per TileChunk
MapTile.尺寸 = 16x16               // 方块

// 特殊值
MapWriter.NO_Y_VALUE = Short.MAX_VALUE  // 无有效高度
caveStart = Integer.MAX_VALUE           // 非洞穴模式
caveStart = Integer.MIN_VALUE           // 全洞穴模式
```

---

## 参考源文件

| 文件 | 主要功能 |
|------|----------|
| MapSaveLoad.java | 保存/加载 region 文件 |
| MapRegion.java | Region 数据结构 |
| MapTileChunk.java | TileChunk 数据结构 |
| MapTile.java | Tile 数据结构 |
| MapBlock.java | 像素数据结构 |
| Overlay.java | 覆盖层数据结构 |
| MapWriter.java | 地图数据生成 |
| LeveledRegion.java | 基础区域抽象类 |

---

*文档基于 Xaero's World Map NeoForge 1.21.1-1.40.11 反编译代码分析*
*生成日期: 2026/05/09*