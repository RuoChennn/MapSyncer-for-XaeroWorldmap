# 服务端指令矩阵测试结果

日期：2026-10-18

配套执行 skill：[mapsyncer-server-command-test](../../.agents/skills/mapsyncer-server-command-test/SKILL.md)。

## 1.21.1 组合总览

| Loader | 结果 | 主要问题 |
|---|---|---|
| Fabric 1.21.1 | PASS | 服务端输出为英文；定时时间显示 `4:0`；单区域会按配置生成地表+洞穴条目 |
| Forge 1.21.1 | PASS（带缺陷） | `reloadconfig` 后写配置失败；缺失维度目录被记为 ERROR |
| NeoForge 1.21.1 | PASS | 缺失维度目录被记为 ERROR；定时时间显示 `4:0`；RCON 输出格式与 Forge 不同 |

三个 Loader 均已完成启动、命令树、增量配置、单区域/单维度/全量/强制生成、重载和跨重启持久化测试。Forge 和 NeoForge 使用临时 RCON 完成后台命令执行，测试后已恢复 `server.properties`。

> 说明：本轮试跑发生在“所有服务端安装 C2ME、统一走 RCON”规范补充之前。Fabric 和 NeoForge 已带 C2ME；Forge 1.21.1 当前没有可用的 Forge C2ME 构建，因此是 MapSyncer 基线结果。新规范要求 Fabric 也走 RCON，Forge 在有 C2ME 构建时需要补跑 C2ME 基线。

## 1. Fabric 1.21.1 — 通过

- 服务端目录：`E:\Game\服务器端\fabric 1.21.1 server`
- Loader / MC：Fabric Loader 0.19.2 / Minecraft 1.21.1
- Java：Eclipse Adoptium JDK 21.0.11
- C2ME：`0.4.0-alpha.0.23+1.21.1`
- RCON：否（本轮使用 PTY 控制台；新规范要求后续改走 RCON）
- MapSyncer：`mapsyncer-1.0.4-fabric-1.21.jar`
- SHA256：`46D3167C213877E357332D28829488FA613A1B571AC873484C4F6BA6C8F1DE61`
- 世界：已有 `world/`，overworld 88 个 MCA，nether 19 个，end 60 个
- 缓存基线：已移到 `cache/tmp/server-command-matrix/2026-10-18/fabric-1.21.1/backup/server_map_cache`（139 个文件，10.3 MB）
- 测试后缓存：3 个维度，196 个 zip，18.81 MB
- 结论：命令功能矩阵通过。测试后重新执行 `build-fabric-1.21.1.bat` 成功，生成的 `output/mapsyncer-1.0.4-fabric-1.21.jar` SHA256 与部署包完全一致，因此本轮产物可视为当前源码构建。

### 1.1 启动

第一次启动时使用默认 `TEMP` 导致 Netty selector 报
`Unable to establish loopback connection`。把 `TEMP` / `TMP` / `TMPDIR` 指向仓库 `cache/tmp/server-command-matrix/sys-temp` 后，同一服务端正常启动。

- 构建：设置同样的 `TEMP` / `TMP` 后，`build-fabric-1.21.1.bat` 成功，Gradle 9.4.1 构建耗时约 10 秒。
- 第一次正常启动：`Done (0.882s)! For help, type "help"`（16:13:41）
- 第二次正常启动：`Done (0.798s)! For help, type "help"`（16:17:57）
- 两次启动都加载了 `mapsyncer 1.0.4-fabric-1.21`，日志中没有 MapSyncer 异常。
- 测试结束后 25565 端口已释放，服务端进程已退出。

### 1.2 命令结果

控制台命令均不带前导 `/`，Fabric 前缀为 `mapsyncerserver`。

| # | 命令 | 实际关键输出 | 结果 |
|---:|---|---|---|
| A1 | `mapsyncerserver` | `Server Commands Help:`，列出 10 条命令 | PASS |
| A2 | `mapsyncerserver help` | 与 A1 相同 | PASS |
| A3 | `mapsyncerserver status` | `No conversion in progress`；`Current incremental mode: TICK (interval 6000 ticks = 300.0 s)` | PASS |
| A4 | `mapsyncerserver incremental` | 显示 TICK 状态和 `Subcommands: /mapsyncerserver incremental off \| tick [interval] \| scheduled [hour] [min]` | PASS |
| B1 | `mapsyncerserver incremental off` | `Incremental updates disabled` | PASS |
| B2 | `mapsyncerserver status` | `Current incremental mode: DISABLED` | PASS |
| B3 | `mapsyncerserver incremental tick` | `Incremental updates set to TICK mode (interval: 6000 ticks = 300.0 seconds)` | PASS |
| B4 | `mapsyncerserver status` | TICK，6000 ticks，显示下次更新时间 | PASS |
| B5 | `mapsyncerserver incremental tick 2400` | 输出 2400 ticks / 120 秒；同时有 `Incremental update handler already running` WARN | PASS |
| B6 | `mapsyncerserver status` | 2400 ticks，120.0 s | PASS |
| B7 | `mapsyncerserver incremental tick 72000` | 输出 72000 ticks / 3600 秒 | PASS |
| B8 | `mapsyncerserver status` | 72000 ticks，3600.0 s | PASS |
| B9 | `mapsyncerserver incremental tick 2399` | `Integer must not be less than 2400, found 2399` | PASS |
| B10 | `mapsyncerserver incremental tick 72001` | `Integer must not be more than 72000, found 72001` | PASS |
| B11 | `mapsyncerserver incremental scheduled` | `SCHEDULED mode (daily at 4:0)` | PASS（格式异常，见 1.3） |
| B12 | `mapsyncerserver status` | `SCHEDULED (daily at 4:0)` | PASS（格式异常） |
| B13 | `mapsyncerserver incremental scheduled 5` | `SCHEDULED mode (daily at 5:0)` | PASS（格式异常） |
| B14 | `mapsyncerserver incremental scheduled 5 30` | `SCHEDULED mode (daily at 5:30)` | PASS |
| B15 | `mapsyncerserver incremental scheduled 24` | `Integer must not be more than 23, found 24` | PASS |
| B16 | `mapsyncerserver incremental scheduled 5 60` | `Integer must not be more than 59, found 60` | PASS |
| B17 | `mapsyncerserver incremental off` | `Incremental updates disabled` | PASS |
| B18 | `mapsyncerserver reloadconfig` | `Server configuration reloaded successfully` | PASS |
| B19 | `mapsyncerserver incremental` | 重载后仍为 DISABLED | PASS |
| C1 | `mapsyncerserver status` | 无任务，缓存为空 | PASS |
| C2 | `mapsyncerserver generate overworld 0 0` | `Starting generation for region (0, 0)...`；`Region conversion completed`；生成 `null/0_0.zip` 和 `null/caves/3/0_0.zip` | PASS |
| C3 | `mapsyncerserver generate overworld 999999 999999` | `Region (999999, 999999) does not exist...` | PASS |
| C4 | `mapsyncerserver generate overworld` | `Dimension generation completed: 138/138 regions, 116 updated`；日志：69 total，116 converted，19 empty MCA，11 empty content | PASS |
| C5 | `mapsyncerserver status` | `Cache stats: 1 dimensions, 116 regions, 16.36 MB` | PASS |
| C6 | `mapsyncerserver generate` | `Map generation completed: 104/104 regions, 3 dimensions completed: minecraft:overworld, minecraft:the_nether, minecraft:the_end` | PASS |
| C7 | 全量进行中再次执行 `mapsyncerserver generate overworld` | 第一次全量只用约 2 秒，重复命令到达时已完成，未触发互斥；改用 `--force` 进行中重复执行，返回 `A map generation task is already in progress...` | PASS（补充测试） |
| C8/C9 | `mapsyncerserver status` | 3 个维度、196 个缓存条目、18.81 MB | PASS |
| C10 | `mapsyncerserver generate overworld --force` | `Cleared cache directory: server_map_cache\null`；`Forced dimension generation completed: 138/138 regions, 116 updated` | PASS |
| C11 | 检查缓存目录 | `null` 最新文件时间 16:16:48；`DIM-1` 16:16:23；`DIM1` 16:16:24 | PASS |
| C12 | `mapsyncerserver generate overworld` | 强制后再次增量：`Dimension generation completed: 138/138 regions, 0 updated` | PASS |
| C13 | `mapsyncerserver status` | 无任务，3 个维度、196 个条目 | PASS |
| C14 | `mapsyncerserver reloadconfig` | `Server configuration reloaded successfully` | PASS |
| C15 | `mapsyncerserver status` | 重载后仍显示 3 个维度、196 个条目 | PASS |
| D1 | `mapsyncerserver incremental scheduled 5 30` | `Incremental update handler started (SCHEDULED mode, daily at 5:30)` | PASS |
| D2 | `save-all flush` | `Saved the game` | PASS |
| D3 | `stop` | 正常停止，所有维度保存，MapSyncer cleanup 完成 | PASS |
| D4 | 重新启动后执行 `mapsyncerserver incremental` | 仍为 `SCHEDULED (daily at 5:30)`；`status` 显示缓存仍在 | PASS |
| D5 | `mapsyncerserver incremental off` | `Incremental updates disabled` | PASS |
| D6 | `stop` | 正常停止，25565 端口释放 | PASS |

### 1.3 发现的问题

1. **服务端输出语言是英文**：控制台实际输出使用 `en_us`，不是 `zh_cn`。`zh_cn.json` 位于客户端资源中，不能作为服务端控制台断言。测试计划中的预期字符串需要同时接受英文输出，或明确以服务端语言为准。
2. **定时时间格式丢失前导零**：`%02d:%02d` 在服务端输出为 `4:0`、`5:0`，而 `5:30` 正常。显示层对 `%02d` 的处理与预期不符，属于可读性问题，不影响配置值。
3. **TICK 间隔修改时出现 WARN**：在 handler 已运行时修改 `tick 2400` / `tick 72000`，日志出现 `Incremental update handler already running`，但 `status` 已反映新间隔。建议确认 handler 是否会按新间隔重新计时，避免“状态显示新值、实际调度仍旧值”的歧义。
4. **全量生成太快导致互斥用例可能漏触发**：本世界全量只用约 2 秒，第一次 C7 没能在进行中发出。互斥逻辑本身在 `--force` 期间复测通过；测试计划应把互斥用例固定放到 `--force` 或大世界上，而不是依赖全量生成的时序。
5. **单区域会生成地表和洞穴两个缓存条目**：overworld 的 `generate overworld 0 0` 同时产生 `null/0_0.zip` 和 `null/caves/3/0_0.zip`。测试计划中的“缓存文件数 +1”应改为“按配置层数增加”，否则会误判。
6. **TEMP 路径同时影响 Gradle 和 Netty**：默认用户目录含空格时，Gradle 守护进程和 Minecraft Netty selector 都会报 `Unable to establish loopback connection`。把 `TEMP` / `TMP` / `TMPDIR` 指向仓库 `cache/tmp` 后两者都恢复正常；后续矩阵必须沿用这个环境设置。

### 1.4 证据

- 服务端日志：`E:\Game\服务器端\fabric 1.21.1 server\logs\latest.log`
- 归档日志：`cache/tmp/server-command-matrix/2026-10-18/fabric-1.21.1/logs/`
- 配置文件：`cache/tmp/server-command-matrix/2026-10-18/fabric-1.21.1/mapsyncer-server.properties.after`
- 旧包与旧缓存：`cache/tmp/server-command-matrix/2026-10-18/fabric-1.21.1/backup/`
- 测试后缓存：`E:\Game\服务器端\fabric 1.21.1 server\server_map_cache`

## 2. Forge 1.21.1 — 通过（带缺陷）

- 服务端目录：`E:\Game\服务器端\forge 1.21.1 server`
- Loader / MC：Forge 52.0.0 / Minecraft 1.21.1
- Java：Eclipse Adoptium JDK 21.0.11
- C2ME：N/A（当前环境没有可用的 Forge 1.21.1 C2ME 构建）
- RCON：是（25575，测试后已关闭）
- MapSyncer：`mapsyncer-1.0.4-forge-1.21.jar`
- SHA256：`646E7E331DF49120D7B18D6886F4715DB5B72C92C703347D6A5CBC739702316A`
- 世界：首次启动创建 `world/`，overworld 4 个 MCA；nether / end 尚未生成 region 目录
- 缓存：`server_map_cache/null/`，4 个 zip，约 0.01 MB
- 结论：启动、命令树、生成、强制重建、配置持久化均能跑通；发现 2 个需要修复的缺陷（见 2.3）。

### 2.1 启动与测试方式的调整

初次启动暴露了两个 Forge 1.21.1 打包问题，已修复：

1. `META-INF/mods.toml` 的依赖缺少 Forge 要求的 `mandatory=true`，服务端报
   `Missing required field mandatory in dependency`。
2. `MapSyncer` 主类只有 `(IEventBus, ModContainer)` 构造函数，Forge 52 报
   `NoSuchMethodException: com.mapsyncer.MapSyncer.<init>()`；已改为无参构造函数，内部通过
   `FMLJavaModLoadingContext.get().getModEventBus()` 和 `ModLoadingContext.get().getActiveContainer()` 获取。

修复后重新构建，`BUILD SUCCESSFUL`，服务端正常加载 MapSyncer。

Forge 控制台在后台管道中不读取 stdin，因此本轮改用临时 RCON（`127.0.0.1:25575`）发送命令。RCON 的反馈是未翻译的翻译键（如 `mapsyncer.prefixmapsyncer.command.incremental_disabled`），不是控制台英文文本；因此结果以“翻译键 + 配置文件/缓存/日志”交叉验证。测试结束后已把 `server.properties` 恢复为 `enable-rcon=false`。

### 2.2 命令结果

| # | 命令（RCON） | 实际反馈 / 证据 | 结果 |
|---:|---|---|---|
| A1/A2 | `mapsyncer` / `mapsyncer help` | 返回 10 条帮助翻译键 | PASS |
| A3 | `mapsyncer status` | `generate.no_progress` + `incremental_status_disabled` | PASS |
| A4 | `mapsyncer incremental` | `incremental_status_disabled` + hint | PASS |
| B1/B2 | `incremental off` + `status` | `incremental_disabled`；状态为 disabled | PASS |
| B3/B4 | `incremental tick` + `status` | `incremental_tick_set`；状态为 tick | PASS |
| B5/B6 | `incremental tick 2400` + `status` | `incremental_tick_interval`；配置为 2400 | PASS |
| B7/B8 | `incremental tick 72000` + `status` | `incremental_tick_interval`；配置为 72000 | PASS |
| B9 | `incremental tick 2399` | `Integer must not be less than 2400` | PASS |
| B10 | `incremental tick 72001` | `Integer must not be more than 72000` | PASS |
| B11/B12 | `incremental scheduled` + `status` | `incremental_scheduled_set`；状态为 scheduled | PASS |
| B13/B14 | `incremental scheduled 5` / `5 30` | `incremental_scheduled_set`；配置为 5:30 | PASS |
| B15 | `incremental scheduled 24` | `Integer must not be more than 23` | PASS |
| B16 | `incremental scheduled 5 60` | `Integer must not be more than 59` | PASS |
| B17 | `incremental off` | `incremental_disabled` | PASS |
| B18/B19 | `reloadconfig` + `incremental` | `config_reloaded`；仍为 disabled | PASS |
| C1 | `status` | 无任务，缓存为空 | PASS |
| C2 | `generate overworld 0 0` | `generating_region`；日志 `Converted single region (0, 0) with 1 passes`；生成 `null/0_0.zip` | PASS |
| C3 | `generate overworld 999999 999999` | `region_not_found` | PASS |
| C4 | `generate overworld` | 日志 `Dimension overworld completed: 4 total, 4 converted` | PASS |
| C5 | `status` | `status.cache_detail`；缓存 4 个 zip | PASS |
| C6 | `generate` | 日志 `Starting conversion of 4 regions across 3 dimensions`；overworld 0/4（已缓存）；nether/end 目录不存在，记录 ERROR（见 2.3） | PASS（有缺陷） |
| C7 | 生成中重复命令 | 世界仅 4 个 region，全量/强制在 0.1 秒内完成，未能稳定触发；Fabric 1.21.1 已用 `--force` 验证互斥 | 未覆盖 |
| C8/C9 | `status` | 无任务；缓存详情正常 | PASS |
| C10/C11 | `generate overworld --force` + 检查缓存 | 4/4 converted；4 个 zip 时间戳全部更新为 16:28:22 | PASS |
| C12/C13 | `generate overworld` + `status` | 0 converted / 4 skipped unchanged；无任务 | PASS |
| C14/C15 | `reloadconfig` + `status` | `config_reloaded`；缓存仍为 4 个 zip | PASS |
| D1 | `incremental scheduled 5 30` | 重启后首次执行成功；在 `reloadconfig` 之后执行会失败（见 2.3） | PASS（仅重启后） |
| D2/D3 | `save-all flush` / `stop` | 保存成功；服务端正常停止 | PASS |
| D4 | 重启后 `incremental` + `status` | 日志 `Incremental update handler started (SCHEDULED mode, daily at 5:30)`；状态为 scheduled，缓存仍在 | PASS |
| D5/D6 | `incremental off` / `stop` | `incremental_disabled`；正常停止 | PASS |

### 2.3 待修缺陷

1. **`reloadconfig` 后设置定时时间失败**：在同一会话先执行 `reloadconfig`，再执行
   `mapsyncer incremental scheduled 5 30`，RCON 返回
   `An unexpected error occurred trying to execute that command`；重启后同一命令成功。
   说明 `reloadconfig` 之后 Forge 配置写回存在状态问题，需要补日志/异常堆栈并修复。
   尝试过在 `acceptConfig` 后调用 `SERVER_SPEC.afterReload()`，复测仍失败，改动已撤回。
2. **缺失维度目录被记为 ERROR**：全量生成时 `the_nether` / `the_end` 还没有 region 目录，日志出现
   `Could not detect region directory` 和 `Region directory not found for dimension: DIM-1/DIM1`，
   级别为 ERROR。空维度应作为“跳过”处理，至少降到 WARN 或 INFO，避免被误判为转换失败。
3. **RCON 反馈未翻译**：RCON 返回翻译键而不是人类可读文本。若后续用 RCON 自动化，需要客户端解析翻译键，或在服务端把命令反馈显式格式化为字符串。
4. **Forge 控制台不接受后台 stdin**：`start.bat` 的交互控制台在 PTY 管道下不读取命令，导致必须启用 RCON。测试计划应把 Forge/NeoForge 的后台方案单独写成 RCON，而不是假设所有 Loader 都能用同一种 stdin 方式。

### 2.4 证据

- 服务端日志：`E:\Game\服务器端\forge 1.21.1 server\logs\latest.log`
- 归档日志：`cache/tmp/server-command-matrix/2026-10-18/forge-1.21.1/logs/`
- 配置：`cache/tmp/server-command-matrix/2026-10-18/forge-1.21.1/mapsyncer-server.toml.after`
- RCON 前配置：`cache/tmp/server-command-matrix/2026-10-18/forge-1.21.1/server.properties.rcon-before`
- 旧包备份：`cache/tmp/server-command-matrix/2026-10-18/forge-1.21.1/backup/`

顺带验证：Forge 1.21.11 的 `mods.toml` 也补上了 `mandatory=true`，重新构建成功，输出
`mapsyncer-1.0.4-forge-1.21.11.jar`（SHA256 `2DC597AC86432AB6934F3DC5D76AF3EB3F8D8000E22899B324770F3BDB3C10C5`）。该版本尚未启动测试。

## 3. NeoForge 1.21.1 — 通过

- 服务端目录：`E:\Game\服务器端\neoforge 1.21.1 server`
- Loader / MC：NeoForge 21.1.227 / Minecraft 1.21.1
- Java：Oracle JDK 21.0.3（`run.bat` 使用 `Oracle-jdk-21`）
- C2ME：`0.3.0+alpha.0.91+1.21.1`
- RCON：是（25575，测试后已关闭）
- MapSyncer：`mapsyncer-1.0.4-neoforge-1.21.jar`
- SHA256：`CE2C6D26D232B53C173F5DC90CC3CF4B711C47D55B705963977B1CC0A8E6E832`
- 测试世界：临时世界 `mapsyncer-test-20261018`，overworld 4 个 MCA
- 测试缓存：4 个 zip，约 0.02 MB
- 结论：通过。旧 1.0.3 的 C2ME/lz4 模块冲突未再出现，新 1.0.4 的 `com/mapsyncer/net/jpountz/lz4` 重定位有效。

### 3.1 启动与兼容性

- 启动日志确认加载 `MapSyncer 1.0.4-neoforge-1.21`，没有
  `Modules org.lz4.java and mapsyncer export package ...` 这类 `ResolutionException`。
- C2ME 0.3.0-alpha.0.91 同时加载；日志中的 Starlight、Sodium、客户端 `Options` 警告属于 C2ME 在专用服务端的可选模块/客户端 mixin 探测，不是 MapSyncer 异常。
- 使用临时世界和 RCON，避免旧世界 1330 个缓存条目干扰“首次生成”验证。测试结束后已恢复 `server.properties`（`level-name=world`、`enable-rcon=false`），旧缓存已放回，临时世界和临时缓存保存在 `cache/tmp/.../neoforge-1.21.1/`。

### 3.2 命令结果

| # | 命令（RCON） | 实际反馈 / 证据 | 结果 |
|---:|---|---|---|
| A1/A2 | `mapsyncer` / `mapsyncer help` | 控制台英文帮助，10 条命令 | PASS |
| A3 | `mapsyncer status` | `No conversion in progress`；`DISABLED`；空缓存 | PASS |
| A4 | `mapsyncer incremental` | `DISABLED` + 子命令提示 | PASS |
| B1/B2 | `incremental off` + `status` | `Incremental updates disabled` | PASS |
| B3/B4 | `incremental tick` + `status` | 使用当前配置间隔 2400 ticks（不是默认 6000）；状态为 TICK | PASS |
| B5/B6 | `incremental tick 2400` + `status` | 2400 ticks / 120 秒 | PASS |
| B7/B8 | `incremental tick 72000` + `status` | 72000 ticks / 3600 秒 | PASS |
| B9/B10 | `2399` / `72001` | 越界错误，参数校验正确 | PASS |
| B11/B12 | `incremental scheduled` + `status` | `daily at 4:0`（格式问题，见 3.3） | PASS |
| B13/B14 | `incremental scheduled 5` / `5 30` | `5:0` / `5:30` | PASS |
| B15/B16 | `24` / `5 60` | 越界错误，参数校验正确 | PASS |
| B17 | `incremental off` | `Incremental updates disabled` | PASS |
| B18/B19 | `reloadconfig` + `incremental` | 重载成功，仍为 DISABLED | PASS |
| B20 | 重载后 `incremental scheduled 5 30` | `SCHEDULED (daily at 5:30)`；NeoForge 没有 Forge 1.21.1 的写回异常 | PASS |
| C1 | `status` | 无任务，空缓存 | PASS |
| C2 | `generate overworld 0 0` | 日志 `Converted single region (0, 0) with 2 passes`；生成 `null/0_0.zip` + `null/caves/3/0_0.zip` | PASS |
| C3 | `generate overworld 999999 999999` | `Region (999999, 999999) does not exist...` | PASS |
| C4 | `generate overworld` | 日志 `Dimension overworld completed: 4 total, 4 converted`；8 个 pass 任务，2 个 empty content 跳过 | PASS |
| C5 | `status` | `Cache stats: 1 dimensions, 4 regions, 0.02 MB` | PASS |
| C6 | `generate` | 全量启动；overworld 0/4（已缓存）；nether/end 目录缺失时记录 ERROR（见 3.3） | PASS（有缺陷） |
| C7 | 生成中重复命令 | 4-region 世界生成在 0.1 秒内完成，未稳定触发互斥；Fabric 已覆盖 | 未覆盖 |
| C8/C9 | `status` | 无任务；缓存详情正常 | PASS |
| C10/C11 | `generate overworld --force` | 4/4 converted；缓存时间戳更新 | PASS |
| C12/C13 | `generate overworld` + `status` | 0 converted / 2 skipped unchanged / 2 empty content | PASS |
| C14/C15 | `reloadconfig` + `status` | 重载成功；缓存仍为 4 个 zip | PASS |
| D1/D2/D3 | `incremental scheduled 5 30` / `save-all flush` / `stop` | 设置 SCHEDULED 5:30；保存并正常停止 | PASS |
| D4 | 重启后 `incremental` + `status` | 日志 `Incremental update handler started (SCHEDULED mode, daily at 5:30)`；状态为 scheduled，缓存仍在 | PASS |
| D5/D6 | `incremental off` / `stop` | 关闭并正常停止 | PASS |

### 3.3 发现的问题

1. **缺失维度目录被记为 ERROR**：与 Forge 1.21.1 相同，全量生成遇到尚未生成 region 的 nether/end 时记录
   `Region directory not found for dimension: DIM-1/DIM1`，级别为 ERROR。建议改为跳过并降级日志。
2. **定时时间格式丢失前导零**：`4:0`、`5:0`，与 Fabric/Forge 一致；属于显示层问题。
3. **`tick` 无参使用当前配置**：本轮配置里间隔是 2400，`incremental tick` 直接沿用了 2400，而不是默认 6000。文档应明确“无参使用当前值”，不要把默认值写成固定结果。
4. **RCON 输出与 Forge 不一致**：NeoForge RCON 返回翻译后的英文文本，Forge RCON 返回翻译键。自动化测试需要按 Loader 分支解析，或统一改为把反馈格式化为纯字符串。
5. **C2ME 专用服务端噪声**：日志出现客户端 `Options` 的 dist 清理 ERROR 和 Starlight/Sodium 目标缺失警告；C2ME 已自动禁用相关模块，MapSyncer 功能不受影响，但会干扰“日志零 ERROR”的简单判据。

### 3.4 证据

- 服务端日志：`E:\Game\服务器端\neoforge 1.21.1 server\logs\latest.log`
- 归档日志：`cache/tmp/server-command-matrix/2026-10-18/neoforge-1.21.1/logs/`
- 配置快照：`cache/tmp/server-command-matrix/2026-10-18/neoforge-1.21.1/mapsyncer-server.toml.after`
- 临时世界：`cache/tmp/server-command-matrix/2026-10-18/neoforge-1.21.1/test-world/`
- 临时缓存：`cache/tmp/server-command-matrix/2026-10-18/neoforge-1.21.1/test-cache/`
- 旧包备份：`cache/tmp/server-command-matrix/2026-10-18/neoforge-1.21.1/backup/`
