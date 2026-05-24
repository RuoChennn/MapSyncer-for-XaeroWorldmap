# MapSyncer-for-XaeroWorldmap

一个完全由AI编写的 Minecraft NeoForge 1.21.X 模组，用于将服务端已探索的区域地图数据同步到客户端的 Xaero's World Map。

> **适用场景**：玩家首次进入已开放很久的服务器，或服务器已使用 Chunky 预生成地图，需要将地图同步给玩家，减少重复跑图的时间成本。支持增量更新，持续获取最新的服务器地图。

## 功能特性

| 特性 | 说明 |
|------|------|
| **流式加载** | 边接收边加载，无需等待全部数据传输完成 |
| **带宽感知限速** | 动态调整发送速率，避免阻塞游戏网络 |
| **断点续传** | 同步中断后自动恢复，无需重新开始 |
| **增量更新** | 仅同步有变化的区域，节省带宽和时间 |
| **维度支持** | 主世界、地狱、末地及 Mod 维度 |

## 环境要求

| 环境 | 要求 |
|------|------|
| Minecraft | 1.21.X (1.21, 1.21.1) |
| NeoForge | 21.0+ |
| Java | 21 |
| 客户端额外 | Xaero's World Map 1.40.11+ |
| 服务端 | 无需安装 Xaero，可独立运行 |

## 快速开始

1. **服务端预生成**：执行 `/mapsyncer generate <dim>`
2. **客户端同步**：加入服务器后执行 `/mapsyncer sync <dim>`

## 命令速查

**客户端** `/mapsyncer sync [维度]`：
- `sync` - 当前维度
- `sync overworld` - 主世界
- `sync all` - 所有维度

**服务端** `/mapsyncer`（需 OP）：
- `generate` - 生成所有维度
- `generate <维度>` - 生成指定维度
- `status` - 查看生成任务和增量更新状态

## 配置

`config/mapsyncer-server.toml`：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `maxSyncPacketSize` | 256KB | 单包最大大小（64KB-1MB） |
| `syncSpeedLimitKBps` | 1024 KB/s (1MiB/s) | 同步速度限制（0=不限） |
| `maxConcurrentRegions` | 4 | 并发转换区域数 |
| `incrementalUpdateMode` | DISABLED | 增量更新模式 |

## 已知问题

- 含水方块渲染异常（海带、海草）
- 水体色彩微小差异
- Mod 方块颜色为近似值

## 文档导航

| 文档 | 内容 |
|------|------|
| [implemented-features.md](implemented-features.md) | **完整功能特性列表** |
| [src/main/java/com/mapsyncer/](src/main/java/com/mapsyncer/) | 源代码目录 |

---

**许可证**：GPL-3.0 | **致谢**：Xaero's World Map & Minimap