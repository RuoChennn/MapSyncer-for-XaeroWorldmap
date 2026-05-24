# MapSyncer 功能实现文档

本文档列出了 MapSyncer for XaeroWorldmap 模组的功能实现状态。

**状态标记**: ✅ 已实现已测试 | 🧪 已实现未测试 | ⚠️ 已知问题 | 📝 规划中

---

## 一、核心功能

MapSyncer 是 Minecraft NeoForge 1.21.X 模组，将服务端已探索区域同步到客户端 Xaero's World Map。

**适用场景**: 玩家首次进入已开放服务器，或服务器已用 Chunky 预生成地图。

---

## 二、命令系统

| 命令 | 状态 | 说明 |
|------|------|------|
| `/mapsyncer sync` | ✅ | 同步当前维度 |
| `/mapsyncer sync <dim>` | ✅ | 同步指定维度（支持 `overworld`/`nether`/`end` 及 Mod 维度 ID） |
| `/mapsyncer sync all` | ✅ | 同步所有维度 |
| `/mapsyncer generate` | ✅ | 生成所有维度缓存（需 OP） |
| `/mapsyncer generate <dim>` | ✅ | 生成指定维度（增量模式） |
| `/mapsyncer generate <dim> <x> <z>` | ✅ | 生成单个区域 |
| `/mapsyncer generate <dim> force` | ✅ | 强制生成（无视缓存） |
| `/mapsyncer status` | ✅ | 查看生成进度 |
| `/mapsyncer incremental off/tick/scheduled` | ✅ | 增量更新控制 |

---

## 三、同步系统

### 传输优化

| 功能 | 状态 | 说明 |
|------|------|------|
| CRC32 哈希比对 | ✅ | 哈希一致跳过同步，流式计算避免内存峰值 |
| 时间戳比对 | ✅ | 客户端旧于服务端才同步 |
| 分批传输 | ✅ | 默认 256KB，可配置 64KB-1MB |
| 速率限制 | ✅ | 默认 1MiB/s，可配置（0=不限） |
| 断点续传 | ✅ | 中断后自动恢复 |
| 流式加载 | ✅ | 边接收边加载，无需等待全部传输 |
| 带宽感知限速 | ✅ | 动态调整避免阻塞游戏网络 |

### 增量比对

| 功能 | 状态 | 说明 |
|------|------|------|
| 双重比对 | ✅ | 哈希+时间戳选择性更新 |
| 时间戳缓存 | ✅ | 持久化，秒级精度，服务器切换自动重初始化 |
| Chunk Update 控制 | ✅ | 同步时暂停 Xaero 区块写入 |
| 选择性重载 | ✅ | 仅重置视距范围内区域 |

---

## 四、地图生成系统

| 功能 | 状态 | 说明 |
|------|------|------|
| 全维度生成 | ✅ | 自动扫描所有已加载维度 |
| Mod 维度支持 | ✅ | 支持 ResourceLocation 格式（暮光森林测试通过） |
| 洞穴模式 | ✅ | CAVE 模式从固定高度向下扫描 |
| 增量更新 | ✅ | TICK 周期模式 + SCHEDULED 定时模式 |
| 强制保存机制 | ✅ | 兼容 C2ME |

**已知渲染问题**: 地狱区块竖线分割（⚠️），洞穴内容异常（⚠️）

---

## 五、配置系统

### mapsyncer-common.toml

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `syncSpeedLimitKBps` | 1024 KB/s | 同步速率限制（1MiB/s，0=不限） |
| `maxSyncPacketSize` | 256KB | 单包大小（64KB-1MB） |
| `enableResumeSync` | true | 断点续传 |
| `maxConcurrentRegions` | 4 | 并发转换数 |

### mapsyncer-server.toml

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `incrementalUpdateMode` | DISABLED | DISABLED/TICK/SCHEDULED |
| `incrementalUpdateIntervalTicks` | 200 | TICK 模式间隔 |
| `scheduledUpdateHour` | 4 | 定时模式时间 |

### 维度扫描配置

```toml
[[dimension_scan.dimension_configs]]
    dimension = "minecraft:the_nether"
    scan_mode = "CAVE"
    cave_start = 120
```

---

## 六、维度路径映射

| 维度 | Minecraft ID | Xaero 目录 |
|------|--------------|------------|
| 主世界 | `minecraft:overworld` | `null` |
| 地狱 | `minecraft:the_nether` | `DIM-1` |
| 末地 | `minecraft:the_end` | `DIM1` |
| Mod 维度 | `namespace:path` | `namespace$path` |

---

## 七、文件存储结构

```
服务端: <server>/server_map_cache/
├── null/, DIM-1/, DIM1/           # 原版维度
├── namespace$path/                 # Mod 维度
└── generation_cache.properties     # 时间戳+哈希缓存

客户端: <client>/xaero/world-map/Multiplayer_<serverIP>/
├── null/mw$<worldId>/             # 主世界
├── DIM-1/mw$<worldId>/            # 地狱
└── DIM1/mw$<worldId>/             # 末地
```

---

## 八、MCA 解析系统

| 功能 | 状态 | 说明 |
|------|------|------|
| 独立解析器 | ✅ | 纯 Java，无 Minecraft API 依赖 |
| GZIP/ZLIB | ✅ | 支持压缩类型 1、2 |
| NBT 解析 | ✅ | 全标签类型（0-12），嵌套结构 |
| 方块状态解析 | ✅ | 调色板、属性、位数组 |
| 生物群系解析 | ✅ | 4x4x4 voxel 格式 |
| 表面扫描 | ✅ | SURFACE/CAVE 模式 |
| Xaero 格式输出 | ✅ | 版本 6.8，TileChunk/Tile 结构 |

---

## 九、方块系统

| 功能 | 状态 | 说明 |
|------|------|------|
| 属性查询 | ✅ | isAir/isWater/isTransparent + 植物检测 |
| 含水检测 | ✅ | waterlogged 属性 + 名称匹配 |
| Mod 方块识别 | ✅ | 注册表 API、RenderShape、BlockTags |
| 颜色映射 | ✅ | MapColor API + 纹理提取 + 启发式规则 |

---

## 十、安全与稳定性

| 功能 | 状态 | 说明 |
|------|------|------|
| 并发保护 | ✅ | volatile、ConcurrentHashMap、锁 |
| 内存管理 | ✅ | 流式 CRC32 + 稀疏 overlay 存储 |
| NBT 大小限制 | ✅ | array/list/depth 限制防恶意数据 |
| 错误处理 | ✅ | 单区块失败不中断 |
| C2ME 兼容 | ✅ | 主线程调度保存 |

---

## 十一、历史更新

### v1.0.1 (2026-05-24)

- **内存优化**: HashUtils 流式计算、MapRegionData 稀疏存储
- **安全增强**: NbtReader 大小限制
- **代码清理**: 删除 40+ 未使用方法、废弃类
- **新功能**: 流式加载、带宽感知限速、动态批次大小

### v4.5 (2026-05-21)

- **代码重构**: 合并 HashUtils、PropertiesCacheIO、ChatUtils 工具类

### v4.4 (2026-05-21)

- **增量更新**: 指令执行后自动保存配置

### v4.1 (2026-05-20)

- **客户端修复**: 时间戳缓存路径追踪、目录逻辑简化
- **服务端兼容**: 旧版维度名转换、哈希回退计算

### v3.8 (2026-05-20)

- **末地修复**: 区块存在性检测，虚空区域正确渲染

### v3.5-v3.7 (2026-05-20)

- **Mod 维度**: 命令解析修复，维度建议完善

---

**文档版本**: 5.0
**最后更新**: 2026-05-24
**模组版本**: v1.0.1