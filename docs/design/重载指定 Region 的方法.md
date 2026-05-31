# 重载指定 Region 的方法

基于 Xaero's World Map 反编译代码分析。

## 1. 获取指定坐标的 MapRegion

通过 `MapProcessor` 获取：
```java
MapProcessor.getLeafMapRegion(int caveLayer, int regX, int regZ, boolean create)
```
- `caveLayer`: 洞穴层（Integer.MAX_VALUE 表示地表层）
- `regX`, `regZ`: region 坐标（block坐标 >> 9 即 block/512）
- `create`: 是否在不存在时创建新的 region

## 2. 请求加载 Region

获取到 MapRegion 后，可以通过 `MapSaveLoad.requestLoad` 请求加载：
```java
mapProcessor.getMapSaveLoad().requestLoad(MapRegion region, String reason)
```

或者使用 `MapRegion.requestRefresh` 刷新已加载的 region：
```java
region.requestRefresh(MapProcessor mapProcessor)
```

## 3. 检查是否可以请求重载

`MapRegion.canRequestReload_unsynced()` 方法检查是否满足重载条件：
```java
// 满足条件：没有重载请求、没有缓存请求、没有刷新、loadState 为 0/4/2且beingWritten
return !reloadHasBeenRequested() && !recacheHasBeenRequested()
    && !isRefreshing() && (loadState == 0 || loadState == 4 || loadState == 2 && isBeingWritten());
```

## 4. 完整的重载流程示例

```java
// 1. 获取 MapProcessor
MapProcessor mapProcessor = WorldMap.mapProcessor;

// 2. 计算 region 坐标（block坐标转换为region坐标）
int regX = blockX >> 9;  // blockX / 512
int regZ = blockZ >> 9;  // blockZ / 512
int caveLayer = Integer.MAX_VALUE; // 地表层

// 3. 获取或创建 region
MapRegion region = mapProcessor.getLeafMapRegion(caveLayer, regX, regZ, true);

// 4. 检查是否可以请求重载
if (region.canRequestReload_unsynced()) {
    // 5. 设置标记
    region.setHasHadTerrain();

    // 6. 请求加载（优先级加载）
    mapProcessor.getMapSaveLoad().requestLoad(region, "manual reload", true);
}
```

## 5. 强制清除并重新加载

如果需要强制清除再重新加载：
```java
// 先清除 region 数据
region.clearRegion(mapProcessor);

// 然后重新请求加载
mapProcessor.getMapSaveLoad().requestLoad(region, "force reload", true);
```

## 关键文件位置

| 文件 | 行号 | 方法 |
|------|------|------|
| MapProcessor.java | 1215 | `getLeafMapRegion` |
| MapSaveLoad.java | 830 | `requestLoad` |
| MapRegion.java | 723 | `canRequestReload_unsynced` |
| MapRegion.java | 171 | `clearRegion` |

## LoadState 状态说明

- 0: 未加载
- 1: 正在加载
- 2: 已加载
- 3: 处理中/上传中
- 4: 已卸载/等待重载

## 注意事项

1. 所有操作需要在主线程（Minecraft 客户端线程）执行
2. 需要确保 `mapSaveLoad.isRegionDetectionComplete()` 返回 true
3. 同步操作需要使用 `synchronized (region)` 保护
4. 重载请求会进入加载队列，由 MapRunner 线程异步处理