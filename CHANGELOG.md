# MapSyncer 更新日志

## v1.0.4（2026-08-29）— 跨地址缓存复用 + 超时重发兜底

本版本重点修复跨地址地图同步失败、同步超时无响应等问题，新增零拷贝重命名方案。

### Bug 修复

#### 跨地址缓存复用
- **零拷贝重命名方案** — `handleMultiEntryCacheReuse` 从复制整目录改为 `Files.move` 重命名，解决复制 5-6GB 地图耗时过长问题
- **空壳目录自动删除后重命名** — 原代码 `currentIPDir.resolve("null").toFile().exists()` 对空壳目录也返回 true → 跳过复制 → 本地完整地图永远不会被搬过来
- **修复根因 bug** — 空壳目录检测逻辑修正，确保完整地图目录能被正确复用

#### 同步超时与重发机制
- **服务端分包 20s 超时通知重发** — 分包上传超时后服务端主动通知客户端重发
- **客户端 60s 超时自动重发（最多 3 次）** — 客户端同步请求超时后自动重发，最多 3 次
- **`request_partial_timeout` 即时重发** — 分片丢失时即时请求重发，不等待整包超时

#### 自动同步进度显示
- **修复 `handleProgressUpdate` 静默丢弃** — 原代码 `if(AutoSyncManager.isActive()) return` 丢弃自动同步进度包，导致一直显示"等待服务端响应"；已删除该静默判断
- **修复 `incrementalUpdateMode` 默认 DISABLED** — 默认配置下进服不自动同步（`shouldAutoSyncOnJoin` 直接跳过），现改为默认启用

#### 多人游戏列表
- **服务端 jar 兼容性问题** — 修复旧版(v1.0.3)服务端 jar 握手包仅 3 字段与新版 6 字段不兼容导致的全同步失败

### 构建与部署
- 版本号从 1.0.7 调整为 1.0.4，与官方版本号对齐
- 双端通用 jar：`mapsyncer-1.0.4-fabric-26.2.jar`

---

## v1.0.4（2026-06-13 ~ 2026-07-01）

本版本在 v1.0.3 基础上共 **53** 次提交，重点完善多层洞穴地图、自动同步策略、同步稳定性与渲染对齐，并带来一轮服务端/客户端性能优化。

### 新功能

#### 多层洞穴与 LayerPlan 配置
- **LayerPlan 统一层配置** — 维度配置格式改为 `dimension|layerPlan|dim_type_info`，支持 `SURFACE`、`ALL`、显式 Y 及组合（如地狱默认 `SURFACE,63`：基岩顶层地表 + Y=63 洞穴层）
- **单次 MCA 多层输出** — 一次解析可生成地表与多个洞穴层，写入 `caves/<层号>/`（层号 = `caveStart >> 4`）
- **旧格式兼容** — `维度|SURFACE|63|…` / `维度|CAVE|63|…` 仍可读取并自动合并为 layerPlan

#### 自动同步策略扩展
- **TICK 在线周期同步** — 服务端 TICK 模式下，客户端在线期间按生成周期自动 `sync all`（默认 5 分钟），进度显示在 Action Bar
- **SCHEDULED 进服时间戳比对** — 定时模式下进服仅比对 `clientMax < serverLastGen`，无冷却
- **客户端 autoSyncEnabled 开关** — 配置文件 + `/mapsyncer autosync on|off` 命令；关闭后仍可手动 sync，进服提示显示「客户端已禁用」

#### 客户端与工具
- **mapRegionLoadIntervalTicks** — 视距外 region 加载限速（-1=一次排空，0=仅视距内，N=每 N tick 加载 1 个），防止 Xaero MapProcessor OOM
- **MapPackager 增强** — 离线打包工具功能扩展
- **generate 命令忙时反馈** — 已有生成任务进行中时向玩家返回多语言提示

### Bug 修复

#### 地狱 / 洞穴层（重点）
- 修复地狱 `SURFACE,63` 基岩顶层渲染错误（表面扫描边界、逻辑顶 Y 计算）
- 修复洞穴层 chunk 消失（像素级 -1 无效、过期 `.xwmc` 缓存、维度判定错误）
- 修复洞穴层群系缺失/错误（空像素写 biome、cave Y 采样、禁用 surface 回退）
- 洞穴扫描深度对齐 Xaero 单层 **15 格**
- 对齐 Xaero 洞穴 `underair` 与 `shouldEnterGround` 状态机
- 修复维度配置 `caveStart` 修改不生效（解析失败静默回退）

#### 渲染与群系对齐
- 对齐 Xaero 树叶处理：树叶为主像素 + biome 用自身 Y 层级 + 跨 section 回退
- 修复透明可染色方块（树叶）颜色不匹配 — 同时记录 overlay 与像素
- 对齐方块 NBT 与群系写入，修复树叶/the_void 红褐色染色
- 全局关闭 biome voxel 边界平滑（`smoothBoundary=false`）
- 修复 `getBiomeAt` 返回 `the_void` 阻断回退链
- 修复 `MOTION_BLOCKING_NO_LEAVES` 回退分支误读 WORLD_SURFACE 数据
- 补全 BlockColorMapper 纹理取色

#### 同步流程
- 重构同步比对逻辑：跳过空 MCA，保留客户端较新的探索成果
- 修复增量同步哈希比对误报「不完整」
- 修复 `DRAINING_RELOAD` 阶段会话无法结束（拆分 sync 进行中判定）
- 修复同步完成时最远视距外 region 未重载
- 修复断线/超时后 `pendingRegionLoads` 残留导致异常加载
- 完善自动同步与客户端写盘流程，简化同步提示文案

#### 稳定性与并发
- 修复 Fabric 增量更新 tick 事件未注册 — SCHEDULED/TICK 模式失效
- 修复增量更新阻塞 Server 线程触发 Watchdog 崩溃（耗时 I/O 异步化）
- 修复 McaReader 不支持 **LZ4 压缩** MCA（添加 lz4-java）
- 修复 GenerationCache / ConversionOrchestrator 多线程竞态
- 修复 SyncRequest 分片组装非原子、partBuffer 丢失分片永久残留
- 修复 performIncrementalScan 并发占锁（CAS + finally 释放）
- 修复 generate 完成后 executor 线程池泄露
- 修复客户端启动时 ClientConfig 未初始化，`mapsyncer-client.properties` 未生成
- 修复 RegionScanner 维度去重仅用短名导致同名不同命名空间维度被覆盖
- 启动时清理残留 `.zip.temp` 文件

#### 代码审计修复（v1.0.4）
- 哈希扫描失败时不再静默降级为全量同步，聊天栏提示错误并中止
- 服务端 sync 玩家校验超时由 5s 增至 15s（2 次重试），超时向客户端发送 `aborted:timeout`
- 移除 Fabric `IncrementalUpdateHandler` 静态 tick 注册，避免集成服务器重载时重复注册
- MCA chunk 读取失败写入 WARN 日志（`McaRegionLoader`）
- Fabric Cloth Config 补全 `mapRegionLoadIntervalTicks` 与 `autoSyncEnabled` 选项
- 移除 Fabric 1.20.1 `FabricNetworkHandler` 调试 `println`，改用 SLF4J
- `DimensionConfigParser` 增加 `invalidateCache()` 与线程安全缓存；Fabric 配置保存时失效
- Fabric 配置加载兼容 snake_case 键名（`PropertiesHelper`）
- BlockColorMapper / BlockPropertyResolver 缓存 trim 至 75% 而非全量清空

#### 平台与构建
- 修复 Forge 1.21.11 `ClientTickEvent` 无 phase、LZ4 与 MC 内置 `at.yawk.lz4` 冲突
- 修复 Forge 1.20.1 / 1.21.11 LZ4 依赖与 ClientTick 注册
- 修复 `build-all.bat` 中文路径下找不到 `gradlew.bat`
- 统一 1.21.11 / 26.1 命令权限

### 性能优化

#### 服务端转换
- McaRegionLoader **流式逐 chunk 读取**，降低并发转换内存峰值
- 增量扫描复用线程池并行转换 region
- 解析 chunk 时预建 BiomeQuartGrid，fill 阶段 O(1) 查表
- BlockPropertyResolver 改用 ConcurrentHashMap 消除锁竞争
- 合并 MCA 目录扫描，复用 mtime 避免二次遍历
- 缓存 PaletteKey，避免重复 TreeMap 分配
- 写 zip 时用 CheckedOutputStream 单次 pass 计算 CRC32
- submitConversionTasks / submitNewRegionTasks：HashSet 替代 List.contains O(n²)
- 移除冗余 blockNames，共享无属性 BlockState 空 map

#### 客户端同步
- 写盘 CRC、hash 扫描与收包 I/O **移出主线程**
- 视距外 region 限速加载，避免 MapProcessor 队列暴涨

### 重构

- 用 **LayerPlan** 替代 scan_mode + cave_start 双字段配置
- 统一同步状态机与 Loader 生命周期
- 拆分 RegionConverter 流水线，修复 Fabric BlockGetter 占位
- 9 个跨版本一致文件压缩至 `libs/common`
- generate 命令 saveEverything 线程安全加固
- 视距外加载配置项由 `mapRegionLoadsPerTick` 更名为 `mapRegionLoadIntervalTicks`（加载时仍兼容旧键名）

### 文档

- 更新 README / README_EN / features.md — layerPlan、自动同步三模式、客户端配置、洞穴层目录
- 对齐服务端/客户端配置文件注释与实际实现

---

## v1.0.3

### 新功能

- **自动同步机制** — 加入服务器时自动比对服务端地图生成时间，静默完成同步
- **MC 1.21.11 全平台适配** — Forge (FML 3.0)、Fabric (Loom 1.15.4)、NeoForge 三平台编译通过
- **MapPackager 独立打包工具** — 纯 Java CLI，将服务器缓存打包为客户端可用的 Xaero 地图 zip
- **内置服务器支持** — 单人游戏局域网共享，复用主机 Xaero 存档目录作为缓存
- **Payload 双向分片传输** — 所有 >28KB 数据自动拆分为小包，接收端组装，支持乱序到达
- **同步冲突防护** — 同步进行中拒绝新请求，10 分钟超时自动清除残留状态
- **握手保护** — Forge 检查客户端 mod 列表 + NeoForge 双向握手，禁止向未安装模组的客户端发送 payload

### Bug 修复

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

### 性能优化

- 区域转换 CPU 优化 — 5 项热点消除（sectionLookup O(1)、getFlags 位掩码、去 Stream、预计算、调色板索引），约 30-50% CPU 降低
- 转换线程使用 MIN_PRIORITY — 降低对服务端 tick 的 CPU 争用
- DimensionConfigParser 添加解析缓存 + 合并查找循环

### 内存与稳定性

- 修复光照数据双重存储，避免 OOM
- 修复时间戳缓存无限增长，添加上限限制
- NetworkHandler 添加幂等防护，防止 payload 重复注册

### 重构

- 目录命名统一 — `fabric-shared` → `shared`
- 三平台 shared 代码合并，消除 forge-shared/fabric-shared 重复
- Xaero 路径统一 — `xaero/world-map` 优先，`XaeroWorldMap` 兼容 fallback
- 提取公共 TimestampHashEntry record + DimensionConfigParser

### 构建与文档

- 构建脚本重构，覆盖全部 11 个平台（PowerShell/Bash/Bat）
- Fabric 配置文件增加与 NeoForge 一致的双语注释
- 更新 README 和 features.md
