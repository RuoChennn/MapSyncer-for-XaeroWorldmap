# MapSyncer v1.0.3 更新日志

## 新功能

- **自动同步机制** — 加入服务器时自动比对服务端地图生成时间，静默完成同步
- **MC 1.21.11 全平台适配** — Forge (FML 3.0)、Fabric (Loom 1.15.4)、NeoForge 三平台编译通过
- **MapPackager 独立打包工具** — 纯 Java CLI，将服务器缓存打包为客户端可用的 Xaero 地图 zip
- **内置服务器支持** — 单人游戏局域网共享，复用主机 Xaero 存档目录作为缓存
- **Payload 双向分片传输** — 所有 >28KB 数据自动拆分为小包，接收端组装，支持乱序到达
- **同步冲突防护** — 同步进行中拒绝新请求，10 分钟超时自动清除残留状态
- **握手保护** — Forge 检查客户端 mod 列表 + NeoForge 双向握手，禁止向未安装模组的客户端发送 payload

## Bug 修复

- 修复异色像素渲染 — `hasVanillaColor` 未依赖 `hasMapColor`，沼泽/针叶林出现 #D9AF91 异色
- 修复树冠表面计算 — 高度图优先级切换为 WORLD_SURFACE 优先，对齐 Xaero 行为
- 修复树叶被跳过 — 占位 BlockGetter 缺少方法导致 buggedBlocks 误判
- 修复雪片渲染 — `checkTransparency` 排除 SnowLayerBlock
- 修复彩色玻璃 — 应作为 overlay 处理而非视为隐形
- 修复进度计数偏差 — 分片展开后 processed 按 region 数而非分片数累计
- 修复增量更新不持久化 — `saveConfig()` 空方法导致配置无法保存
- 修复乱码中文注释 + 删除 4 个废弃类
- 修复 NBT MAX_LIST_SIZE — 5000 → 100000，防止大区域解析失败
- 修复单机目录命名 — 对齐 Xaero，使用存档文件夹名
- 修复 sync_timestamps.cache 超过 32KB 时同步请求 bug
- 修复自动同步消息双前缀

## 性能优化

- 区域转换 CPU 优化 — 5 项热点消除（sectionLookup O(1)、getFlags 位掩码、去 Stream、预计算、调色板索引），约 30-50% CPU 降低
- 转换线程使用 MIN_PRIORITY — 降低对服务端 tick 的 CPU 争用
- DimensionConfigParser 添加解析缓存 + 合并查找循环

## 内存与稳定性

- 修复光照数据双重存储，避免 OOM
- 修复时间戳缓存无限增长，添加上限限制
- NetworkHandler 添加幂等防护，防止 payload 重复注册

## 重构

- 目录命名统一 — `fabric-shared` → `shared`
- 三平台 shared 代码合并，消除 forge-shared/fabric-shared 重复
- Xaero 路径统一 — `xaero/world-map` 优先，`XaeroWorldMap` 兼容 fallback
- 提取公共 TimestampHashEntry record + DimensionConfigParser

## 构建与文档

- 构建脚本重构，覆盖全部 11 个平台（PowerShell/Bash/Bat）
- Fabric 配置文件增加与 NeoForge 一致的双语注释
- 更新 README 和 features.md
