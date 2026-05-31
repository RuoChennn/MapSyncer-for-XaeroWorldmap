# Xaero 地图叠加层（Overlay）机制分析

## 1. 核心类概览

| 类 | 路径 | 职责 |
|---|---|---|
| `Overlay` | `xaero/map/region/Overlay.java` | 单个叠加层像素数据，继承 `MapPixel` |
| `OverlayBuilder` | `xaero/map/region/OverlayBuilder.java` | 从下往上构建一个像素列的多个叠加层 |
| `OverlayManager` | `xaero/map/region/OverlayManager.java` | 去重管理，复用相同属性叠加层对象 |
| `MapBlock` | `xaero/map/region/MapBlock.java` | 地图主方块，持有 `ArrayList<Overlay>` 列表 |
| `MapPixel` | `xaero/map/region/MapPixel.java` | 像素基类，包含颜色计算逻辑（含叠加层混合） |

## 2. 数据模型

### 2.1 Overlay 结构

`Overlay` 继承自 `MapPixel`，额外增加了一个 `opacity` 字段：

```java
// Overlay.java
private byte opacity;  // 透明度/不透明度，范围 0-15
protected BlockState state;  // 方块状态（继承自 MapPixel）
protected byte light;        // 光照等级 0-15（继承自 MapPixel）
protected boolean glowing;   // 是否自发光（继承自 MapPixel）
```

- `opacity` 通过 `increaseOpacity(int toAdd)` 累加，上限为 15
- `getParametres()` 将状态编码为整数：bit 0 = 是否非水, bits 4-7 = 光照, bits 11-14 = 透明度

### 2.2 MapBlock 中的叠加层列表

```java
// MapBlock.java
private ArrayList<Overlay> overlays;  // 每个像素点可持有一列叠加层
```

`MapBlock.getParametres()` 中通过 bit 1 标记是否存在叠加层：
```java
parametres |= this.getNumberOfOverlays() != 0 ? 2 : 0;
```

## 3. 叠加层构建流程

### 3.1 构建入口（客户端实时）

`MapWriter.loadPixel()` 是主要入口，从世界 Y 轴高处向下遍历方块：

```
1. overlayBuilder.startBuilding()          // 重置构建状态
2. 从 highY 向 lowY 逐层扫描方块
   ├─ 不透明方块 → loadPixelHelp() 找到主方块，退出循环
   └─ 透明/半透明方块 → shouldOverlayCached() = true
      └─ overlayBuilder.build(state, lightBlock, light, processor, biome)
         // 累加到当前叠加层
3. overlayBuilder.finishBuilding(pixel)    // 将叠加层附加到 MapBlock
```

### 3.2 OverlayBuilder.build() 逻辑

```java
// OverlayBuilder.java
// MAX_OVERLAYS = 10，最多支持 10 层叠加

public void build(BlockState state, int opacity, byte light, MapProcessor mapProcessor, ResourceKey<Biome> biomeId) {
    Overlay currentOverlay = getCurrentOverlay();
    Overlay nextOverlay = null;
    if (currentOverlayIndex < overlayBuildingSet.length - 1) {
        nextOverlay = overlayBuildingSet[currentOverlayIndex + 1];
    }

    // 检测方块类型是否变化
    TextureAtlasSprite icon = ...;
    boolean changed = icon != prevIcon;

    // 如果方块类型变了，创建新叠加层
    if (nextOverlay != null && (currentOverlay == null || changed)) {
        nextOverlay.write(state, light, glowing);
        currentOverlay = nextOverlay;
        ++currentOverlayIndex;
    }

    // 累加当前叠加层的不透明度
    currentOverlay.increaseOpacity(opacity);
}
```

**关键逻辑**：
- 相同 `BlockState` 的连续透明方块被合并到同一个 `Overlay` 中
- 方块类型变化时创建新的 `Overlay` 层
- 每层的 `opacity` = 该层所有方块的 `lightBlock` 值之和（上限 15）
- biome 取自第一层叠加层对应的生物群系

### 3.3 finishBuilding() — 去重与附加

```java
public void finishBuilding(MapBlock block) {
    for (int i = 0; i <= currentOverlayIndex; ++i) {
        Overlay o = overlayBuildingSet[i];
        Overlay original = overlayManager.getOriginal(o);  // 去重复用
        if (o == original) {
            overlayBuildingSet[i] = new Overlay(AIR, 0, false);  // 新对象，重置
        }
        block.addOverlay(original);  // 附加到 MapBlock
    }
}
```

### 3.4 OverlayManager 去重机制

使用三层嵌套 `HashMap` 实现对象池复用：

```
HashMap<BlockState, HashMap<Byte(light), HashMap<Short, Overlay>>>
                                         ^
                                         |___ key = (opacity << 8) | glowing
```

- 相同 `(BlockState, light, opacity, glowing)` 组合共享同一个 `Overlay` 实例
- `numberOfUniques` 跟踪唯一叠加层数量

## 4. 叠加层渲染 — 颜色混合

### 4.1 MapPixel.getPixelColours() 中的叠加层处理

渲染时，主方块颜色先计算出来，然后叠加层逐层混合：

```java
// MapPixel.java — 核心混合逻辑
if (overlays != null && !overlays.isEmpty()) {
    int sun = 15;  // 初始光照
    for (int i = 0; i < overlays.size(); ++i) {
        Overlay o = overlays.get(i);

        // 递归获取叠加层的颜色 → 写入 result_dest[0..3]
        o.getPixelColour(block, result_dest, ..., mapWriter, ...);

        if (result_dest[0] == -1) continue;
        hasValidOverlay = true;

        if (i == 0) {
            topLightValue = o.light;  // 第一层叠加层光照用于顶部
        }

        // 计算叠加层亮度
        float transparency = (float)result_dest[3] / 255.0f;
        float overlayIntensity = getBlockBrightness(lightMin, o.light, sun) * transparency * currentTransparencyMultiplier;

        // 累加颜色
        overlayRed   += result_dest[0] * overlayIntensity;
        overlayGreen += result_dest[1] * overlayIntensity;
        overlayBlue  += result_dest[2] * overlayIntensity;

        // 逐层衰减光照
        sun -= o.getOpacity();
        if (sun < 0) sun = 0;

        // 透明度累积衰减
        currentTransparencyMultiplier *= (1.0f - transparency);
    }
}

// 最终颜色 = 主方块颜色 * 透明度衰减 + 叠加层累加颜色
result_dest[0] = (int)(r * brightnessR * currentTransparencyMultiplier + overlayRed);
result_dest[1] = (int)(g * brightnessG * currentTransparencyMultiplier + overlayGreen);
result_dest[2] = (int)(b * brightnessB * currentTransparencyMultiplier + overlayBlue);
```

### 4.2 混合公式总结

```
最终R = 主方块R * 亮度R * 透明度乘积 + Σ(叠加层i_R * 叠加层i_亮度 * 叠加层i_透明度 * 透明度乘积_i)
最终G = 同上
最终B = 同上
```

其中：
- `透明度乘积_i = Π(1 - 叠加层j_透明度)` for j < i
- `叠加层_亮度 = getBlockBrightness(minLight, overlayLight, remainingSun)`
- 每经过一层，`sun` 减少该层的 `opacity`

## 5. 服务端生成流程

`WorldDataReader` 用于服务端世界数据生成，逻辑与客户端类似：

- 使用 `OverlayBuilder[256]` 数组，每个 2D 位置（64x64 区域中的位置）独立构建
- 同样遵循 `startBuilding() → build() → finishBuilding()` 三段式
- `buildPixelHelp()` 判断方块是否应作为叠加层：
  - 调用 `shouldOverlayCached()` → 透明/半透明方块返回 true
  - 调用 `hasVanillaColor()` → 无颜色的方块不作为主方块

## 6. 服务端下发地图场景中的意义

从服务端向客户端下发地图时，需要处理的叠加层相关数据：

1. **主方块数据**：`BlockState` + `height` + `topHeight` + `biome` + `light` + `glowing`
2. **叠加层列表**：每个 `MapBlock` 可能携带 0~10 个 `Overlay`
3. **每个 Overlay**：`BlockState` + `light` + `opacity` + `glowing`
4. **渲染结果**：最终颜色由主方块 + 所有叠加层混合计算得出

如果服务端只下发主方块数据（不含叠加层），客户端显示的地图将缺少：
- 玻璃、水等透明方块的视觉效果
- 多层透明结构（如多层玻璃天花板、树冠）的叠加颜色
- 光照穿透效果

## 7. 叠加层触发条件

`MapWriter.shouldOverlayCached()` 判断方块是否应作为叠加层：
- 透明方块（`BlockState` 透明属性）
- 液体
- 染色玻璃/玻璃板（受配置 `stainedGlass` 控制）
- 花和植物（受配置 `flowers` 控制）
- 短方块（地毯、台阶等）

这些方块不会阻挡主方块的确定，而是作为额外图层叠加在主方块之上。
