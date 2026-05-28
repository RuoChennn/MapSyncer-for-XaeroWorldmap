# 从 .mca 到 .xaero 文件转换方法（独立实现）

## 一、概述

本方案实现**完全不依赖 Minecraft 库**的 `.mca` → `.xaero` 转换工具。核心思路：自研轻量级 NBT 读写器 + 纯字符串匹配判断方块类型，只使用 Java 标准库。

**零依赖**: 不需要 `ServerLevel`、`RegistryAccess`、`BlockState`、`NbtUtils` 或任何 Minecraft 注册表。

---

## 二、架构总览

```
.mca → GZIP/ZLIB解压 → 自研NBT读取器 → 字符串匹配方块类型 → 直接复制原始NBT tag → .xaero
```

```
┌─────────────┐    ┌──────────────┐    ┌────────────┐
│ MCA解析器    │───►│ NBT数据提取器 │───►│ Xaero写入器 │
│ .mca二进制   │    │ 自研NBT读写器 │    │ .zip打包    │
└─────────────┘    └──────────────┘    └────────────┘
       │                  │                   │
       ▼                  ▼                   ▼
 RandomAccessFile     原始NBT字节流      标准Java Zip
 GZIP/ZLIB/LZ4解压    字符串方块名称      DataOutputStream

 ┌──────────────────────────────────────────────────┐
 │ 方块判断器 (字符串匹配)                              │
 │ "minecraft:water" → 透明, "minecraft:stone" → 不透明 │
 └──────────────────────────────────────────────────┘
```

---

## 三、实现步骤

### 步骤 1：实现独立 NBT 读写器

#### 1.1 NBT Tag 类型定义

| Tag ID | 类型 | 说明 |
|--------|------|------|
| 0 | TAG_End | Compound 结束标记 |
| 1 | TAG_Byte | 有符号 8-bit 整数 |
| 2 | TAG_Short | 有符号 16-bit 整数 |
| 3 | TAG_Int | 有符号 32-bit 整数 |
| 4 | TAG_Long | 有符号 64-bit 整数 |
| 5 | TAG_Float | 32-bit IEEE 754 浮点 |
| 6 | TAG_Double | 64-bit IEEE 754 浮点 |
| 7 | TAG_Byte_Array | 字节数组 |
| 8 | TAG_String | UTF-8 字符串 (2字节长度 + 数据) |
| 9 | TAG_List | 同类型 Tag 列表 |
| 10 | TAG_Compound | 键值对集合 |
| 11 | TAG_Int_Array | Int 数组 |
| 12 | TAG_Long_Array | Long 数组 |

所有多字节值均为 **大端序 (big-endian)**。

#### 1.2 NBT 读取器

```java
public class NbtReader {
    private final DataInputStream in;
    
    public Tag readTag() throws IOException {
        byte type = in.readByte();
        if (type == 0) return null;          // TAG_End
        String name = in.readUTF();           // 顶层 tag 有名称
        return readPayload(type, name);
    }
    
    private Tag readPayload(byte type, String name) throws IOException {
        switch (type) {
            case 10: return readCompound(name);
            case 9:  return readList(name);
            case 12: return readLongArray(name);
            case 11: return readIntArray(name);
            case 8:  return new TagString(name, in.readUTF());
            case 3:  return new TagInt(name, in.readInt());
            case 1:  return new TagByte(name, in.readByte());
            // ... 其他类型
        }
    }
    
    private TagCompound readCompound(String name) throws IOException {
        Map<String, Tag> children = new LinkedHashMap<>();
        while (true) {
            byte type = in.readByte();
            if (type == 0) break;             // TAG_End
            String childName = in.readUTF();
            children.put(childName, readPayload(type, childName));
        }
        return new TagCompound(name, children);
    }
    
    private TagList readList(String name) throws IOException {
        byte elementType = in.readByte();
        int length = in.readInt();
        List<Tag> items = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            items.add(readPayload(elementType, ""));  // 列表元素无名称
        }
        return new TagList(name, elementType, items);
    }
}
```

#### 1.3 NBT 写入器

```java
public class NbtWriter {
    private final DataOutputStream out;
    
    public void writeCompound(String name, Map<String, Tag> children) throws IOException {
        out.writeByte(10);    // TAG_Compound
        out.writeUTF(name);
        for (var entry : children.entrySet()) {
            writePayload(entry.getValue());
        }
        out.writeByte(0);     // TAG_End
    }
}
```

#### 1.4 关键：直接复制原始 NBT 二进制

从 palette 中读取的 CompoundTag 不经过任何转换，写入 `.xaero` 时直接调用 NbtWriter 输出原始字节流，替代 `NbtUtils.writeBlockState()`。

---

### 步骤 2：读取 .mca 文件

#### 2.1 .mca 文件二进制结构

```
偏移 0:     位置表 (4KB = 32×32×4 bytes)
            每 4 bytes 编码一个 chunk:
            bytes[0..2] = 扇区偏移 (大端序)
            bytes[3]    = 扇区数量

偏移 4KB:   时间戳表 (4KB = 32×32×4 bytes)
            每 4 bytes = Unix 时间戳

偏移 8KB:   实际 chunk 数据扇区 (每扇区 4KB)
```

#### 2.2 读取单个 chunk

```java
int chunkIndex = (chunkX & 31) * 32 + (chunkZ & 31);
raf.seek(chunkIndex * 4);
int b0 = raf.readUnsignedByte();
int b1 = raf.readUnsignedByte();
int b2 = raf.readUnsignedByte();
int offsetSector = (b0 << 16) | (b1 << 8) | b2;
int sectorCount = raf.readUnsignedByte();

if (offsetSector == 0 || sectorCount == 0) return null;

raf.seek(offsetSector * 4096L);
int dataLength = raf.readInt() - 1;        // 减去压缩类型字节
int compressionType = raf.readUnsignedByte();
byte[] compressedData = raf.readNBytes(dataLength);

switch (compressionType) {
    case 1: decompress = new GZIPInputStream(bais);
    case 2: decompress = new InflaterInputStream(bais);   // ZLIB/Deflate
    case 3: return compressedData;                         // 无压缩
    case 4: decompress = new Lz4InputStream(bais);         // LZ4 (较新版本)
}
```

> **注意**: 压缩类型映射 1=GZIP, 2=ZLIB, 3=NONE, 4=LZ4

---

### 步骤 3：从 NBT 提取数据（字符串方式）

#### 3.1 NBT 结构（1.18+）

```
CompoundTag (根)
├── String "Status": "minecraft:full"
├── Int "yPos": -4
├── Compound "Heightmaps"
│   └── LongArray "WORLD_SURFACE": [4096个long]
├── List "sections" (元素类型=Compound)
│   └── [0] Compound
│       ├── Byte "Y": 0              ← 1.18+: 绝对世界坐标 (sectionY * 16)
│       ├── Compound "block_states"
│       │   ├── List "palette"       ← [CompoundTag, ...]
│       │   │   ├── [0] {Name:"minecraft:stone", Properties:{}}
│       │   │   └── [1] {Name:"minecraft:grass_block", Properties:{snowy:"false"}}
│       │   └── LongArray "data"     ← 位压缩的 palette 索引
│       └── Compound "biomes"
│           ├── List "palette"       ← [String, ...]
│           │   ├── "minecraft:plains"
│           │   └── "minecraft:forest"
│           └── LongArray "data"
```

#### 3.2 高度图解码（Wiki 规范）

**数据来源**: Chunk NBT 中的 `Heightmaps` 字段，包含多个高度图类型。

**高度图类型选择**（按优先级）：
1. `MOTION_BLOCKING_NO_LEAVES` - 包含水方块的高度图，用于检测上方水层
2. `WORLD_SURFACE` - 不包含水方块的高度图

**Wiki 编码公式**：

```java
// 编码序号公式：i = x + 16*z（注意不是 z*16+x）
// 高度值公式：value = (data[i/u] >> ((i%u)*b)) & ((1L<<b)-1L) + chunkBottomY

int b = ceil(log2(h));  // h = 维度高度范围 (worldTopY - minBuildHeight)
int u = 64 / b;         // 一个long能存储的元素数量
int i = x + 16 * z;     // XZ坐标编码序号

// 从LongArray读取高度偏移量
long offset = (data[i / u] >> ((i % u) * b)) & ((1L << b) - 1L);
int worldY = chunkBottomY + (int) offset;  // 世界绝对Y坐标
```

**实现代码**：

```java
// 解析高度图 → int[16][16] (世界绝对Y坐标)
int[][] parseHeightmap(CompoundTag rootTag, int chunkBottomY, int worldHeightRange) {
    int[][] hm = new int[16][16];
    CompoundTag heightmaps = rootTag.getCompound("Heightmaps");
    
    // 优先使用 MOTION_BLOCKING_NO_LEAVES（包含水方块）
    // 这样能正确检测上方的水方块
    if (heightmaps.contains("MOTION_BLOCKING_NO_LEAVES", 12)) {
        long[] data = heightmaps.getLongArray("MOTION_BLOCKING_NO_LEAVES");
        int bitsPerHeight = calculateBitsPerHeight(data.length, worldHeightRange);
        if (bitsPerHeight > 0 && bitsPerHeight <= 10) {
            decodeHeightmapWiki(data, bitsPerHeight, chunkBottomY, hm);
            return hm;
        }
    }
    
    // 备用: WORLD_SURFACE（不包含水方块）
    if (heightmaps.contains("WORLD_SURFACE", 12)) {
        long[] data = heightmaps.getLongArray("WORLD_SURFACE");
        int bitsPerHeight = calculateBitsPerHeight(data.length, worldHeightRange);
        if (bitsPerHeight > 0 && bitsPerHeight <= 10) {
            decodeHeightmapWiki(data, bitsPerHeight, chunkBottomY, hm);
            return hm;
        }
    }
    
    // 旧格式兼容: int[] HeightMap (直接存储世界绝对高度)
    if (rootTag.contains("HeightMap", 11)) {
        int[] oldData = rootTag.getIntArray("HeightMap");
        if (oldData.length == 256) {
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    hm[x][z] = oldData[z * 16 + x];  // 旧格式索引：z*16+x
            return hm;
        }
    }
    return hm;
}

// bitsPerHeight 计算（Wiki 规范）
int calculateBitsPerHeight(int longArrayLength, int worldHeightRange) {
    // 优先使用维度高度范围计算
    if (worldHeightRange > 0) {
        return 32 - Integer.numberOfLeadingZeros(worldHeightRange - 1);  // ceil(log2(h))
    }
    // 备用：从数组长度反推
    if (longArrayLength <= 0) return 0;
    int u = (256 + longArrayLength - 1) / longArrayLength;  // ceil(256/l)
    return 64 / u;
}

// Wiki 规范的高度图解码
void decodeHeightmapWiki(long[] data, int bitsPerHeight, int chunkBottomY, int[][] hm) {
    int u = 64 / bitsPerHeight;  // 每个long存储的元素数量
    
    for (int z = 0; z < 16; z++) {
        for (int x = 0; x < 16; x++) {
            // Wiki: 编码序号 i = x + 16*z
            int i = x + 16 * z;
            
            // Wiki公式：(data[i/u] >> ((i%u)*b)) & ((1L<<b)-1L)
            int longIndex = i / u;
            int bitOffset = (i % u) * bitsPerHeight;
            
            if (longIndex < data.length) {
                long offset = (data[longIndex] >>> bitOffset) & ((1L << bitsPerHeight) - 1L);
                hm[x][z] = chunkBottomY + (int) offset;
            }
        }
    }
}
```

#### 3.3 Section 方块数据解码

**Bits Per Entry 规则 (1.18+ 固定模式)**：

| Palette 大小 | Bits Per Entry | 说明 |
|-------------|---------------|------|
| 1 | 0 | RO_INDEX: 全部同一种方块 |
| 2-16 | 4 | INDIRECT: 间接调色板 |
| 17-256 | 8 | INDIRECT: 间接调色板 |
| >256 | 15 | DIRECT: 直接编码 (不使用调色板) |

```java
int calcBitsPerEntry(int paletteSize) {
    if (paletteSize <= 1) return 0;
    if (paletteSize <= 16) return 4;
    if (paletteSize <= 256) return 8;
    return 15;   // Direct palette
}

// 解析 palette → List<String> (方块名称)
List<String> parseBlockPalette(List<TagCompound> paletteList) {
    List<String> names = new ArrayList<>();
    for (TagCompound tag : paletteList) {
        String name = tag.getString("Name");  // "minecraft:stone"
        names.add(name);
    }
    return names;
}

// 从 data 数组读取 palette 索引
int readBits(long[] data, int index, int bitsPerEntry) {
    int bitOffset = index * bitsPerEntry;
    int cellIndex = bitOffset >> 6;
    int bitInCell = bitOffset & 0x3F;
    int bitsRemaining = 64 - bitInCell;
    long mask = (1L << bitsPerEntry) - 1;
    
    if (bitsRemaining >= bitsPerEntry) {
        return (int) ((data[cellIndex] >> bitInCell) & mask);
    } else {
        int needed = bitsPerEntry - bitsRemaining;
        return (int) ((data[cellIndex] >> bitInCell) |
                (data[cellIndex + 1] & ((1L << needed) - 1)) << bitsRemaining);
    }
}

// 解码所有方块
for (TagCompound section : sections) {
    int sectionY = section.getByte("Y");       // 1.18+: 绝对世界坐标
    int sectionBaseY = sectionY * 16;
    
    TagCompound blockStates = section.getCompound("block_states");
    List<String> palette = parseBlockPalette(blockStates.getList("palette"));
    long[] data = blockStates.getLongArray("data");
    int bits = calcBitsPerEntry(palette.size());
    
    for (int y = 0; y < 16; y++)
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++) {
                int blockIndex = (y << 8) | (z << 4) | x;  // YZX 顺序
                int paletteIdx = readBits(data, blockIndex, bits);
                String name = palette.get(paletteIdx);
                blockNames[x][z][sectionBaseY + y] = name;
            }
}
```

#### 3.4 生物群系解码

生物群系使用 **4×4×4 voxel** 存储，每个 section 有 64 个 voxel：

```java
int voxelIndex = (localY >> 2) << 4 | (lz >> 2) << 2 | (lx >> 2);
int paletteIdx = readBits(biomeData, voxelIndex, biomeBits);
String biomeName = biomePalette.get(paletteIdx);
```

#### 3.5 区块状态检查（Wiki 规范）

区块生成按顺序有多个状态，只有 `surface` 及之后的状态才有实际地形数据：

```java
// 区块状态级别（按生成顺序）
List<String> VALID_STATUSES = List.of(
    "minecraft:empty",             // 0 - 空（跳过）
    "minecraft:structure_starts",  // 1 - 结构开始（跳过）
    "minecraft:structure_references", // 2 - 结构引用（跳过）
    "minecraft:biomes",            // 3 - 生物群系（跳过）
    "minecraft:noise",             // 4 - 噪声（无地形，跳过）
    "minecraft:surface",           // 5 - 表面（开始有地形）✓
    "minecraft:carvers",           // 6 - 雕刻器 ✓
    "minecraft:features",          // 7 - 特性 ✓
    "minecraft:light",             // 8 - 光照 ✓
    "minecraft:spawn",             // 9 - 生成点 ✓
    "minecraft:heightmaps",        // 10 - 高度图 ✓
    "minecraft:full"               // 11 - 完成 ✓
);

// 最小有效状态索引
int MIN_VALID_STATUS_INDEX = 5;  // surface

boolean shouldSkipChunk(String status) {
    if (status == null || status.isEmpty()) return true;
    
    // 处理带命名空间和不带命名空间的状态
    String normalizedStatus = status.contains(":") ? status : "minecraft:" + status;
    
    int index = VALID_STATUSES.indexOf(normalizedStatus);
    if (index < 0) {
        // 未知状态，保守处理：检查是否包含早期状态关键词
        return status.contains("empty") ||
               status.contains("structure_starts") ||
               status.contains("structure_references") ||
               status.contains("biomes") ||
               status.contains("noise");
    }
    
    // 只有 surface 及之后的状态才有效
    return index < MIN_VALID_STATUS_INDEX;
}
```

**关键点**：`noise` 状态的区块虽然已有基础形状，但还没有实际的地形表面，因此应该跳过。

#### 3.6 亮度数据读取（Wiki 规范）

亮度存储格式使用字节数组。因为亮度是 0-15（只需4比特），而1字节是8比特，所以每个字节存储两个亮度信息。

**存储规则**：
- 整个子区块 4096 个亮度信息共 **2048 字节**
- 写入顺序：**YZX 编码**
- 低4位存储偶数索引，高4位存储奇数索引

**Wiki 公式**：

```java
// YZX编码序号
int yzx = (y << 8) | (z << 4) | x;

// Wiki公式：(data[yzx >> 1] >> (4 * (yzx & 1))) & 0xF
// yzx >> 1 找到字节索引（每字节存2个亮度）
// yzx & 1 判断是该字节的第几个亮度（0=低4位，1=高4位）
// 4 * (yzx & 1) 计算位偏移（0或4）
int light = (data[yzx >> 1] >> (4 * (yzx & 1))) & 0xF;
```

**实现代码**：

```java
byte getLightValue(byte[] lightArray, int x, int y, int z) {
    if (lightArray == null || lightArray.length != 2048) {
        return 0;
    }
    
    // YZX编码序号
    int yzx = (y << 8) | (z << 4) | x;
    
    // Wiki公式：(data[yzx >> 1] >> (4 * (yzx & 1))) & 0xF
    return (byte) ((lightArray[yzx >> 1] >> (4 * (yzx & 1))) & 0xF);
}
```

---

### 步骤 4：方块判断（字符串匹配）

```java
Set<String> TRANSPARENT = Set.of(
    "minecraft:water", "minecraft:lava",
    "minecraft:glass", "minecraft:white_stained_glass",
    "minecraft:oak_leaves", "minecraft:spruce_leaves",
    "minecraft:birch_leaves", "minecraft:jungle_leaves",
    "minecraft:acacia_leaves", "minecraft:dark_oak_leaves",
    "minecraft:mangrove_leaves", "minecraft:cherry_leaves",
    "minecraft:azalea_leaves", "minecraft:flowering_azalea_leaves"
);

Set<String> AIR_BLOCKS = Set.of(
    "minecraft:air", "minecraft:cave_air", "minecraft:void_air"
);

Set<String> GRASS_LIKE = Set.of(
    "minecraft:grass_block", "minecraft:grass", "minecraft:fern"
);

boolean isTransparent(String name) { return TRANSPARENT.contains(name); }
boolean isAir(String name) { return AIR_BLOCKS.contains(name); }
boolean isGrass(String name) { return GRASS_LIKE.contains(name); }
```

---

### 步骤 5：构建像素数据

#### 5.1 可见表面扫描（基于 Xaero 源码分析）

**Xaero 源码出处**: `WorldDataReader.java` 第 419-554 行 `buildTile()` 方法

**核心机制**: Xaero 采用 **从最高 section 向下遍历** 的方式，而不是对每个 (x,z) 列独立扫描。它维护 256 个位置的状态数组：

```java
// Xaero 维护的 per-pixel 状态数组 (256 = 16x16)
boolean[] blockFound = new boolean[256];    // 该位置是否已找到可见方块
boolean[] underair = new boolean[256];      // 该位置当前是否在空气中
boolean[] shouldEnterGround = new boolean[256]; // 洞穴模式: 是否需要进入地面
byte[] lightLevels = new byte[256];         // 方块光照等级
byte[] skyLightLevels = new byte[256];      // 天空光照等级
int[] topH = new int[256];                  // 最高透明方块Y (用于 topHeight)
OverlayBuilder[] overlayBuilders = new OverlayBuilder[256]; // 透明层构建器
```

**扫描流程** (逐 section 从上到下):

```
1. 获取 chunk NBT 中的所有 sections
2. 按 Y 从高到低遍历 sections (sectionsList.size()-1 → 0)
3. 对每个 section:
   a. 解析 block_states palette + data
   b. 解析 BlockLight / SkyLight (如果有)
   c. 遍历 16x16 位置
   d. 从 heightMapValue+3 或 sectionBasedHeight 开始向下扫描
   e. 遇到透明方块 → OverlayBuilder 记录
   f. 遇到有颜色的非透明方块 → 写入 MapBlock, 标记 blockFound=true
4. 当所有 256 个位置都找到方块 (fillCounter == 0) 时提前终止
```

**关键逻辑 — 扫描起点计算**:

```java
// Xaero 源码第 424-425 行:
int heightMapValue = heightMapExists ?
    (oldHeightMap ? oldHeightMapArray[pos_2d] :
     chunkBottomY + this.heightMapBitArray.get(pos_2d))
    : Integer.MIN_VALUE;

int startHeight = cave && !fullCave ? caveStart :
    (ignoreHeightmaps || heightMapValue < chunkBottomY ?
     sectionBasedHeight : heightMapValue + 3);
```

**关键逻辑 — Overlay 构建**:

Xaero 使用 `OverlayBuilder` 机制合并连续透明方块（详见 `OverlayBuilder.java`）：

```java
// OverlayBuilder.build() — 处理每个透明方块
// 如果方块类型改变，创建新 overlay；相同类型则累加 opacity
public void build(BlockState state, int opacity, byte light, ...) {
    Overlay currentOverlay = getCurrentOverlay();
    if (currentOverlay == null || currentOverlay.getState() != state) {
        // 方块类型改变，创建新 overlay
        ++this.currentOverlayIndex;
        nextOverlay.write(state, light, glowing);
        currentOverlay = nextOverlay;
    }
    currentOverlay.increaseOpacity(opacity);  // 累加不透明度
}
```

**最多 10 层 overlay** (`MAX_OVERLAYS = 10`)。

**扫描伪代码**（对应 Xaero 逻辑）:

```java
// 对每个 chunk (16x16)
int[][] heightmap = parseHeightmap(chunkNbt, chunkBottomY);
boolean[] blockFound = new boolean[256];
int[] topH = new int[256];  // 记录最高透明方块Y
Arrays.fill(topH, worldBottomY);

// 从最高 section 向下遍历
for (int i = sections.size() - 1; i >= 0; i--) {
    TagCompound section = sections.get(i);
    int sectionY = section.getByte("Y");       // 1.18+: 绝对世界坐标
    int sectionBaseY = sectionY * 16;
    
    List<String> palette = parseBlockPalette(section.getCompound("block_states"));
    long[] blockData = section.getLongArray("data");
    int bits = calcBitsPerEntry(palette.size());
    
    // 方块光照数据
    byte[] blockLight = parseLightArray(section, "BlockLight");
    byte[] skyLight = parseLightArray(section, "SkyLight");
    
    for (int z = 0; z < 16; z++) {
        for (int x = 0; x < 16; x++) {
            int pos = z * 16 + x;
            if (blockFound[pos]) continue;  // 已找到方块，跳过
            
            // 计算扫描起点
            int heightMapValue = heightmap[x][z];
            int startHeight = heightMapValue + 3;  // +3 容差
            if (startHeight >= worldTopY) startHeight = worldTopY - 1;
            
            // 确定在 section 内的起始局部Y
            int localStartY = 15;
            if (startHeight >> 4 << 4 == sectionBaseY) {
                localStartY = startHeight & 0xF;
            }
            
            // Overlay 构建器 (最多 10 层)
            OverlayBuilder overlayBuilder = new OverlayBuilder();
            overlayBuilder.startBuilding();
            
            // 从 startHeight 向下扫描到 section 底部
            for (int ly = localStartY; ly >= 0; ly--) {
                int worldY = sectionBaseY + ly;
                int blockIdx = readBits(blockData, (ly << 8) | (z << 4) | x, bits);
                String name = palette.get(blockIdx);
                
                if (isAir(name)) {
                    underair[pos] = true;
                    continue;
                }
                
                if (isTransparent(name)) {
                    int opacity = getOpacity(name);  // 水=5, 岩浆=12, 玻璃=3, 其他=8
                    overlayBuilder.build(name, opacity, 15);
                    if (worldY > topH[pos]) topH[pos] = worldY;
                    continue;
                }
                
                // 找到有颜色的不透明方块
                if (hasVanillaColor(name)) {
                    // 完成 overlay 构建
                    List<OverlayInfo> overlays = overlayBuilder.finishBuilding();
                    
                    // 写入像素
                    pixelData[x][z] = new Pixel(
                        name,
                        (short) worldY,           // height
                        (short) topH[pos],        // topHeight
                        overlays,
                        getBiomeAt(x, worldY, z),
                        (byte) 15                 // light (服务端硬编码日光)
                    );
                    blockFound[pos] = true;
                    break;
                }
            }
        }
    }
}

// 未找到方块的像素用 air 填充
for (int x = 0; x < 16; x++)
    for (int z = 0; z < 16; z++)
        if (pixelData[x][z] == null)
            pixelData[x][z] = new Pixel("minecraft:grass_block", (short)(worldBottomY-1), ...);
```

#### 5.2 Pixel 数据结构（替代 MapBlock，无 Minecraft 依赖）

```java
record Pixel(
    String blockName,              // "minecraft:stone"
    short height,
    short topHeight,
    List<OverlayInfo> overlays,
    String biome,                  // "minecraft:plains"
    byte light                     // 固定 15 (日光)
) {}

record OverlayInfo(String blockName, int opacity, byte light) {}
```

#### 5.3 Parameters 位域编码（基于 Xaero 源码分析）

**Xaero 源码出处**: `MapBlock.java` 第 53-62 行 `getParametres()` 方法

```java
// 源码: MapBlock.getParametres()
public int getParametres() {
    int parametres = 0;
    parametres |= !this.isGrass() ? 1 : 0;                      // bit 0: isGrass
    parametres |= this.getNumberOfOverlays() != 0 ? 2 : 0;      // bit 1: hasOverlays
    parametres |= this.light << 8;                               // bits 8-11: light (0-15)
    parametres |= (this.getHeight() & 0xFF) << 12;               // bits 12-19: height 低8位
    parametres |= this.biome != null ? 0x100000 : 0;             // bit 20: hasBiome
    parametres |= this.height != this.topHeight ? 0x1000000 : 0; // bit 24: topHeightDifferent
    return parametres |= (this.getHeight() >> 8 & 0xF) << 25;   // bits 25-28: height 高4位
}
```

**完整位域布局**:

| 位域 | 位置 | 含义 | Xaero 代码 |
|------|------|------|-----------|
| bit 0 | 1 | isGrass (0=草方块, 1=非草) | `!this.isGrass() ? 1 : 0` |
| bit 1 | 2 | hasOverlays | `getNumberOfOverlays() != 0 ? 2 : 0` |
| bits 8-11 | 0xF00 | light (0-15) | `this.light << 8` |
| bits 12-19 | 0xFF000 | height 低8位 | `(this.getHeight() & 0xFF) << 12` |
| bit 20 | 0x100000 | hasBiome | `this.biome != null ? 0x100000 : 0` |
| bit 21 | 0x200000 | blockStateNewInPalette | 写入时动态添加 (MapSaveLoad.savePixel) |
| bit 22 | 0x400000 | biomeNewInPalette | 写入时动态添加 (MapSaveLoad.savePixel) |
| bit 24 | 0x1000000 | topHeightDifferent | `this.height != this.topHeight ? 0x1000000 : 0` |
| bits 25-28 | 0x1F000000 | height 高4位 | `(this.getHeight() >> 8 & 0xF) << 25` |

**服务端实现**:

```java
int params = 0;
if (!isGrass(pixel.blockName)) params |= 1;            // bit 0: isGrass
if (!pixel.overlays.isEmpty()) params |= 2;             // bit 1: hasOverlays
params |= (pixel.light & 0xF) << 8;                     // bits 8-11: light
params |= (pixel.height & 0xFF) << 12;                  // bits 12-19: height 低8位
params |= ((pixel.height >> 8) & 0xF) << 25;            // bits 25-28: height 高4位
if (pixel.biome != null) params |= (1 << 20);           // bit 20: hasBiome
if (pixel.topHeight != pixel.height) params |= (1 << 24); // bit 24: topHeight不同
```

---

### 步骤 6：序列化为 .xaero 并打包 .zip

#### 6.1 文件结构

```
{regionX}_{regionZ}.zip
└── region.xaero (ZipEntry)
    ├── byte: 0xFF (版本标记)
    ├── int: fullVersion = 0x00060008 (major=6, minor=8)
    └── 64 个 TileChunk 数据 (8×8)
        ├── byte: chunkCoords (o<<4 | p)
        └── 16 个 Tile 数据 (4×4)
            ├── 256 个 Pixel 数据 (16×16)
            └── Tile footer (3 bytes)
```

#### 6.2 序列化代码

```java
public void writeRegionFile(File outputFile, List<Pixel[]> tilePixels) throws IOException {
    File tempFile = new File(outputFile.getAbsolutePath() + ".temp");
    
    try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tempFile))) {
        zos.putNextEntry(new ZipEntry("region.xaero"));
        DataOutputStream dos = new DataOutputStream(zos);
        
        dos.writeByte(0xFF);
        dos.writeInt(0x00060008);
        
        Map<String, Integer> blockPalette = new LinkedHashMap<>();
        Map<String, Integer> biomePalette = new LinkedHashMap<>();
        Map<String, byte[]> blockNbtCache = new HashMap<>();
        
        for (int o = 0; o < 8; o++) {
            for (int p = 0; p < 8; p++) {
                dos.writeByte((o << 4) | p);
                
                for (int i = 0; i < 4; i++) {
                    for (int j = 0; j < 4; j++) {
                        Pixel[] tile = getTile(tilePixels, o, p, i, j);
                        if (tile == null) {
                            dos.writeInt(-1);
                            continue;
                        }
                        
                        for (int x = 0; x < 16; x++)
                            for (int z = 0; z < 16; z++)
                                writePixel(dos, tile[x * 16 + z],
                                          blockPalette, biomePalette, blockNbtCache);
                        
                        dos.writeByte(1);             // worldInterpretationVersion
                        dos.writeInt(Integer.MAX_VALUE);
                        dos.writeByte(0);
                    }
                }
            }
        }
    }
    
    Files.move(tempFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
}
```

#### 6.3 单像素写入（纯字符串方式）

```java
private void writePixel(DataOutputStream dos, Pixel pixel,
                        Map<String, Integer> blockPalette,
                        Map<String, Integer> biomePalette,
                        Map<String, byte[]> blockNbtCache) throws IOException {
    
    int params = encodeParameters(pixel);
    boolean blockIsNew = !isGrass(pixel.blockName) && !blockPalette.containsKey(pixel.blockName);
    boolean biomeIsNew = pixel.biome != null && !biomePalette.containsKey(pixel.biome);
    
    if (blockIsNew) params |= 0x200000;   // bit 21: new blockState
    if (biomeIsNew) params |= 0x400000;   // bit 22: new biome
    
    dos.writeInt(params);
    
    if (!isGrass(pixel.blockName)) {
        if (blockIsNew) {
            byte[] rawNbt = buildBlockNbt(pixel.blockName, blockNbtCache);
            dos.write(rawNbt);
            blockPalette.put(pixel.blockName, blockPalette.size());
        } else {
            dos.writeInt(blockPalette.get(pixel.blockName));
        }
    }
    
    if (pixel.topHeight != pixel.height) {
        dos.writeByte(pixel.topHeight & 0xFF);
    }
    
    if (!pixel.overlays.isEmpty()) {
        dos.writeByte(pixel.overlays.size());
        for (OverlayInfo ov : pixel.overlays) {
            writeOverlay(dos, ov, blockPalette);
        }
    }
    
    if (pixel.biome != null) {
        if (biomeIsNew) {
            dos.writeUTF(pixel.biome);
            biomePalette.put(pixel.biome, biomePalette.size());
        } else {
            dos.writeInt(biomePalette.get(pixel.biome));
        }
    }
}
```

#### 6.4 方块 NBT 构造

首次出现的方块需要写入完整的 NBT CompoundTag：

```java
private byte[] buildBlockNbt(String blockName, Map<String, byte[]> cache) {
    return cache.computeIfAbsent(blockName, name -> {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        NbtWriter writer = new NbtWriter(new DataOutputStream(baos));
        
        // "minecraft:grass_block{snowy:false}" → {Name:"minecraft:grass_block", Properties:{snowy:"false"}}
        String pureName = stripProperties(name);
        Map<String, String> properties = parseBlockProperties(name);
        
        Map<String, Tag> fields = new LinkedHashMap<>();
        fields.put("Name", new TagString("Name", pureName));
        if (!properties.isEmpty()) {
            fields.put("Properties", new TagCompound("Properties", 
                properties.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, 
                        e -> new TagString(e.getKey(), e.getValue())))));
        }
        writer.writeCompound("", fields);
        return baos.toByteArray();
    });
}
```

#### 6.5 Overlay 写入（基于 Xaero 源码分析）

**Xaero 源码出处**: `Overlay.java`, `OverlayBuilder.java`, `MapSaveLoad.java` `saveOverlay()` 方法

**Overlay 数据结构** (`Overlay.java`):
```java
public class Overlay {
    private BlockState state;    // 方块状态
    private byte light;          // 光照等级 (0-15)
    private boolean glowing;     // 是否发光
    private int opacity;         // 不透明度 (累加值, 上限 15)
}
```

**Overlay 序列化** (Xaero `MapSaveLoad.saveOverlay()`):
```java
private void saveOverlay(Overlay o, DataOutputStream out) throws IOException {
    boolean isWater = o.getState().getBlock() == Blocks.WATER;
    int parametres = 0;
    parametres |= !isWater ? 1 : 0;                    // bit 0: isWater
    parametres |= o.getLight() << 4;                   // bits 4-7: light
    parametres |= o.getOpacity() << 11;                // bits 11-14: opacity
    if (!isWater && !this.regionSavePalette.containsKey(o.getState())) {
        parametres |= 0x400;                           // bit 10: new BlockState in palette
    }
    out.writeInt(parametres);
    
    if (!isWater) {
        if (parametres has bit 10) {
            NbtIo.write(NbtUtils.writeBlockState(o.getState()), out);
            this.regionSavePalette.put(o.getState(), size);
        } else {
            out.writeInt(this.regionSavePalette.get(o.getState()));
        }
    }
}
```

**OverlayBuilder 合并规则** (Xaero `OverlayBuilder.java` 第 47-78 行):

```java
public void build(BlockState state, int opacity, byte light, ...) {
    Overlay currentOverlay = getCurrentOverlay();
    Overlay nextOverlay = null;
    if (currentOverlayIndex < overlayBuildingSet.length - 1) {
        nextOverlay = overlayBuildingSet[currentOverlayIndex + 1];
    }
    
    TextureAtlasSprite icon = getParticleIcon(state);
    boolean changed = (currentOverlay == null || currentOverlay.getState() != state);
    
    // 如果方块类型改变 或 当前无 overlay → 创建新 overlay
    if (nextOverlay != null && (currentOverlay == null || changed)) {
        boolean glowing = isGlowing(state);
        nextOverlay.write(state, light, glowing);
        currentOverlay = nextOverlay;
        ++currentOverlayIndex;
    }
    // 累加 opacity (相同类型方块连续出现时)
    currentOverlay.increaseOpacity(opacity);
}
```

**关键**: 相同 BlockState 的连续透明方块**合并为一个 overlay**，opacity 累加。不同类型各自独立。最多 10 层。

```java
private void writeOverlay(DataOutputStream dos, OverlayInfo ov,
                          Map<String, Integer> blockPalette) throws IOException {
    boolean isWater = "minecraft:water".equals(ov.blockName);
    boolean stateNew = !isWater && !blockPalette.containsKey(ov.blockName);
    
    int overlayParams = 0;
    if (!isWater) overlayParams |= 1;                    // bit 0: isWater
    overlayParams |= (ov.light & 0xF) << 4;              // bits 4-7: light
    overlayParams |= (ov.opacity & 0xF) << 11;           // bits 11-14: opacity
    if (stateNew) overlayParams |= 0x400;                // bit 10: new state
    
    dos.writeInt(overlayParams);
    
    if (!isWater) {
        if (stateNew) {
            byte[] rawNbt = buildBlockNbt(ov.blockName);
            dos.write(rawNbt);
            blockPalette.put(ov.blockName, blockPalette.size());
        } else {
            dos.writeInt(blockPalette.get(ov.blockName));
        }
    }
}
```

---

## 四、层次结构与尺寸对照

```
MapRegion (512×512 方块)
├── 8×8 MapTileChunk (每个 64×64 方块)
│   └── 4×4 MapTile (每个 16×16 方块 = 1 个 Minecraft chunk)
│       └── 16×16 Pixel (每个 1×1 方块 = 1 个像素)
```

| 层级 | 方块尺寸 | 数量 | 说明 |
|------|----------|------|------|
| MapRegion | 512×512 | 1 | 对应一个 .mca region |
| MapTileChunk | 64×64 | 64 (8×8) | 序列化坐标 `o<<4\|p` |
| MapTile | 16×16 | 16 per chunk | 对应一个 MC chunk |
| Pixel | 1×1 | 256 per tile | 单个像素 |

一个完整 region 包含 **262,144** 个像素。

---

## 五、数据结构定义（无 Minecraft 依赖）

### 5.1 NBT 标签

```java
sealed interface Tag permits TagByte, TagInt, TagString, TagCompound, TagList, TagLongArray, TagIntArray {
    byte getType();
    String getName();
}
record TagByte(String name, byte value) implements Tag { public byte getType() { return 1; } }
record TagInt(String name, int value) implements Tag { public byte getType() { return 3; } }
record TagString(String name, String value) implements Tag { public byte getType() { return 8; } }
record TagCompound(String name, Map<String, Tag> children) implements Tag { public byte getType() { return 10; } }
record TagList(String name, byte elementType, List<Tag> items) implements Tag { public byte getType() { return 9; } }
record TagLongArray(String name, long[] values) implements Tag { public byte getType() { return 12; } }
record TagIntArray(String name, int[] values) implements Tag { public byte getType() { return 11; } }
```

### 5.2 像素数据

```java
record Pixel(
    String blockName, short height, short topHeight,
    List<OverlayInfo> overlays, String biome, byte light
) {}
record OverlayInfo(String blockName, int opacity, byte light) {}
```

---

## 六、核心常量

```java
// 版本
int MAJOR_VERSION = 6, MINOR_VERSION = 8;
int FULL_VERSION = (6 << 16) | 8;  // 0x00060008

// NBT Tag 类型
byte TAG_End = 0, TAG_Byte = 1, TAG_Short = 2, TAG_Int = 3;
byte TAG_Long = 4, TAG_Float = 5, TAG_Double = 6, TAG_Byte_Array = 7;
byte TAG_String = 8, TAG_List = 9, TAG_Compound = 10;
byte TAG_Int_Array = 11, TAG_Long_Array = 12;

// 尺寸
int CHUNKS_PER_REGION = 32;       // 32x32 chunks per region
int TILE_CHUNKS_PER_REGION = 8;   // 8x8 tile chunks
int TILES_PER_TILE_CHUNK = 4;     // 4x4 tiles per tile chunk
int TILE_SIZE = 16;               // 16x16 blocks per tile

// Paletted Container (1.18+ 固定模式)
int BITS_PER_ENTRY_RO_INDEX = 0;   // palette size = 1
int BITS_PER_ENTRY_INDIRECT_4 = 4; // palette size 2-16
int BITS_PER_ENTRY_INDIRECT_8 = 8; // palette size 17-256
int BITS_PER_ENTRY_DIRECT = 15;    // palette size > 256

// 压缩类型
int COMPRESS_GZIP = 1;
int COMPRESS_ZLIB = 2;
int COMPRESS_NONE = 3;
int COMPRESS_LZ4 = 4;  // 较新版本支持
```

---

## 七、文件路径规范

### 输入

```
{worldDir}/region/r.{regionX}.{regionZ}.mca          # 主世界
{worldDir}/DIM-1/region/r.{x}.{z}.mca                # 下界
{worldDir}/DIM1/region/r.{x}.{z}.mca                 # 末地
```

### 输出

```
server_map_cache/{dimension}/{regionX}_{regionZ}.zip
```

### 临时文件

```
server_map_cache/{dimension}/{regionX}_{regionZ}.zip.temp
```

---

## 八、关键实现决策

| 决策 | 方案 | 原因 |
|------|------|------|
| NBT 解析 | 自研 NbtReader/NbtWriter | 不依赖 Minecraft 库 |
| 方块判断 | 字符串匹配 | 替代 state.is(Blocks.X) |
| 方块数据 | 直接复制原始 NBT 字节 | 替代 NbtUtils.writeBlockState() |
| 调色板 | Map<String, Integer> | 替代 Map<BlockState, Integer> |
| 文件读取 | RandomAccessFile("r") | 只读，不触发服务器锁 |
| 文件写入 | 先 .temp 后原子替换 | 避免中间状态损坏文件 |
| 线程模型 | 单线程顺序处理 | 避免与服务器主线程冲突 |
| 高度图 | 优先 WORLD_SURFACE | MOTION_BLOCKING_NO_LEAVES 可能遗漏顶层透明方块 |

---

## 九、验证说明

根据 wiki.vg 和 Minecraft Wiki 的文档验证：

| 项目 | 文档确认 | 方案匹配 |
|------|---------|---------|
| .mca 文件结构 | 4KB 位置表 + 4KB 时间戳 + chunk 数据 | 完全匹配 |
| 压缩类型 | 1=GZIP, 2=ZLIB, 3=NONE, 4=LZ4 | 方案包含 LZ4 支持 |
| NBT Tag 类型 | 13 种 tag 类型 (0-12) | 完全匹配 |
| 大端序 | 所有多字节值大端序 | DataInputStream 默认大端序 |
| Section Y | 1.18+ 为绝对世界坐标 | 使用 sectionY*16 |
| Bits per entry | ≤16→4, ≤256→8, >256→15 | 使用固定模式 |
| 高度图 | 动态位宽 (通常 9 bits)，chunkBottomY+偏移量 | 正确实现跨 long 边界读取 + 动态位宽 |
| 生物群系 | 4×4×4 voxel | voxelIndex 公式正确 |

---

## 十、已修复 Bug 总结

| # | Bug | 根因 | 修复 |
|---|-----|------|------|
| 1 | 所有地图空数据 | 压缩类型映射反了 | case 1→GZIP, case 2→ZLIB, case 3→raw |
| 2 | sections 读取为空 | 1.18+ 结构变化 | 检查根标签是否含 sections |
| 3 | 方块错位 | Section Y 用相对坐标 | 改为 sectionY*16 绝对坐标 |
| 4 | 高度值错误 | index/64 而非 index*9 | 改为 index*bitsPerEntry |
| 5 | 方块类型全错 | ceil(log2(n)) 位计算 | 改为 ≤16→4, ≤256→8, =1→0 |
| 6 | 生物群系错位 | 索引公式 &3 而非 >>2 | 改为 localY>>2 等 |
| 7 | 高度图优先级 | WORLD_SURFACE不含水 | 改为优先 MOTION_BLOCKING_NO_LEAVES |
| 8 | 高度图解码错误 | 索引公式 z*16+x | 改为 Wiki规范 x+16*z |
| 9 | 亮度读取错误 | 索引判断条件复杂 | 改为 Wiki公式 `(data[yzx>>1]>>(4*(yzx&1)))&0xF` |
| 10 | 未生成区块渲染 | 状态检查不完整 | 只保留 surface 及之后的状态 |

---

## 十一、Xaero 高度图实现详解（源码分析）

### 11.1 高度图数据来源

**来源文件**: `.mca` 文件 → Chunk NBT → `Heightmaps.WORLD_SURFACE`

**Xaero 源码位置**: `WorldDataReader.java` 第 357-370 行

```java
// Xaero 使用两种格式兼容
boolean oldHeightMap = !levelCompound.contains("Heightmaps", 10);
if (oldHeightMap) {
    // 旧格式: int[] HeightMap (1.17及更早)
    oldHeightMapArray = levelCompound.getIntArray("HeightMap");
    heightMapExists = oldHeightMapArray.length == 256;
} else {
    // 新格式: LongArray WORLD_SURFACE (1.18+)
    long[] heightMapArray = levelCompound.getCompound("Heightmaps").getLongArray("WORLD_SURFACE");
    int potentialBitsPerHeight = heightMapArray.length / 4;  // 计算位宽
    heightMapExists = potentialBitsPerHeight > 0 && potentialBitsPerHeight <= 10;
    if (heightMapExists) {
        this.updateHeightArray(potentialBitsPerHeight);
        System.arraycopy(heightMapArray, 0, this.heightMapBitArray.getRaw(), 0, heightMapArray.length);
    }
}
```

### 11.2 高度图存储结构

**Xaero 源码位置**: `WorldDataReader.java` 第 143 行

```java
this.heightMapBitArray = new SimpleBitStorage(9, 256);
```

Xaero 使用 `SimpleBitStorage` 存储 256 个高度值（16×16 网格）。

**位宽计算**: `bitsPerHeight = LongArray.length / 4`

| 世界高度范围 | LongArray 长度 | Bits Per Height |
|-------------|---------------|-----------------|
| 128 格 (旧版) | 36 | 9 (36×64/256) |
| 256 格 (1.17) | 64 | 16 (但限制为 ≤10) |
| 384 格 (1.18+) | 57 | 9 (57×64/256≈14, 但实际用9) |

实际上 Minecraft 1.18+ 高度图固定使用 **9 bits**，因为世界最大连续高度差是 384 格（-64 到 319），但 heightmap 存储的是相对于 chunkBottomY 的偏移量，每个 chunk 的高度范围通常不超过 512（9 bits = 0-511）。

### 11.3 高度值含义

**新格式** (LongArray WORLD_SURFACE):
- 存储值 = 世界绝对Y坐标 - chunkBottomY
- 即: 世界Y = chunkBottomY + heightMapValue

**旧格式** (int[] HeightMap):
- 存储值 = 世界绝对Y坐标（直接使用）

### 11.4 高度图在扫描中的作用

Xaero 使用高度图确定每个 (x,z) 位置的**扫描起点**，从该点向下寻找第一个可见方块。

```java
// WorldDataReader.java 第 424-425 行
int heightMapValue = heightMapExists ?
    (oldHeightMap ? oldHeightMapArray[pos_2d] :     // 旧格式: 直接使用
     chunkBottomY + this.heightMapBitArray.get(pos_2d))  // 新格式: 偏移量+基线
    : Integer.MIN_VALUE;                             // 无高度图

int startHeight = cave && !fullCave ? caveStart :
    (ignoreHeightmaps || heightMapValue < chunkBottomY ?
     sectionBasedHeight : heightMapValue + 3);       // +3 容差
```

**+3 容差的原因**: 高度图记录的是 WORLD_SURFACE（包含所有方块），但高度图位置可能正好是草方块顶部。+3 确保扫描起点在草方块上方，这样向下扫描时能正确捕获草方块上的草/花/雪层。

### 11.5 Overlay 叠加层机制

**Xaero 源码位置**: `OverlayBuilder.java`

Overlay 构建器最多维护 10 层透明方块叠加层。关键机制：

```java
// OverlayBuilder.java 第 47-78 行
public void build(BlockState state, int opacity, byte light, ...) {
    // 如果当前无 overlay 或方块类型改变 → 创建新 overlay
    if (currentOverlay == null || currentOverlay.getState() != state) {
        ++this.currentOverlayIndex;
        nextOverlay.write(state, light, glowing);
    }
    // 累加 opacity
    currentOverlay.increaseOpacity(opacity);
}
```

**合并规则**: 连续相同 BlockState 的透明方块共享一个 overlay，opacity 累加。类型改变时分叉。

**opacity 来源**: Xaero 使用 `state.getLightBlock()` 获取方块的遮挡值：
- 水: 1 (但 Xaero 在 overlay 参数中硬编码为 5)
- 岩浆: 15 (Xaero 硬编码为 12)
- 玻璃: 0 (Xaero 硬编码为 3)
- 树叶: 通常为 2-4 (Xaero 硬编码为 8)

### 11.6 topHeight 计算

**Xaero 源码位置**: `WorldDataReader.java` 第 422-424 行

```java
int[] topH = this.topH;
// 初始化
for (int i = 0; i < 256; ++i) topH[i] = worldBottomY;

// 在 buildPixelHelp 中更新
if (h > topH[pos_2d]) {
    topH[pos_2d] = h;
}
```

`topHeight` = 该 (x,z) 列上遇到的**最高透明方块的 Y 坐标**。如果无透明方块，`topHeight == height`。

### 11.7 Xaero 完整扫描流程图

```
buildTile() 被调用 (一个 16x16 chunk)
    │
    ├── 1. 初始化 256 个位置的状态数组
    │      blockFound[256]=false, underair[256]=false
    │      topH[256]=worldBottomY, lightLevels[256]=0
    │
    ├── 2. 解析高度图 (Heightmaps.WORLD_SURFACE)
    │      → heightMapBitArray (SimpleBitStorage)
    │
    ├── 3. 遍历 sections (从高到低)
    │      │
    │      ├── 3a. 解析 block_states palette + data
    │      ├── 3b. 解析 BlockLight / SkyLight 数组
    │      │
    │      └── 3c. 遍历 16x16 位置
    │            │
    │            ├── if blockFound[pos] → 跳过
    │            │
    │            ├── 计算 startHeight:
    │            │   heightMapValue = chunkBottomY + heightMapBitArray.get(pos)
    │            │   startHeight = heightMapValue + 3  (容差)
    │            │
    │            ├── if startHeight >= sectionHeight → continue (跳过此section)
    │            │
    │            ├── 确定 localStartHeight:
    │            │   如果 startHeight 在 section 范围内 → localStartHeight = startHeight & 0xF
    │            │   否则 → localStartHeight = 15
    │            │
    │            └── 向下扫描 (ly = localStartHeight → 0):
    │                  │
    │                  ├── 方块 = 空气:
    │                  │   underair[pos] = true
    │                  │
    │                  ├── 方块 = 透明(水/玻璃/树叶):
    │                  │   overlayBuilder.build(state, opacity, light)
    │                  │   if h > topH[pos]: topH[pos] = h
    │                  │   继续扫描
    │                  │
    │                  └── 方块 = 有颜色实体(石头/泥土/草):
    │                      MapBlock.write(state, h, topH[pos], null, light, glowing, false)
    │                      blockFound[pos] = true
    │                      fillCounter--
    │                      break
    │
    └── 4. 检查 fillCounter == 0 → 提前终止
```

### 11.8 与我们的实现的关键差异

| 方面 | Xaero 源码 | 我们的简化实现 |
|------|-----------|--------------|
| 数据结构 | 256 个并行数组 (blockFound, underair 等) | 直接 Pixel 对象 |
| 扫描方式 | 逐 section 从上到下遍历 | 对每列独立从上到下扫描 |
| Overlay 构建 | OverlayBuilder 合并连续同类型方块 | 简化为直接添加 |
| 光照 | 从 NBT BlockLight/SkyLight 数组读取 | 硬编码 15 (日光) |
| 洞穴模式 | caveStart/caveDepth 参数控制 | 不支持 (非洞穴模式) |
| 生物群系 | 延迟填充 + zoomer 插值 | 直接从 section biome palette 读取 |

---

*更新: 2026/05/10 — 基于 .tool/aero-decompiled 源码分析更新高度图和 Overlay 实现细节*

---

Sources:
- [Minecraft Wiki - Region file format](https://minecraft.wiki/w/Region_file_format)
- [Minecraft Wiki - NBT format](https://minecraft.wiki/w/NBT_format)
- [wiki.vg - Chunk Format](https://wiki.vg/Chunk_format)
- [Wiki.vg - Anvil file format](https://wiki.vg/Anvil_file_format)

*更新: 2026/05/13 — 基于 Wiki 规范更新高度图解码、亮度读取、区块状态检查*

*源码参考*:
- `WorldDataReader.java` — 高度图解析 + 可见表面扫描逻辑
- `OverlayBuilder.java` — Overlay 合并规则
- `MapBlock.java` — Parameters 位域编码
- `MapSaveLoad.java` — 序列化/反序列化
- `ChunkSectionParser.java` — 亮度读取（Wiki规范）
- `ChunkDataParser.java` — 高度图解码（Wiki规范）、区块状态检查
