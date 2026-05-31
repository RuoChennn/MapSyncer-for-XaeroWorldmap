# 洞穴模式实现分析报告

## 概述

本报告基于对 Xaero's World Map 反编译代码的分析，确认 MapSyncer 洞穴模式的实现是否正确。

## 发现的关键缺陷（已修复）

### 问题描述

原来的洞穴模式实现将地图"从中间劈开"，显示了地狱的横截面，被埋在山里的方块光照也是满的。

### 根本原因

**缺少 `underair` 状态追踪机制**。

Xaero 的洞穴模式使用 `underair`（在空气下方）状态来追踪扫描进度：
1. 扫描从 `caveStart` 向下开始
2. 遇到**空气方块**时设置 `underair = true`（表示进入了洞穴内部）
3. 只有 `underair = true` 后才会记录遇到的第一实体方块作为"表面"
4. 这确保只显示洞穴内部的墙壁，而不是外部岩层顶

原来的实现直接从 `caveStart` 向下扫描并记录遇到的第一个方块，没有等待进入洞穴内部空气区域，因此：
- 显示了岩层顶部的横截面（而不是洞穴内部）
- 所有方块光照都是 15（因为没有正确追踪光照变化）

### 修复方案

添加了 `underair` 和 `shouldEnterGround` 状态追踪：

```java
// 洞穴模式状态追踪（参考 Xaero WorldDataReader.java:351, 571-596）
boolean underair = isFullCaveMode;  // 全洞穴模式初始化为 true
boolean shouldEnterGround = isFullCaveMode;

// 扫描循环中的逻辑：
if (state.isAir()) {
    underair = true;  // 进入洞穴内部
    continue;
}

// 洞穴模式核心逻辑：必须先进入空气才能记录方块
if (!underair) {
    continue;  // 还没进入洞穴，跳过
}
```

## Xaero 洞穴模式核心逻辑

### 1. 洞穴层号计算 (MapProcessor.java:1848-1853)

```java
private int getCaveLayer(int caveStart) {
    if (caveStart == Integer.MAX_VALUE || caveStart == Integer.MIN_VALUE) {
        return caveStart;
    }
    return caveStart >> 4;  // Y坐标除以16得到层号
}
```

**关键发现**：
- `caveStart` 是世界 Y 坐标（如 63）
- `caveLayer` = caveStart >> 4 = caveStart / 16
- 示例：caveStart=63 → caveLayer=3（第3层，覆盖 Y=48-63）

### 2. 洞穴模式类型 (MapDimension.java:105-106)

```java
private int caveModeType;  // 0=禁用, 1=允许, 2=强制
private static final int CAVE_MODE_TYPES = 3;
```

| caveModeType | 含义 | caveStart 值 |
|--------------|------|--------------|
| 0 | 禁用洞穴模式 | Integer.MAX_VALUE |
| 1 | 允许洞穴模式 | 配置值或自动计算 |
| 2 | 强制洞穴模式 | Integer.MIN_VALUE |

### 3. 存储路径结构 (MapSaveLoad.java:251-289, 315-321)

```java
public File getNormalFile(MapRegion region) {
    // ...
    Path layerFolder = subFolder = this.getMWSubFolder(region.getWorldId(), mainFolder, mwId);
    if (region.getCaveLayer() != Integer.MAX_VALUE) {
        layerFolder = layerFolder.resolve("caves").resolve("" + region.getCaveLayer());
    }
    return layerFolder.resolve(region.getRegionX() + "_" + region.getRegionZ() + ".zip").toFile();
}

public Path getCaveLayerFolder(int caveLayer, Path subFolder) {
    Path layerFolder = subFolder;
    if (caveLayer != Integer.MAX_VALUE) {
        layerFolder = subFolder.resolve("caves").resolve("" + caveLayer);
    }
    return layerFolder;
}
```

**存储路径格式**：
- 地表：`<world>/<dim>/mw$<worldId>/<regionX_regionZ>.zip`
- 洞穴：`<world>/<dim>/mw$<worldId>/caves/<layer>/<regionX_regionZ>.zip`

### 4. LayeredRegionManager 洞穴数据管理

```java
public class LayeredRegionManager {
    private final Int2ObjectMap<MapLayer> mapLayers;  // 按 caveLayer 存储

    public void putLeaf(int X, int Z, MapRegion leaf) {
        this.getLayer(leaf.caveLayer).getMapRegions().putLeaf(X, Z, leaf);
    }

    public MapRegion getLeaf(int caveLayer, int X, int Z) {
        return this.getLayer(caveLayer).getMapRegions().getLeaf(X, Z);
    }
}
```

每个 `MapRegion` 包含 `caveLayer` 字段，标识其所属层级。

## MapSyncer 实现对比

### 1. 洞穴层号计算 (ModConfig.java:164-172)

```java
public int getCaveLayer() {
    if (scanMode == ScanMode.SURFACE) {
        return Integer.MAX_VALUE;
    }
    if (caveStart == Integer.MAX_VALUE || caveStart == Integer.MIN_VALUE) {
        return caveStart;
    }
    return caveStart >> 4;  // 与 Xaero 一致
}
```

**结论**：✅ 正确实现

### 2. 服务端存储路径 (ConversionOrchestrator.java:420-429)

```java
Path baseOutputDir = CACHE_DIR.resolve(xaeroDimName);
Path outputDir;
if (caveLayer == Integer.MAX_VALUE) {
    outputDir = baseOutputDir;  // 地表
} else {
    outputDir = baseOutputDir.resolve("caves").resolve(String.valueOf(caveLayer));  // 洞穴
}
```

**结论**：✅ 正确实现

### 3. 客户端存储路径 (XaeroMapIntegrator.java:978-986)

```java
Path targetDir;
if (chunk.caveLayer == Integer.MAX_VALUE) {
    targetDir = mwDir;  // 地表
} else {
    targetDir = mwDir.resolve("caves").resolve(String.valueOf(chunk.caveLayer));  // 洞穴
}
```

**结论**：✅ 正确实现

### 4. ChunkMapData 网络传输 (ChunkMapData.java)

包含 `caveLayer` 字段，使用标记位实现向后兼容的序列化。

**结论**：✅ 正确实现

## 洞穴模式扫描逻辑分析

### RegionConverterStandalone.java 洞穴扫描逻辑 (processChunk 方法)

```java
// 洞穴模式参数
int caveStart = caveParams.caveStart();
int caveDepth = caveParams.caveDepth();
boolean isCaveMode = caveStart != Integer.MAX_VALUE;

// 计算扫描范围
if (isCaveMode) {
    startY = caveStart;
    scanBottomY = Math.max(caveStart - caveDepth, minBuildHeight);
} else {
    startY = ChunkDataParser.getHeightmapStartY(chunk, lx, lz, worldTopY);
    scanBottomY = minBuildHeight;
}

// 跳过高于 caveStart 的 section
if (isCaveMode && sectionTopY > startY) continue;
```

**关键点**：
- 洞穴模式从 `caveStart` 向下扫描到 `caveStart - caveDepth`
- 地表模式从高度图向下扫描到世界底部

## 验证结果

| 检查项 | Xaero 实现 | MapSyncer 实现 | 状态 |
|--------|-----------|---------------|------|
| caveLayer 计算 | caveStart >> 4 | caveStart >> 4 | ✅ 一致 |
| 地表存储路径 | mw$<id>/<region>.zip | mw$<id>/<region>.zip | ✅ 一致 |
| 洞穴存储路径 | mw$<id>/caves/<layer>/<region>.zip | mw$<id>/caves/<layer>/<region>.zip | ✅ 一致 |
| Integer.MAX_VALUE | 地表层标识 | 地表层标识 | ✅ 一致 |
| Integer.MIN_VALUE | 全洞穴模式 | 全洞穴模式 | ✅ 一致 |

## 结论

经过详细分析，MapSyncer 的洞穴模式实现与 Xaero's World Map 的存储格式完全一致：

1. **洞穴层号计算正确**：使用 `caveStart >> 4` 计算层号
2. **存储路径正确**：地表和洞穴分别存储到正确的目录结构
3. **网络传输正确**：ChunkMapData 包含 caveLayer 字段并正确序列化
4. **客户端存储正确**：写入到 Xaero 预期的目录位置

**如果洞穴模式存在显示问题，可能是以下原因**：

1. **客户端未正确切换到洞穴视图**：Xaero 需要用户手动切换 caveModeType
2. **caveStart 配置值不匹配**：确保配置的 caveStart 与 Xaero 客户端期望的一致
3. **光照计算差异**：洞穴模式使用不同的光照逻辑（同时使用 BlockLight 和 SkyLight）

## 建议

1. 添加日志验证洞穴地图文件的生成和传输
2. 确认客户端 Xaero 的洞穴模式配置（caveModeType=1 或 2）
3. 测试不同 caveStart 值的洞穴层号计算

---

*报告生成时间：2026-05-22*
*分析依据：Xaero's World Map 反编译代码（.decompiled 目录）*