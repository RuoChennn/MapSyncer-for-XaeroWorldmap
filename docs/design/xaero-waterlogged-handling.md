# Xaero 对含水方块（Waterlogged Blocks）的处理

## 结论

Xaero **没有**针对 `waterlogged` 属性做特殊区分处理。它统一通过 `FluidState` 来处理所有含流体的情况。

## 处理机制

### 1. FluidState 统一处理

Xaero 通过 `state.getFluidState()` 获取方块的流体状态，不关心方块是否含水：

- **水源方块**（如静止水、流动水）：`getFluidState()` 返回非空
- **含水方块**（如含水台阶、含水楼梯）：`getFluidState()` 同样返回非空
- **普通方块**：`getFluidState()` 返回空

### 2. 流体转方块缓存

使用 `CachedFunction<FluidState, BlockState>` 将流体状态转换为对应的方块状态：

```java
// MapWriter.java:204
this.fluidToBlock = new CachedFunction<FluidState, BlockState>(FluidState::createLegacyBlock);
// WorldDataReader.java:156 同样有此定义
```

### 3. 渲染逻辑（MapWriter.java）

在 `loadPixel` 方法中，Xaero 的处理流程：

```java
// 805-837 行
FluidState fluidFluidState = state.getFluidState();
if (!(fluidFluidState.isEmpty() || cave && shouldEnterGround)) {
    underair = true;
    BlockState fluidState = this.fluidToBlock.apply(fluidFluidState);
    // 将流体作为 overlay 层处理
    if (this.loadPixelHelp(..., fluidState, ..., fluidFluidState, ...)) {
        opaqueState = state;
        break;
    }
}
```

含水方块的流体会被当作 **overlay** 层叠加显示在主方块之上。

### 4. Overlay 保存优化（MapSaveLoad.java:1476-1495）

水方块在保存时有特殊标记优化：

```java
private void saveOverlay(Overlay o, DataOutputStream out) {
    boolean isWater = o.isWater(); // 判断是否为 Blocks.WATER
    // 如果是水，不保存完整 NBT，仅标记
    // 如果不是水，保存完整的方块状态 NBT
}
```

`Overlay.isWater()` 判断：
```java
// Overlay.java:51
public boolean isWater() {
    return this.state.getBlock() == Blocks.WATER;
}
```

### 5. 读取时的还原（MapSaveLoad.java:1518）

```java
if ((parametres & 1) != 0) {
    // 从 palette 或 NBT 读取方块状态
} else {
    state = Blocks.WATER.defaultBlockState(); // 标记为水的简化存储
}
```

## 服务端实现（基于 Wiki 规范）

### 1. Overlay opacity 计算

使用方块的实际 `lightBlock` 值（与 Xaero 一致）：

```java
// BlockClassifier.java
public static int getLightBlock(String blockName) {
    if (isWater(blockName)) return 1;   // 水: lightBlock=1
    if (isLava(blockName)) return 15;   // 熔岩: lightBlock=15
    if (isAir(blockName)) return 0;
    return 15;  // 大多数实体方块遮挡全部光照
}
```

**关键点**：水的 overlay opacity 使用 `lightBlock=1`，而不是硬编码值。

### 2. 含水方块处理流程

```java
// RegionConverterStandalone.java
// Step 1: 检查含水方块（方块本身作为表面 + 同层水overlay）
if (BlockClassifier.isWaterloggedSurface(state)) {
    topState = state;
    topY = worldY;
    
    // 含水方块添加同层水 overlay（opacity=1，与水的 lightBlock 一致）
    int opacity = 1;
    overlayList.add(new OverlayData("minecraft:water", worldY, opacity, overlayLight));
    
    surfaceLight = overlayLight;  // 含水方块使用 blockLight
    break;
}

// Step 2: 上方独立水方块作为 overlay
if (BlockClassifier.isTranslucentFluid(state)) {
    int opacity = BlockClassifier.getLightBlock(state);  // 水=1, 熔岩=15
    overlayList.add(new OverlayData(state.name(), worldY, opacity, overlayLight));
    continue;  // 继续向下扫描找表面
}
```

### 3. Heightmap 选择

使用 `MOTION_BLOCKING_NO_LEAVES` 高度图（包含水方块），确保正确检测上方水层：

```java
// ChunkDataParser.java
// 优先使用 MOTION_BLOCKING_NO_LEAVES（包含水方块）
if (heightmaps.contains("MOTION_BLOCKING_NO_LEAVES", 12)) {
    // 解码...
}
// 备用 WORLD_SURFACE（不包含水方块）
```

**原因**：`WORLD_SURFACE` 不包含水方块，导致上方有水时扫描起点可能在水层下方，错过检测水overlay。

## 对服务端生成的影响

在服务端生成 Xaero 地图时：

1. **含水方块处理**：方块本身作为表面，添加同层水 overlay（opacity=1）
2. **上方水层处理**：作为独立 overlay 层累加（每层 opacity=1）
3. **Heightmap 选择**：使用 MOTION_BLOCKING_NO_LEAVES 确保检测上方水
4. **保存格式**：水 overlay 使用简化的水标记存储；含水方块需要保存完整方块状态

## 关键源码位置

| 文件 | 行号 | 说明 |
|------|------|------|
| MapWriter.java | 181, 204 | fluidToBlock 缓存定义 |
| MapWriter.java | 715-716 | 流体透明渲染判断 |
| MapWriter.java | 805-837 | 流体 overlay 处理逻辑 |
| Overlay.java | 51 | isWater() 判断 |
| MapSaveLoad.java | 1476-1495 | overlay 保存（水的特殊优化） |
| MapSaveLoad.java | 1518 | overlay 读取（水的还原） |
| WorldDataReader.java | 130, 156 | 服务端数据读取的 fluidToBlock |
| WorldDataReader.java | 572-598 | 服务端 buildPixel 中的流体处理 |

## 服务端实现关键点

| 方面 | Xaero 客户端 | 服务端实现 |
|------|-------------|-----------|
| Overlay opacity | 状态累加 `state.getLightBlock()` | 使用 `getLightBlock()` (水=1) |
| 含水方块 | FluidState 自动处理 | 检测 waterlogged 属性 |
| Heightmap | WORLD_SURFACE | MOTION_BLOCKING_NO_LEAVES |
| 水累加 | OverlayBuilder 合并相同类型 | 直接累加 opacity |
