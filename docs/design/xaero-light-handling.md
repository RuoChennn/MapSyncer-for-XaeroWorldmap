# Xaero WorldMap 光照处理机制分析

## 一、光照在 Xaero 中的作用

光照值影响地图像素的渲染亮度。Xaero 地图并非单纯的颜色贴图，而是将光照等级存储在 `parameters` 位域中，客户端渲染时根据光照值计算像素亮度，使地图呈现出更自然的光照效果（如洞穴昏暗、地表明亮）。

## 二、光照来源：从 .mca NBT 中提取

### 2.1 数据来源

Xaero 从 chunk section NBT 中提取两种光照数据：

| 光照类型 | NBT 字段 | 含义 |
|---------|---------|------|
| BlockLight | `BlockLight` (TAG_ByteArray) | 方块发出的光照（火把、岩浆等） |
| SkyLight | `SkyLight` (TAG_ByteArray) | 来自天空的自然光照 |

**WorldDataReader.java:464-469:**
```java
if (sectionCompound.contains("BlockLight", 7) && (lightMap = sectionCompound.getByteArray("BlockLight")).length != 2048) {
    lightMap = null;  // 长度异常则丢弃
}
if (cave && sectionCompound.contains("SkyLight", 7) && (skyLightMap = sectionCompound.getByteArray("SkyLight")).length != 2048) {
    skyLightMap = null;
}
```

### 2.2 Nibble Array 解码

光照数据以 **nibble array**（半字节数组）存储，每个值占 4 bits（0-15），2048 字节对应 4096 个方块位置（16×16×16 section）。

**WorldDataReader.java:748-754:**
```java
private byte nibbleValue(byte[] array, int index) {
    byte b = array[index >> 1];           // index / 2
    if ((index & 1) == 0) {               // 偶数索引 → 低 4 位
        return (byte)(b & 0xF);
    }
    return (byte)(b >> 4 & 0xF);         // 奇数索引 → 高 4 位
}
```

## 三、光照值的处理流程

### 3.1 初始化

**WorldDataReader.java:352-353:**
```java
this.lightLevels[i] = 0;                    // BlockLight 默认 0
this.skyLightLevels[i] = worldHasSkylight ? 15 : 0;  // SkyLight 默认 15（地表日光）
```

### 3.2 扫描时收集光照

在从上到下扫描每个方块列时，当找到表面方块后，记录该位置的光照：

**WorldDataReader.java:556-561:**
```java
byte dataLight = lightMap == null ? (byte)0 : this.nibbleValue(lightMap, pos);
// 洞穴模式下，如果方块光 < 15 且有天空光，也记录天空光
if (cave && dataLight < 15 && worldHasSkylight) {
    int dataSkyLight = !ignoreHeightmaps && !fullCave && startHeight > heightMapValue
        ? 15  // 高于高度图的位置 = 直接日照
        : (skyLightMap == null ? 0 : this.nibbleValue(skyLightMap, pos));
    this.skyLightLevels[pos_2d] = dataSkyLight;
}
this.lightLevels[pos_2d] = dataLight;
```

### 3.3 最终光照选择（写入 MapBlock 时）

**WorldDataReader.java:537-541:**
```java
byte light = this.lightLevels[pos_2d];
// 洞穴模式特殊处理：无 overlay 且 skyLight 更亮时，用 skyLight
if (cave && light < 15 && this.buildingObject.getNumberOfOverlays() == 0
    && (skyLight = this.skyLightLevels[pos_2d]) > light) {
    light = skyLight;
}
this.buildingObject.write(state, h, topH[pos_2d], null, light, glowing, cave);
```

**关键逻辑：**
- **地表模式（非洞穴）**：只使用 `lightLevels`（方块光），不关心天空光
- **洞穴模式**：比较方块光和天空光，取更亮的那个（但只有当方块光 < 15 时才考虑天空光）

## 四、光照存储：MapBlock.parameters 位域

### 4.1 位域布局

MapBlock 通过 `getParametres()` 方法将所有元数据打包到一个 `int` 中：

**MapBlock.java / MapPixel.java:53-62:**
```java
public int getParametres() {
    int parametres = 0;
    parametres |= !this.isGrass() ? 1 : 0;            // Bit 0: 非草地
    parametres |= this.getNumberOfOverlays() != 0 ? 2 : 0;  // Bit 1: 有覆盖层
    parametres |= this.light << 8;                    // Bits 8-11: 光照 (4 bits, 0-15)
    parametres |= (this.getHeight() & 0xFF) << 12;    // Bits 12-19: 高度低 8 位
    parametres |= this.biome != null ? 0x100000 : 0;  // Bit 20: 有生物群系
    parametres |= this.height != this.topHeight ? 0x1000000 : 0; // Bit 24: 顶部高度不同
    return parametres |= (this.getHeight() >> 8 & 0xF) << 25;    // Bits 25-28: 高高 4 位
}
```

### 4.2 光照位提取

**MapSaveLoad.java:1471 (loadPixel):**
```java
pixel.setLight((byte)(parametres >> 8 & 0xF));  // 右移 8 位，取低 4 位
```

### 4.3 Overlay 光照（独立位域）

Overlay（水、玻璃等透明方块覆盖层）有自己的光照值，使用不同的位：

**Overlay.java:54-59:**
```java
public int getParametres() {
    int parametres = 0;
    parametres |= !this.isWater() ? 1 : 0;       // Bit 0: 非水
    parametres |= this.light << 4;               // Bits 4-7: 光照 (4 bits, 0-15)
    return parametres |= (this.opacity & 0xF) << 11;  // Bits 11-14: 不透明度
}
```

## 五、二进制文件格式中的光照

### 5.1 序列化（savePixel）

光照值已经嵌入 `parameters` 整数中，直接写入即可：

**MapSaveLoad.java:1328-1377:**
```java
int parametres = pixel.getParametres();  // 包含 bits 8-11 的光照
// 添加 paletteNew、biomePaletteNew 等标志位
out.writeInt(parametres);  // 光照随 parameters 一起写入
// 后续写入 state、height、overlays、biome 等附加数据
```

### 5.2 反序列化（loadPixel）

**MapSaveLoad.java:1471-1472:**
```java
pixel.setLight((byte)(parametres >> 8 & 0xF));
pixel.setGlowing(this.mapProcessor.getMapWriter().isGlowing(pixel.getState()));
```

## 六、地表模式 vs 洞穴模式光照对比

| 维度 | 地表模式（普通地图） | 洞穴模式 |
|------|---------------------|----------|
| 默认 BlockLight | 0 | 0 |
| 默认 SkyLight | 15（如果有天空） | 0 或 15 |
| 使用哪种光照 | 只记录 BlockLight | BlockLight 和 SkyLight 都记录 |
| 最终选择 | 使用记录的 BlockLight | 取 BlockLight 和 SkyLight 中较亮的 |
| 高于高度图 | 不适用 | SkyLight 强制 15（直接日照） |

## 七、光照渲染（客户端侧）

**MapPixel.java:291-297:**
```java
public float getBlockBrightness(float min, int l, int sun) {
    return (min + (float)Math.max(sun, l)) / (15.0f + min);
}

private float getPixelLight(float min, int topLightValue) {
    return topLightValue == 0 ? 0.0f : this.getBlockBrightness(min, topLightValue, 0);
}
```

渲染时像素 alpha 通道由光照决定：
```java
result_dest[3] = (int)(this.getPixelLight(lightMin, topLightValue) * 255.0f);
```

**发光方块处理**：发光方块（如火把、岩浆块）强制 `light = 15`。

## 八、服务端当前实现 vs Xaero 的差异

### 8.1 当前代码

在 `RegionConverter.java` 中：

```java
data.lightMap[relX][relZ] = 15;  // 硬编码日光
```

**问题**：所有方块固定光照 15，没有从 `.mca` 文件中提取 `BlockLight` 和 `SkyLight` 数据。

### 8.2 影响

- 所有地表和地下区域都显示为完全明亮
- 无法区分洞穴（应昏暗）和露天区域（应明亮）
- 丢失了火把等光源的亮度信息

### 8.3 Xaero 的正确做法

Xaero 从 chunk section NBT 的 `BlockLight` 和 `SkyLight` 字节数组中提取对应位置的光照值，使用 `nibbleValue()` 解码 4-bit 光照等级。

## 九、关键代码位置速查

| 文件 | 行号范围 | 功能 |
|------|---------|------|
| WorldDataReader.java | 352-353 | 光照数组初始化 |
| WorldDataReader.java | 464-469 | 从 NBT 读取 BlockLight/SkyLight |
| WorldDataReader.java | 556-561 | 扫描时收集光照值 |
| WorldDataReader.java | 537-541 | 最终光照选择（洞穴模式逻辑） |
| WorldDataReader.java | 748-754 | nibbleValue 解码方法 |
| MapBlock/MapPixel.java | 53-62 | getParametres() 位域打包 |
| MapSaveLoad.java | 1471 | loadPixel 光照提取 |
| Overlay.java | 54-59 | Overlay 光照位域 |
| MapPixel.java | 291-297 | 光照→亮度计算 |
