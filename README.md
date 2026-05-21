# MapSyncer-for-XaeroWorldmap

一个完全由AI编写的 Minecraft NeoForge 1.21.X 模组，用于将服务端已探索的区域地图数据同步到客户端的 Xaero's World Map。

> **适用场景**：玩家初次进入已开放很久的服务器，或服务器已使用 Chunky 预生成地图，需要将地图同步给玩家，减少重复跑图的时间成本，对老玩家则是支持地图的增量更新，持续获取最新的服务器地图。

## 用前提示
这是一个完全由AI构建的项目，很多东西只能人工测试，甚至中间还藏着一些俺寻思之力，遇到什么问题都是正常的，出现问题请提issue。

## 环境要求

| 环境 | 要求 |
|-----|-----|
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

`config/mapsyncer-common.json`：
- `syncSpeedLimitKBps` - 同步限速（0=不限）
- `enableResumeSync` - 断点续传
- `maxSyncPacketSize` - 最大包大小（默认1MB）

## 已知问题

- 含水方块渲染异常（海带、海草）
- 水体色彩微小差异
- Mod 方块颜色为近似值

## 文档导航

| 文档 | 内容 |
|-----|-----|
| [implemented-features.md](implemented-features.md) | **完整功能特性列表** |
| [项目结构](src/main/java/com/mapsyncer/) | 源代码目录 |

---

**许可证**：GPL-3.0 | **致谢**：Xaero's World Map & Minimap