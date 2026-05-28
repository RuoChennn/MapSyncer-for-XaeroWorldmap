# Xaero 像素流构成模式分析

## 1. 数据层次结构

Xaero地图数据采用四级层次结构：

```
MapRegion (区域, 128x128 像素)
  └── MapTileChunk (瓦片块, 64x64 像素)
        └── MapTile (瓦片, 16x16 像素)
              └── MapBlock (像素块, 1x1 像素)
```

- **MapRegion**: 128x128 = 8x8 个 MapTileChunk
- **MapTileChunk**: 64x64 = 4x4 个 MapTile
- **MapTile**: 16x16 = 256 个 MapBlock
- **MapBlock**: 最小像素单元

## 2. MapBlock 像素数据结构

每个 `MapBlock` 是一个像素，包含以下字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `state` | BlockState | 方块状态（决定基础颜色） |
| `height` | short | 高度值 |
| `topHeight` | short | 顶部高度值（用于半透明方块） |
| `light` | byte | 光照值 (0-15) |
| `glowing` | boolean | 是否发光 |
| `biome` | ResourceKey<Biome> | 群系信息 |
| `verticalSlope` | byte | 垂直方向坡度 |
| `diagonalSlope` | byte | 对角线方向坡度 |
| `slopeUnknown` | boolean | 坡度是否未知 |
| `overlays` | ArrayList<Overlay> | 半透明叠加层（如水、玻璃） |

## 3. 像素颜色计算流程

### 3.1 基础颜色来源

颜色计算在 `MapPixel.getPixelColours()` 中进行，有两种来源：

1. **纹理颜色** (`updateConfig.blockColors == 0`):
   - 调用 `mapWriter.loadBlockColourFromTexture()` 从方块纹理PNG采样
   - 采样方式：将纹理分成网格，取每个网格中心像素的RGB平均值
   - 格式: `alpha << 24 | red << 16 | green << 8 | blue`

2. **原版MapColor** (`updateConfig.blockColors != 0`):
   - 使用 `state.getMapColor()` 获取Minecraft原版地图颜色
   - 透明度: 液体=191, 冰=216, 普通=127
   - 格式: `a << 24 | colour & 0xFFFFFF`

### 3.2 群系染色

```
if (biomeColorsInVanilla || blockColors == 0):
    biomeColor = blockTintProvider.getBiomeColor(...)
    r = (biomeColor.r) * (baseColor.r / 255)
    g = (biomeColor.g) * (baseColor.g / 255)
    b = (biomeColor.b) * (baseColor.b / 255)
```

### 3.3 发光效果

如果方块发光 (`glowing == true`):
```
minBrightness = 407.0
brightener = max(1.0, minBrightness / (r+g+b))
r *= brightener
g *= brightener
b *= brightener
light = 15
```

### 3.4 叠加层处理

每个overlay按顺序混合：
```
transparency = overlayAlpha / 255.0
intensity = getBlockBrightness(9, overlayLight, sun) * transparency * currentTransparencyMultiplier
overlayRed += overlayR * intensity
overlayGreen += overlayG * intensity
overlayBlue += overlayB * intensity
sun -= overlayOpacity  // 光线被遮挡
currentTransparencyMultiplier *= (1.0 - transparency)
```

### 3.5 深度和坡度效果

**深度效果** (`terrainDepth`):
```
depthBrightness = height / 63.0  // 范围限制在 [0.7, 1.15]
r *= depthBrightness
g *= depthBrightness
b *= depthBrightness
```

**坡度效果** (`terrainSlopes`):
- 模式1: 简单亮/暗 (x1.15 / x0.85)
- 模式2: 向量法线计算光照
- 模式3: 更精细的向量光照

最终亮度计算：
```
whiteLight = ambientLightWhite + directLightClamped
r *= (shadowR * ambientLightColored + whiteLight)
g *= (shadowG * ambientLightColored + whiteLight)
b *= (shadowB * ambientLightColored + whiteLight)
```

### 3.6 最终RGBA输出

```
result[0] = min(255, r * brightnessR * transparencyMultiplier + overlayRed)  // R
result[1] = min(255, g * brightnessG * transparencyMultiplier + overlayGreen)  // G
result[2] = min(255, b * brightnessB * transparencyMultiplier + overlayBlue)  // B
result[3] = getPixelLight(9, topLightValue) * 255  // A (光照alpha)
```

## 4. 像素数据写入流程

### 4.1 从世界到像素

两个路径：

**实时渲染路径** (`MapWriter.writeMap`):
```
玩家位置 → 确定加载范围 → writeChunk → writePixel(遍历y从高到低) → MapBlock.write()
```

**世界存档加载路径** (`WorldDataReader.buildRegion`):
```
读取区块NBT → buildTile → buildPixel (从section最高处向下扫描) → MapBlock.write()
```

### 4.2 扫描逻辑

从高处向低处 (y递减) 扫描每个(x,z)列：
1. 跳过透明方块（记录为overlay）
2. 遇到第一个有不透明颜色的方块 → 作为该像素的可见表面
3. 记录：方块状态、高度、光照、群系、overlays

### 4.3 Overlay 生成

满足以下条件的方块会生成overlay：
- `shouldOverlay() == true` (液体、透明方块如玻璃)
- 在可见表面之上
- 最多5层透明叠加 (`MAX_TRANSPORT_BLEND_DEPTH = 5`)

## 5. 文件存储格式

### 5.1 文件格式

Region文件存储为ZIP压缩包，内含 `region.xaero` 文件。

### 5.2 序列化格式 (v6.8)

```
Header:
  0xFF (标记字节)
  fullVersion (int) = (major << 16) | (minor) = 0x00060008

Per Chunk (存在时写入 chunkCoords = o<<4|p):
  Per Tile (16 tiles per chunk):
    tileExists (int, -1表示不存在)
    if exists:
      Per Pixel (256 pixels per tile):
        parametres (int) - 位标志组合
        if !isGrass && paletteNew:
          BlockState NBT (完整NBT标签)
        else if !isGrass:
          paletteIndex (int)
        if topHeightIsDifferent:
          topHeight (byte)
        if hasOverlays:
          overlayCount (byte)
          Per Overlay:
            overlayParametres (int)
            state NBT / paletteIndex
            biome string / index
        if hasBiome:
          biomeString (UTF) / paletteIndex
```

### 5.3 Parametres 位标志

`MapBlock.getParametres()` 计算：

```
bit 0  (0x1):       isGrass (0=grass, 1=其他方块)
bit 1  (0x2):       hasOverlays (是否有叠加层)
bit 8-11 (0xF00):   light (光照值, 4位)
bit 12-23 (0xFFF000): height & 0xFF (高度低8位)
bit 20 (0x100000):  hasBiome (是否有群系数据)
bit 24 (0x1000000): topHeightDifferent (topHeight与height不同)
bit 21 (0x200000):  paletteNew (新palette条目)
bit 22 (0x400000):  biomePaletteNew (新群系palette条目)
bit 25-28 (0xF000000): height >> 8 (高度高位)
```

### 5.4 Palette机制

每个Region使用两级palette压缩：
- **BlockState Palette**: `HashMap<BlockState, Integer>` - 方块状态到索引
- **Biome Palette**: `HashMap<ResourceKey<Biome>, Integer>` - 群系到索引

首次出现的方块/群系写入完整NBT/字符串并加入palette，后续引用索引。

## 6. 颜色缓冲区格式

`MapTileChunk.putColour()` 写入GPU颜色缓冲区：

```
position = (y * 64 + x) * 4  // 64x64 chunk, 4字节/pixel
buffer.putInt(position, blue << 24 | green << 16 | red << 8 | alpha)
```

注意字节序是 **BGRA** (OpenGL格式)。

## 7. 关键尺寸总结

| 单元 | 尺寸(像素) | 组成 |
|------|-----------|------|
| MapBlock | 1x1 | 单个像素 |
| MapTile | 16x16 | 256 MapBlocks |
| MapTileChunk | 64x64 | 16 MapTiles (4x4) |
| MapRegion | 128x128 | 64 MapTileChunks (8x8) |

对应Minecraft区块：
- 1 MapTile = 1 MC Chunk (16x16方块列)
- 1 MapTileChunk = 4x4 MC Chunks (64x64方块)
- 1 MapRegion = 32x32 MC Chunks (512x512方块)
