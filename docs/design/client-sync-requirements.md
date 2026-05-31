# 客户端地图同步功能需求

## 概述

实现客户端与服务端之间的 Xaero World Map 地图数据同步功能，支持增量更新和本地完整性缓存。

---

## 已实现功能

### 1. 基础同步架构

**需求：** 实现客户端向服务端请求地图数据的完整流程。

**实现：**
- 客户端计算本地region文件的SHA-256哈希
- 发送 `SyncRequestPayload` 到服务端，携带所有region哈希
- 服务端对比哈希，返回差异region数据
- 客户端接收并写入数据

**相关文件：**
- `SyncButtonHandler.java` - 同步按钮处理
- `ClientHashManager.java` - 哈希计算
- `ServerSyncHandler.java` - 服务端同步处理
- `MapPacketReceiver.java` - 客户端数据接收

---

### 2. 客户端路径修复

**需求：** 客户端地图文件需存放在正确的 Xaero 路径结构中。

**路径格式：**
```
xaero/world-map/Multiplayer_<serverIP>/null/mw$<worldId>/<regionX_regionZ>.zip
```

**实现：**
- 使用 `connection.getServerData()` 获取服务器地址
- 清理服务器IP（移除端口、括号等）
- 使用 `mc.level.getLevelData().hashCode()` 计算世界ID
- 维度名转换：`overworld` → `null`，`the_nether` → `DIM-1`，`the_end` → `DIM1`

**相关文件：**
- `XaeroMapIntegrator.java`

---

### 3. 分批传输

**需求：** 避免单个数据包过大导致传输失败。

**实现：**
- 服务端按 1MB 限制分批发送数据
- 发送进度更新到客户端
- 最后一批标记 `isComplete=true`

**相关文件：**
- `ServerSyncHandler.java` - `MAX_PACKET_SIZE = 1_000_000`

---

### 4. 同步完成后刷新地图

**需求：** 同步完成后自动调用 Xaero 的 Reload All Regions 功能。

**实现：**
- 使用反射调用 Xaero 内部API
- 调用链：`WorldMapSession.getCurrentSession()` → `getMapProcessor()` → `getMapWorld()` → `getCurrentDimension()` → `startFullMapReload(0, false, mapProcessor)`
- 显示刷新提示

**相关文件：**
- `MapPacketReceiver.java` - `triggerXaeroReload()`

---

### 5. 区块级增量合并

**需求：** 从服务端获取文件后，不直接替换，而是合并客户端已有区块，只填充客户端不存在的新区块。

**实现：**
- 解析 Xaero region.xaero 文件格式（版本6.8）
- 按区块坐标提取数据（64个区块，坐标0-63）
- 合并策略：保留客户端已有区块，只添加新区块
- 支持完整的像素格式解析（NBT、palette等）

**相关文件：**
- `RegionMerger.java` - 区块级合并逻辑
- `XaeroMapIntegrator.java` - 使用合并而非覆盖

---

### 6. 本地完整性缓存

**需求：** 同步前检测本地地图是否已完全生成，已完成的region不再向服务端申请同步。

**实现：**
- 创建 `CompletedRegionsCache` 缓存类
- 检测region是否包含全部64个区块且有有效像素数据
- 完整的region标记到缓存并持久化到磁盘
- 未来同步时直接跳过已缓存的region

**缓存文件位置：**
```
mw$worldId/completed_regions.cache
```

**缓存格式：**
- 文件头：int (数量)
- 每条记录：UTF string (格式: `dimension:regionX_regionZ`)

**相关文件：**
- `CompletedRegionsCache.java` - 缓存管理
- `RegionMerger.checkCompleteness()` - 完整性检测
- `ClientHashManager.computeHashesForSync()` - 排除完整region
- `SyncButtonHandler.java` - 使用缓存机制

---

## 客户端侧架构

```
客户端模块:
├── XaeroMapIntegrator.java      - 路径管理和数据写入
├── ClientHashManager.java       - 哈希计算和完整性检测
├── CompletedRegionsCache.java   - 完成region缓存
├── RegionMerger.java            - 区块级增量合并
├── SyncButtonHandler.java       - 同步按钮UI
├── MapPacketReceiver.java       - 网络包接收处理
├── SyncProgressTracker.java     - 进度追踪
```

---

## 网络协议

### SyncRequestPayload (Client → Server)
- `Map<String, String> clientHashes` - 相对路径 → SHA-256哈希

### SyncResponsePayload (Server → Client)
- `List<ChunkMapData> chunks` - region数据列表
- `boolean isComplete` - 是否最后一批

### SyncProgressPayload (Server → Client)
- `int processed` - 已处理数量
- `int total` - 总数量
- `String status` - 状态描述

---

## 待优化项

1. 缓存过期机制：当服务端地图更新时，需要清除相关缓存
2. 增量合并的边界处理：处理区块边界上的数据一致性
3. 性能优化：大批量region的并行处理

---

## 更新日志

- 2026-05-14: 完成本地完整性缓存机制
- 2026-05-14: 完成区块级增量合并功能
- 2026-05-14: 完成同步后自动刷新地图
- 2026-05-14: 完成客户端路径修复
- 2026-05-14: 完成基础同步架构