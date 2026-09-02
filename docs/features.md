# MapSyncer 功能模块

将服务端已探索 / 预生成的 MCA 区域转换为 Xaero's World Map 格式，经网络或离线包同步到客户端。

**适用场景**：新玩家进服、Chunky 预生成大地图、减少重复跑图。

**状态**：✅ 已实现

---

## 一、平台与架构

### 平台支持

| MC 版本 | Forge | NeoForge | Fabric | Java |
|---------|:-----:|:--------:|:------:|:----:|
| 1.20.1 | ✅ | — | ✅ | 17 |
| 1.21.1 | ✅ | ✅ | ✅ | 21 |
| 1.21.11 | ✅ | ✅ | ✅ | 21 |
| 26.1 | — | ✅ | ✅ | 25 |
| 26.2 | — | ✅ | ✅ | 25 |

> 1.20.4 前 NeoForge、26.x Forge 不做适配。客户端依赖 Xaero's World Map **1.40.11+**。服务端无需安装 Xaero。

### 架构分层

```
libs/common/        全版本业务逻辑（同步、缓存、命令逻辑、配置解析）
libs/core/          纯 Java（MCA/NBT、MapPackager）
libs/platform-api/  Platform 接口、网络 Payload、常量
libs/mc-1.20/       G1 锚点（1.20.1 API）
libs/mc-1.21/       G2 锚点（1.21.1 API）
libs/mc-1.21.11/    G3 锚点（1.21.11 API）
libs/mc-26/         G4 锚点（26.x API）

mc-{精确版本}/{fabric|forge|neoforge}/   Loader 胶水（Platform 实现、网络注册）
```

---

## 二、命令系统

### 客户端（`/mapsyncer`）

| 命令 | 说明 |
|------|------|
| `/mapsyncer` / `help` | 帮助（Forge/NeoForge 上 OP4+ 可见服务端命令说明） |
| `sync` / `sync <维度>` / `sync all` | 同步当前 / 指定 / 全部维度 |
| `autosync` / `autosync on\|off` | 查看或开关客户端自动同步（写入配置） |

**维度补全**：原版三维度、`all`、当前维度、已注册 Mod 维度、已有 Xaero 地图目录。

### 服务端（需 OP 等级 4）

| 平台 | 前缀 |
|------|------|
| Fabric | `/mapsyncerserver` |
| Forge / NeoForge | `/mapsyncer` |

| 命令 | 说明 |
|------|------|
| `generate` / `generate <维>` / `generate <维> <x> <z>` | 全维 / 指定维 / 单 region 生成 |
| `generate <维> --force` | 清缓存后强制重生成 |
| `status` | 转换进度、增量状态、各维度缓存统计 |
| `incremental` | 查看当前增量更新模式（DISABLED / TICK / SCHEDULED） |
| `incremental off` | 关闭增量更新 |
| `incremental tick [间隔]` | TICK 模式（2400–72000，默认 6000 = 5 分钟） |
| `incremental scheduled [时] [分]` | SCHEDULED 模式（默认 04:00，服务器本地时区） |
| `reloadconfig` | 从磁盘重载服务端配置 |
| `help` | 服务端帮助 |

生成任务进行中时拒绝新任务。生成前主线程 `saveEverything`（兼容 C2ME）。

---

## 三、客户端配置

| 路径 | 说明 |
|------|------|
| Fabric | `config/mapsyncer-client.properties`（可选 Cloth 客户端 GUI） |
| Forge / NeoForge | `config/mapsyncer-client.toml` → `[client]` |

| 配置项 | 默认 | 范围 | 说明 |
|--------|------|------|------|
| `hashThreads` | CPU/2 | 1~核心数 | 同步前本地 region CRC32 并行扫描 |
| `mapRegionLoadIntervalTicks` | 1 | -1~100 | 视距外 region 喂给 Xaero：-1=一次排空，0=仅视距内，N=每 N tick 1 个（防 OOM；兼容旧键 `mapRegionLoadsPerTick`） |
| `autoSyncEnabled` | true | — | 进服自动 sync（TICK/SCHEDULED）+ TICK 在线周期 sync；关后仍可手动 sync |

---

## 四、服务端配置

服务端仅通过配置文件管理（含 `/… reloadconfig`），**不提供 Cloth / 客户端 GUI 编辑服务端项**。

| 路径 | 说明 |
|------|------|
| Forge | `world/serverconfig/mapsyncer-server.toml`（每世界） |
| NeoForge | `config/mapsyncer-server.toml` |
| Fabric | `config/mapsyncer-server.properties`（camelCase / snake_case 双键名） |

### 通用 `[general]`

| 配置项 | 默认 | 范围 | 说明 |
|--------|------|------|------|
| `enableDebugLogging` | false | — | 地图生成调试日志 |
| `maxConcurrentRegions` | **0（自动）** | 0–16 | 并发 MCA→Xaero 数；**0** = `max(1, min(16, 逻辑处理器数 − 2))`；正数为手动 |
| `maxSyncPacketSize` | 262144 (256KB) | 64KB–1MB | 同步单包上限 |
| `syncSpeedLimitKBps` | 1024 (1MiB/s) | 0–10240 | 同步限速（0=不限） |

### 增量更新 `[incremental_update]`

| 配置项 | 默认 | 说明 |
|--------|------|------|
| `incrementalUpdateMode` | DISABLED | DISABLED / TICK / SCHEDULED |
| `incrementalUpdateIntervalTicks` | 6000 | TICK 间隔（最小 2400 = 2 分钟） |
| `scheduledUpdateHour` / `Minute` | 4 / 0 | 定时点（本地时区） |

### 维度扫描 `[dimension_scan]`

| 配置项 | 默认 | 说明 |
|--------|------|------|
| `default_scan_mode` | SURFACE | 未列入列表的维度回退（SURFACE / CAVE） |
| `default_cave_start` | 63 | `CAVE` 回退时的 caveStart Y |
| `dimension_configs` | 三原版预设 | 每维度一条字符串 |

**推荐条目格式**（Fabric 与 Forge/NeoForge 均为列表风格）：

```toml
dimension_configs = [
    "minecraft:overworld = SURFACE",
    "minecraft:the_nether = SURFACE,63",
    "minecraft:the_end = SURFACE"
]
```

| layerPlan | 说明 |
|-----------|------|
| `SURFACE` | 仅地表；有顶盖维度扫逻辑顶以上（如地狱 Y≥128） |
| `ALL` | 全高度洞穴层 |
| `63` / `63,127` | 显式洞穴层（不含地表） |
| `SURFACE,63` 等 | 组合；层号 = `caveStart >> 4` → `caves/<层号>/` |

**兼容**：`dimension|layerPlan`、旧多字段管道、Fabric 旧键 `dimensionConfig.N` / `dimensionConfigs=a;b`。维度类型信息运行时从服务器 API 获取，**不再写入配置**。

---

## 五、地图同步

### 决策（`RegionSyncPolicy`）

1. 哈希一致 → 跳过  
2. 哈希不一致且客户端时间戳 ≥ 服务端 → 跳过（保留本地探索）  
3. 哈希不一致且客户端更旧 → 传输  
4. 客户端无元数据 → 传输  

### 传输能力

| 功能 | 说明 |
|------|------|
| CRC32 + 时间戳 | 双重过滤增量同步 |
| 视距优先 | 视距内先传，其余按距离排序 |
| 分批 / 限速 | 按 `maxSyncPacketSize`、`syncSpeedLimitKBps` |
| Payload 分片 | >28KB 拆 ~1KB 小包，接收端乱序组装 |
| 流式写入 | 边收边写 `mw$worldId/`，每 region 触发重载 |
| 断点续传 | 基于客户端 `sync_timestamps.cache`，无需服务端记进度 |
| 跨地址缓存复用 | 切换服务器地址时通过 `Files.move` 零拷贝重命名复用已下载地图，避免重复下载 |
| 冲突防护 | 同步中拒新请求；10 分钟过期；离线/切维中断；约每 60s 清理离线残留 |
| 握手保护 | 未装 MapSyncer 的客户端不发自定义包 |

### 网络 Payload（6 种）

| Payload | 方向 | 作用 |
|---------|------|------|
| `ServerInstalledPayload` | 服→客 | 版本、最后生成时间、更新模式、TICK 间隔 |
| `SyncRequestPayload` | 客→服 | 区域元数据（路径→时间戳+CRC32），可分片 |
| `SyncResponsePayload` | 服→客 | 区域数据；状态含 ok / uptodate / no_cache / dim_not_available / in_progress / aborted:* |
| `ChunkMapData` | 嵌入响应 | 单 region 压缩图 + `caveLayer` |
| `SyncProgressPayload` | 服→客 | 进度（Action Bar） |
| `ClientMeta` | 嵌入请求 | 秒级时间戳 + CRC32 |

---

## 六、增量更新与客户端自动同步

服务端 `incrementalUpdateMode` 控制**何时重扫 MCA 更新缓存**；客户端在 `autoSyncEnabled=true` 时按同一模式决定是否**自动 sync**。手动 `/mapsyncer sync` 始终可用。

| 模式 | 服务端 | 客户端（autosync 开） |
|------|--------|----------------------|
| **DISABLED** | 不跑增量扫描 | 不自动 sync；未完成同步时可进服断点提示 |
| **TICK** | 每 N tick 增量扫描 | 进服：时间戳 + 冷却 → 延迟 sync；**在线**周期 sync（Action Bar） |
| **SCHEDULED** | 每日定点 1 分钟窗口扫描一次 | 进服仅时间戳比对（无冷却）；在线无周期 timer |

**共用**：`needsResume` 时进服优先续传；`lastGenerationTimestamp<=0` 或客户端已最新则不触发进服自动 sync。

---

## 七、地图生成（MCA → Xaero）

| 功能 | 说明 |
|------|------|
| 全维 / 单维 / 单 region / `--force` | 命令驱动生成 |
| LayerPlan 多层输出 | 单次 MCA 解析多 pass（地表 + 洞穴） |
| 并发转换 | `maxConcurrentRegions` 解析后的固定线程池，MIN_PRIORITY |
| 增量扫描 | 比对 MCA mtime；两遍（变更 + 新增） |
| 世界格式自适应 | MC 26.1+ 与传统格式 |
| MCA 压缩 | GZIP / ZLIB / LZ4 |
| 渲染对齐 | WORLD_SURFACE 优先；地狱逻辑顶；洞穴 underair 状态机；树叶/透明/染色等 |
| 内置服务器 | 复用主机 Xaero 存档目录作缓存，跳过二次转换 |
| Xaero 路径 | 优先 `xaero/world-map`，fallback `XaeroWorldMap` |

流水线：`region/*.mca` → NBT 解析 → RegionConverter → `region.zip`（Xaero 6.8）→ GenerationCache → 网络 / MapPackager。

---

## 八、缓存

| 缓存 | 位置 | 作用 | 上限 |
|------|------|------|------|
| GenerationCache | `server_map_cache/generation_cache.properties` | region 时间戳 + CRC32 | 50000 |
| McaTimestampCache | 内存 | MCA mtime，驱动增量扫描 | 50000 |
| ClientTimestampCache | `sync_timestamps.cache` | 客户端同步时间戳与断点状态 | 有 trim |
| BlockColorMapper / BlockPropertyResolver | 内存 | 取色 / 属性 | 5000 / 10000 |

`CacheConfig` / `TimeoutConfig`：单区域转换超时 60s、同步过期 10min、服务端响应超时 5s 等。

---

## 九、维度映射与存储

| 维度 | Minecraft ID | Xaero 目录 |
|------|--------------|------------|
| 主世界 | `minecraft:overworld` | `null` |
| 地狱 | `minecraft:the_nether` | `DIM-1` |
| 末地 | `minecraft:the_end` | `DIM1` |
| Mod | `namespace:path` | `namespace$path` |

```
服务端: <server>/server_map_cache/
├── null/, DIM-1/, DIM1/, namespace$path/
├── caves/<layer>/
└── generation_cache.properties

客户端: xaero/world-map/Multiplayer_<IP>/  （优先）
       或 XaeroWorldMap/Multiplayer_<IP>/   （fallback）
├── <维>/mw$<worldId>/
├── caves/<layer>/
└── sync_timestamps.cache
```

---

## 十、MCA 解析与方块系统

**MCA**：独立纯 Java 解析；GZIP/ZLIB/LZ4；NBT 全标签 + 大小/深度限制；调色板与生物群系 4×4×4；layerPlan 多 pass；Xaero 洞穴空像素写 air。

**方块**：`BlockPropertyResolver` 桥接平台 API（air/流体/透明/发光等）；`BlockColorMapper`（MapColor + 启发式）；彩色玻璃 overlay、含水检测、Mod 方块识别。

---

## 十一、MapPackager（离线分发）

```bash
./gradlew buildPackager
java -jar mapsyncer-packager.jar -c <缓存> -o <zip> [-s 名] [-w worldId] [-d 世界目录]
```

扫描全部维度（含洞穴层），输出 `Multiplayer_<名>/<维>/mw$<worldId>/`，并转换 `generation_cache.properties` → `sync_timestamps.cache`。纯 Java，无需 MC/Xaero 运行时。

---

## 十二、客户端体验与稳定性

| 功能 | 说明 |
|------|------|
| 进度 / 完成耗时 | Action Bar；进服自动 sync 可有聊天提示 |
| 同步时暂停 Xaero 写入 | 完成后恢复 |
| 选择性重载 | 收到的 region；视距外由 `mapRegionLoadIntervalTicks` 限速排空 |
| 洞穴层 | 按目标维度加载；清理 `.xwmc` / `.outdated` |
| 多线程哈希 | ForkJoinPool，`hashThreads` 可配 |
| 并发与内存 | ConcurrentHashMap、流式 CRC32、缓存上限、overlay 不重复存储 |
| 生命周期 | 停服清理线程池与静态缓存；玩家断开立即中断 sync |

---

## 已知限制

- 部分 Mod 维度多层洞穴 LayerPlan 需自行验证
- v1.0.4 已修复地狱 `SURFACE,63` 地表/洞穴显示问题（见 `CHANGELOG.md`）

**许可证**：GPL-3.0
