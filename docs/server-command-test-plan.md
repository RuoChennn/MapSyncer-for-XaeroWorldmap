# 服务端指令矩阵测试计划

日期：2026-10-18

目标：在 `E:\Game\服务器端` 的固定测试环境中，按版本矩阵逐个启动服务端，全部通过 RCON 执行 MapSyncer 服务端指令，记录每条指令的 RCON 输出、状态变化和缓存副作用，完成一个版本后立即落盘结果，再启动下一个版本。

配套的项目内 skill：[mapsyncer-server-command-test](../.agents/skills/mapsyncer-server-command-test/SKILL.md)。计划保留完整背景；skill 是可复用的执行入口。

## 0. 先看结论

当前环境不能直接开始“逐个服务器跑命令”，否则测到的是不同版本的混合结果。开始测试前必须先处理以下阻塞项：

1. **包版本不一致**：12 个服务端中，6 个是 1.0.4，但多数仍是 7/21 或更早的构建；另有 5 个 1.0.3 和 1 个 1.0.1。必须以同一批 `output/` 正式包替换后再测。
2. **所有服务端都要安装 C2ME**：按 Loader + MC 版本放入匹配的 C2ME 构建，启动日志必须出现 `c2me`。C2ME 与 MapSyncer 的 lz4 包必须兼容；旧 1.0.3 未重定位 lz4 时，NeoForge 1.21.1 会出现
   `Modules org.lz4.java and mapsyncer export package net.jpountz.lz4 to module c2me_fixes_chunkio_threading_issues`。
   当前 1.0.4 输出包已把 lz4 放到 `com/mapsyncer/net/jpountz/lz4`，替换后必须复测。若没有对应 Loader 的 C2ME 构建，结果必须标为 `C2ME N/A`，不能把不兼容的 C2ME jar 强行放入其他 Loader。
3. **所有服务端都要启用 RCON**：启动前备份 `server.properties`，设置 `enable-rcon=true`、`rcon.port`、随机 `rcon.password`，测试结束后恢复原文件。所有服务端命令通过 RCON 执行，不再依赖交互控制台。
4. **4 个服务端没有世界**：Fabric 26.2、Forge 1.21.1、Forge 1.21.11、NeoForge 26.2 没有 `world/`。首次启动会新建世界，单区域测试前必须确认 `r.0.0.mca` 已生成。
5. **RCON 输入不要带 `/`**：RCON 发送 `mapsyncer generate` / `mapsyncerserver generate`。帮助文本里的 `/mapsyncer ...` 是聊天栏写法，RCON 带斜杠会被当成未知命令。
6. **26.3 没有服务端**：`output/` 有 26.3 构建，但 `E:\Game\服务器端` 没有 26.3 服务端目录；Forge 也没有 26.x 模块。矩阵中必须标为 N/A，不能算通过。
7. **大世界全量生成耗时不控**：1.21.11 / NeoForge 1.21.1 / NeoForge 26.1 的主世界已有 1376 个 region，逐版本跑全量会非常久。建议先建立统一的小世界基线，或用代表版本做全量、其余版本只做小世界全量。

## 1. 测试范围

本轮覆盖：

- 服务端命令树、命令前缀、帮助输出。
- `generate` 全量 / 单维度 / 单区域 / `--force` 的启动、完成、失败和互斥行为。
- `status` 的任务进度、增量模式和缓存统计。
- `incremental off / tick / scheduled` 的模式切换、参数边界和配置持久化。
- `reloadconfig` 的重载结果，以及重载后的配置与缓存状态。
- 各 Loader / MC 版本之间的输出差异。

本轮不覆盖：

- 客户端同步、Xaero 地图渲染、网络传输和断点续传。
- 非 RCON 用户的权限行为；RCON 天然是最高权限，不能证明普通 OP 的权限等级。
- 26.3 和 Forge 26.x；当前没有可启动服务端。

## 2. 版本矩阵与执行顺序

按“MC 版本组 → Fabric → Forge → NeoForge”的顺序执行。每完成一行，记录结果、正常停止服务端，恢复 RCON 配置，并确认 25565 / RCON 端口释放后再启动下一行。

| 顺序 | 服务端目录 | Loader | MC | Java | 当前 MapSyncer | 世界概况 | 审计结论 |
|---:|---|---|---|---|---|---|---|
| 1 | `fabric 1.20.1 server` | Fabric | 1.20.1 | 17 | 1.0.4，7/21，559925 B | 88 / 19 / 60 region | 替换为当前构建 |
| 2 | `forge 1.20.1 server` | Forge | 1.20.1 | 17 | 1.0.4，9/18，551791 B | 60 / 16 / 60，含暮色森林缓存 | 替换为当前构建 |
| 3 | `fabric 1.21.1 server` | Fabric | 1.21.1 | 21 | 1.0.3，6/28，321878 B | 88 / 16 / 60 region | 替换 |
| 4 | `forge 1.21.1 server` | Forge | 1.21.1 | 21 | 1.0.4，7/21，544250 B | 无 `world/` | 替换并首次生成世界 |
| 5 | `neoforge 1.21.1 server` | NeoForge | 1.21.1 | 21 | 1.0.3，6/28，1003064 B | 1376 region | 替换；复测 C2ME/lz4 冲突 |
| 6 | `fabric 1.21.11 server` | Fabric | 1.21.11 | 21 | 1.0.3，7/1，403903 B | 1376 / 164 region | 替换 |
| 7 | `forge 1.21.11 server` | Forge | 1.21.11 | 21 | 1.0.4，7/21，543947 B | 无 `world/` | 替换并首次生成世界 |
| 8 | `neoforge 1.21.11 server` | NeoForge | 1.21.11 | 21 | 1.0.3，6/13，314014 B | 1376 / 164 region | 替换 |
| 9 | `fabric 26.1 server` | Fabric | 26.1.2 | 25 | 1.0.1，5/31，302808 B | 60 region，新世界布局 | 替换；验证新布局识别 |
| 10 | `neoforge 26.1 server` | NeoForge | 26.1.2 | 25 | 1.0.3，6/13，314725 B | 1376 region，新世界布局 | 替换；验证新布局识别 |
| 11 | `fabric 26.2 server` | Fabric | 26.2 | 25 | 1.0.4，7/21，552766 B | 无 `world/` | 替换并首次生成世界 |
| 12 | `neoforge 26.2 server` | NeoForge | 26.2 | 25 | 1.0.4，7/21，546720 B | 无 `world/` | 替换并首次生成世界 |
| 13 | `fabric 26.3 server` | Fabric | 26.3 | — | 仅 `output/` 有包 | 无服务端 | N/A |
| 14 | `neoforge 26.3 server` | NeoForge | 26.3 | — | 仅 `output/` 有包 | 无服务端 | N/A |
| 15 | Forge 26.x | Forge | 26.x | — | 无模块 / 无服务端 | — | N/A |

26.1 的世界目录是 `world/dimensions/minecraft/overworld/region/`，不是旧版的 `world/region/`。检查 region 数量时必须先探测实际路径，不能只统计 `world/region`。

## 3. 测试前置

### 3.1 构建与部署

1. 在仓库根目录执行完整构建：

   ```bat
   cmd /c scripts\fastbuild\build-all.bat
   cmd /c scripts\fastbuild\copy-release-jars.bat
   ```

   若只补某个目标，使用 `build-target.bat <target>`，但仍需保证该目标产物进入 `output/`。

2. 只部署正式包，不部署 `-dev-all`、`-slim`、`-sources`、`-javadoc`。

3. 对每个服务端：
   - 记录原 `mods/mapsyncer-*.jar` 的名称、大小、SHA256。
   - 将旧包移到 `cache/tmp/server-command-matrix/<run>/backup/<server>/`，不要直接删除。
   - 放入本版本对应的新包。
   - 确认 `mods/` 中只有一个 `mapsyncer-*.jar`。

部署映射：

| 服务端 | 应部署的 `output/` 包 |
|---|---|
| `fabric 1.20.1 server` | `mapsyncer-1.0.4-fabric-1.20.jar` |
| `forge 1.20.1 server` | `mapsyncer-1.0.4-forge-1.20.jar` |
| `fabric 1.21.1 server` | `mapsyncer-1.0.4-fabric-1.21.jar` |
| `forge 1.21.1 server` | `mapsyncer-1.0.4-forge-1.21.jar` |
| `neoforge 1.21.1 server` | `mapsyncer-1.0.4-neoforge-1.21.jar` |
| `fabric 1.21.11 server` | `mapsyncer-1.0.4-fabric-1.21.11.jar` |
| `forge 1.21.11 server` | `mapsyncer-1.0.4-forge-1.21.11.jar` |
| `neoforge 1.21.11 server` | `mapsyncer-1.0.4-neoforge-1.21.11.jar` |
| `fabric 26.1 server` | `mapsyncer-1.0.4-fabric-26.1.jar` |
| `neoforge 26.1 server` | `mapsyncer-1.0.4-neoforge-26.1.jar` |
| `fabric 26.2 server` | `mapsyncer-1.0.4-fabric-26.2.jar` |
| `neoforge 26.2 server` | `mapsyncer-1.0.4-neoforge-26.2.jar` |

4. 记录每个正式包的 SHA256，写入该版本的结果块。测试期间不要再次替换包，否则结果不可比较。

C2ME 的安装、版本匹配和兼容性校验见 3.4；C2ME 必须与 MapSyncer 一起在启动日志中确认。

### 3.2 世界与缓存准备

- 在 Windows 上先把 `TEMP`、`TMP`、`TMPDIR` 指向仓库内无空格的临时目录，例如
  `cache/tmp/server-command-matrix/sys-temp`。JVM 的 `UnixDomainSockets` 使用 `TEMP`，
  如果用户目录含空格，Minecraft 启动时会在 Netty selector 处报
  `Unable to establish loopback connection`，这不是模组问题。

- 对已有世界的服务端，确认 `overworld 0 0` 对应的 `r.0.0.mca` 存在。若不存在，先通过 RCON 执行：

  ```text
  forceload add 0 0
  save-all flush
  ```

  然后确认 region 文件已生成。旧布局检查 `world/region/r.0.0.mca`；26.x 新布局检查 `world/dimensions/minecraft/overworld/region/r.0.0.mca`。

- 对没有 `world/` 的 4 个服务端，第一次启动会创建世界。启动后先执行 `forceload add 0 0`、`save-all flush`，再开始命令测试。

- 测试“首次生成”前，建议把 `server_map_cache/` 整体移到
  `cache/tmp/server-command-matrix/<run>/cache-before/<server>/`，让本轮从空缓存开始。
  如果不移动，必须先记录缓存文件数、维度和大小，并在结果中注明“非空缓存基线”。

- 检查磁盘空间。当前 E: 剩余约 344 GB，足够；仍应在每个大世界全量生成前确认剩余空间不少于 10 GB。

- 检查 25565 端口和 Java 进程。上一个服务端未完全退出时不要启动下一个。

### 3.3 建议的小世界基线

现有大世界的全量生成会让矩阵测试非常长。推荐为 12 个服务端准备同规格的小世界，例如 5×5 region：

- 使用服务端已有的 Chunky 生成固定半径，记录种子和生成范围。
- 或每个版本用相同种子首次启动后，用 `forceload` 生成固定范围，再 `save-all flush`。
- 小世界用于完整的命令矩阵；现有大世界只做代表版本的额外回归和性能记录。

跨 MC 版本直接复制同一个世界会触发数据升级，不建议作为严格基线。若要比较版本差异，应每个版本用自己的服务端生成同种子、同范围的小世界。

### 3.4 C2ME 与 RCON 基线（强制）

#### C2ME

- 对矩阵中的每一行，先从目录名、`start.bat`、`server.properties` 或 mod 元数据确认 Loader 和精确 MC 版本，再选择匹配的 C2ME 构建。
- **不要把 Fabric 的 C2ME 放进 Forge/NeoForge，也不要把 NeoForge 的 C2ME 放进 Fabric。** C2ME 的 jar 是 Loader 敏感的。
- 每个服务端只保留一个顶层 C2ME jar；安装前备份已有 C2ME，记录文件名、版本和 SHA256。
- 启动后必须在日志的 mod 列表或 `Found mod file` 中出现 `c2me`。没有加载 C2ME 的版本，不能标记为“带 C2ME 通过”。
- 如果该 Loader/MC 组合没有可用的 C2ME 构建，结果中写 `C2ME N/A（无对应构建）`，继续测试 MapSyncer 基线；不要用不兼容的 jar 强行凑数。
- 如果 MapSyncer 与 C2ME 的 lz4 发生模块/重复类冲突，先检查 MapSyncer jar 是否把 lz4 重定位到 `com/mapsyncer/net/jpountz/lz4`。修复 MapSyncer 的 shade/relocation，而不是长期移除 C2ME；临时移除 C2ME 只能用于定位问题，结果必须注明。
- C2ME 在专用服务端可能打印客户端 mixin、Starlight/Sodium 目标缺失等警告或 ERROR；这些是 C2ME 的模块探测噪声，不等于 MapSyncer 失败，但要在结果中单独分类。

当前环境扫描（每次开跑前重新确认，不要把它当成永久事实）：

| Loader | 已有 C2ME 的服务端 | 处理 |
|---|---|---|
| Fabric | 1.20.1 / 1.21.1 / 1.21.11 / 26.1 / 26.2 | 保留匹配版本，记录版本 |
| NeoForge | 1.21.1 / 1.21.11 / 26.1 / 26.2 | 保留匹配版本，记录版本 |
| Forge | 当前 1.20.1 / 1.21.1 / 1.21.11 未发现 C2ME | 有可信 Forge 构建则安装；否则记 `C2ME N/A` |

安装/检查示例：

```powershell
Get-ChildItem -LiteralPath '<server>\mods' -Filter '*c2me*.jar' |
  Select-Object Name,Length,LastWriteTime
```

启动后必须在日志中看到 `c2me` 的加载记录；只把 jar 放进 `mods` 但日志没加载，不能算安装成功。

#### RCON

- 启动前备份 `server.properties` 到 `cache/tmp/server-command-matrix/<run>/<server>/server.properties.before`。
- 测试期间设置：

  ```properties
  enable-rcon=true
  rcon.port=25575
  rcon.password=<随机测试密码>
  broadcast-rcon-to-ops=false
  ```

  串行测试可以复用 25575；若并行或端口被占用，改用其他端口并记录。

- 启动后等待日志出现 `RCON running on ...`，并确认端口处于 LISTENING。RCON 已启动但 MapSyncer 未初始化时不要发命令。
- 使用 skill 自带的 `scripts/RconClient.java`（或等价的 RCON 客户端）发送命令。每条命令可以独立登录，避免长连接在服务端重启时失效。
- 所有 MapSyncer 命令都通过 RCON 发送，**不带前导 `/`**。
- RCON 输出因 Loader 而异：
  - Fabric / NeoForge 通常返回翻译后的文本；
  - Forge 可能返回翻译键（如 `mapsyncer.prefixmapsyncer.command.incremental_disabled`）；
  - `generate` 的完成回调可能因为 RCON 连接关闭而丢失。

  因此，命令是否成功要用“RCON 响应 + `status` 轮询 + `logs/latest.log` + 配置文件/缓存副作用”交叉判断，不能只看 RCON 一行文本。

- 测试结束先执行 `save-all flush` 和 `stop`，等待进程退出，再恢复 `server.properties`，确认 `enable-rcon=false`、RCON 端口不再监听。
- 结果中只记录“RCON 已启用/端口/是否恢复”，不要记录或提交真实密码。

## 4. 单个版本的执行流程

每个版本严格按以下顺序执行，完成后再进入下一行矩阵。

### 4.1 启动

1. 在对应目录运行 `start.bat`。Forge / NeoForge 的 `start.bat` 会先设置正确的 JDK PATH；不要直接双击 `run.bat`，否则可能用到错误的 Java 版本。
2. 等待控制台出现 `Done (...)! For help, type "help"`。
3. 在 `logs/latest.log` 中确认：
   - 加载的 Minecraft / Loader 版本正确。
   - 加载的是当前目录下的新 `mapsyncer-*.jar`。
   - 加载了匹配的 C2ME（日志出现 `c2me`，且没有 `ResolutionException` / 重复类冲突）。
   - 出现 MapSyncer 初始化日志。
   - 出现 `RCON running on ...`，且 `server.properties` 中 `enable-rcon=true`。
   - 没有 `ERROR`、`Exception`、`ResolutionException`、Mixin 失败。
4. 如果日志里的 mods 路径不是当前服务端目录，立即停止。旧 NeoForge 1.21.1 日志曾指向
   `你好新蒸程1.2.0-Server - 副本\mods`，说明日志或工作目录不可信，不能据此判定通过。

服务端控制台/RCON 的翻译通常使用 `en_us`，即使仓库里有 `zh_cn.json`。记录和断言时以实际语言为准；不要因为输出是英文就判失败。

### 4.2 执行命令

所有命令通过 RCON 发送，**不带前导 `/`**。下表中 `<P>` 在 Fabric 为
`mapsyncerserver`，在 Forge / NeoForge 为 `mapsyncer`。

使用 skill 自带的 `scripts/RconClient.java`，每次登录后发送一条命令。Fabric 也会启用 RCON，保证三个 Loader 使用同一种命令通道；不再依赖交互式 stdin。Forge 的 RCON 可能返回翻译键，NeoForge/Fabric 可能返回翻译文本；异步完成回调可能因连接关闭而丢失，必须结合 `status`、配置文件和 `latest.log` 判断。

每输入一条命令，记录：

- 输入的命令。
- RCON 的响应文本（翻译文本或翻译键）。
- 如果是异步生成，记录从开始到 `status` 显示完成的时间。
- 必要的文件系统或配置文件变化。

### 4.3 停止

1. 通过 RCON 执行 `save-all flush`。
2. 通过 RCON 执行 `stop`。
3. 等待进程退出；`start.bat` / `run.bat` 末尾有 `pause` 时，按任意键关闭窗口。
4. 恢复 `server.properties`，确认 `enable-rcon=false`、25565/RCON 端口不再监听，再启动下一台服务器。

## 5. 命令执行顺序

### 5.1 基线

| # | RCON 输入 | 预期输出 / 断言 |
|---:|---|---|
| A1 | `<P>` | 输出“服务端命令帮助:”，并列出 10 条帮助；前缀必须是当前 Loader 的正确前缀 |
| A2 | `<P> help` | 与 A1 相同的帮助内容 |
| A3 | `<P> status` | 初始应显示“当前无转换任务”；显示当前增量模式；有缓存时显示各维度 region 数和大小 |
| A4 | `<P> incremental` | “当前增量更新模式：...”以及可用子命令提示 |

### 5.2 增量配置

| # | RCON 输入 | 预期输出 / 断言 |
|---:|---|---|
| B1 | `<P> incremental off` | “增量更新已禁用” |
| B2 | `<P> status` | 显示 `DISABLED（未启用）` |
| B3 | `<P> incremental tick` | 进入 TICK，使用当前配置间隔；默认 6000 ticks = 300 秒，但若配置已被改过则沿用当前值（本次 NeoForge 1.21.1 实测为 2400） |
| B4 | `<P> status` | 显示 TICK、当前间隔和“下次更新约 …” |
| B5 | `<P> incremental tick 2400` | 最小合法值；输出 2400 ticks = 120 秒 |
| B6 | `<P> status` | 显示 2400 ticks |
| B7 | `<P> incremental tick 72000` | 最大合法值；输出 72000 ticks = 3600 秒 |
| B8 | `<P> status` | 显示 72000 ticks |
| B9 | `<P> incremental tick 2399` | 参数越界，命令失败；不得改变当前配置 |
| B10 | `<P> incremental tick 72001` | 参数越界，命令失败；不得改变当前配置 |
| B11 | `<P> incremental scheduled` | 进入定时模式，使用当前/默认时间；默认每日 04:00，若配置已被改过则沿用当前值 |
| B12 | `<P> status` | 显示 `SCHEDULED（每日 HH:MM）` |
| B13 | `<P> incremental scheduled 5` | 设置小时为 5，分钟沿用当前值；默认得到 05:00 |
| B14 | `<P> incremental scheduled 5 30` | 设置每日 05:30 |
| B15 | `<P> incremental scheduled 24` | 小时越界，命令失败 |
| B16 | `<P> incremental scheduled 5 60` | 分钟越界，命令失败 |
| B17 | `<P> incremental off` | 关闭后台增量更新，为生成测试隔离变量 |
| B18 | `<P> reloadconfig` | “服务端配置已重新加载” |
| B19 | `<P> incremental` | 仍为 DISABLED，证明配置已持久化并被重载 |

补充：B9/B10/B15/B16 是负向用例。若只想执行“每条注册指令”的最小集合，可以跳过负向用例，但不能把跳过的边界校验算作通过。

### 5.3 生成

| # | RCON 输入 | 预期输出 / 断言 |
|---:|---|---|
| C1 | `<P> status` | 记录缓存基线：维度、region 数、大小 |
| C2 | `<P> generate overworld 0 0` | 出现区域生成开始/完成消息；缓存文件数按配置层数增加。overworld 默认可能同时生成 `null/0_0.zip` 和 `null/caves/3/0_0.zip`，不能只按“+1 个文件”判断 |
| C3 | `<P> generate overworld 999999 999999` | 应失败并提示“区域 (999999, 999999) … 不存在”；不得启动后台任务 |
| C4 | `<P> generate overworld` | “开始生成维度地图：…”；完成后出现“维度生成完成：… 本次更新 … 个” |
| C5 | `<P> status` | 任务结束，缓存统计更新 |
| C6 | `<P> generate` | “开始全量地图生成...”；等待进入进行中 |
| C7 | 在 C6 进行中执行 `<P> generate overworld` | 应返回“已有地图生成任务正在进行中，请等待当前任务完成后再执行新任务。” |
| C8 | 等待 C6 完成 | 出现“地图生成完成：… 个区域，已完成 … 个维度：…”；`status` 显示无任务 |
| C9 | `<P> status` | 所有有 region 的配置维度都有缓存统计；空维度应有跳过/空处理记录；记录总 region 数、总大小 |
| C10 | `<P> generate overworld --force` | “开始强制生成维度地图：…（忽略缓存）”；完成后出现“强制维度生成完成：…” |
| C11 | 检查缓存目录 | 该维度旧缓存被清除并重建；文件时间戳更新；`generation_cache` 记录同步更新 |
| C12 | `<P> generate overworld` | 强制后再次增量生成；应只处理需要更新的 region，输出“本次更新 … 个” |
| C13 | `<P> status` | 任务结束；缓存统计正常 |
| C14 | `<P> reloadconfig` | 重载成功；缓存仍在 |
| C15 | `<P> status` | 重载后仍能读取缓存和状态 |

注意：

- C6 的全量生成在现有大世界上可能很久。建议在小世界上执行；若使用大世界，记录 region 数和超时。
- C7 必须等 C6 的 `status` 已显示“转换进行中”后再发，否则可能碰到任务线程尚未置位的竞态，不能证明互斥逻辑。若小世界全量在几秒内完成，C7 可能来不及触发；此时应在 `generate <维度> --force` 进行中重复发一次生成命令，固定做互斥用例。
- 完成回调是在后台工作线程里发出的。若 RCON 没有出现完成消息，但 `status` 已显示无任务，应记为“完成消息可能丢失/线程安全问题”，不能直接判通过。
- `--force` 是破坏性操作，放在全量之后执行，避免让全量重跑。

### 5.4 配置持久化与停止

| # | RCON 输入 | 预期输出 / 断言 |
|---:|---|---|
| D1 | `<P> incremental scheduled 5 30` | 设置一个容易识别的值，输出每日 05:30 |
| D2 | `<P> save-all flush` | 世界保存完成 |
| D3 | `<P> stop` | 服务端正常停止，日志末尾无异常 |
| D4 | 重新启动同一服务端 | 启动成功；`incremental` 仍显示 SCHEDULED 05:30；`status` 无残留任务 |
| D5 | `<P> incremental off` | 关闭定时模式 |
| D6 | `<P> stop` | 正常停止，记录最终日志 |

D1–D6 用于验证配置跨重启持久化。若时间不足，至少在每个 G1/G2/G3/G4 组选一个代表版本执行，其余版本在结果中明确写“未执行重启持久化”。

## 6. 结果记录模板

每个版本完成后，把以下内容写入结果文件，建议放在
`docs/test-results/2026-10-18-server-command-matrix.md`。临时日志、截图和备份放
`cache/tmp/server-command-matrix/2026-10-18/`。

```markdown
### <顺序>. <Loader> <MC 版本> — PASS / FAIL / BLOCKED

- 服务端目录：
- Java：
- C2ME 版本 / 文件（或 N/A）：
- RCON 端口 / 是否已恢复：
- MapSyncer jar：
- jar SHA256：
- 世界 / region 数：
- 缓存基线 / 结束：
- 启动耗时 / 停止耗时：
- 结果：PASS / FAIL / BLOCKED
- 备注：

| # | RCON 命令（无斜杠） | 预期 | 实际关键输出 | 结果 | 证据 |
|---:|---|---|---|---|---|
| A1 | mapsyncer | 帮助 |  |  | latest.log 行号 |
| ... |  |  |  |  |  |
```

证据至少保留：

- `E:\Game\服务器端\<server>\logs\latest.log` 的副本。
- 失败时的 RCON 响应、日志行号或终端截图。
- 配置文件内容或 diff：
  - Fabric：`config/mapsyncer-server.properties`
  - Forge：`world/serverconfig/mapsyncer-server.toml`
  - NeoForge：`config/mapsyncer-server.toml`
- `server_map_cache` 的文件数、维度目录和总大小。

## 7. 通过标准

一个版本判为 PASS，必须同时满足：

1. 服务端正常启动，加载的是本轮指定 jar 和匹配的 C2ME；日志没有 MapSyncer 相关异常，也没有 C2ME 模块冲突。
2. RCON 正常监听，所有必测命令均通过 RCON 执行；测试结束后 `enable-rcon` 已恢复为关闭。
3. 第 5 节所有必测命令均执行，成功命令有成功输出，失败命令有预期失败输出。
4. `generate` 的异步任务能通过 `status` 观察到开始、进行中、完成三个阶段。
5. 单区域生成确实新增/更新了对应缓存；`--force` 确实清空并重建该维度缓存。
6. `incremental` 的配置值写入正确，`reloadconfig` 后保持，重启后仍保持。
7. 停止过程正常，`stop` 后无残留服务端进程占用 25565 / RCON 端口。
8. 没有把“命令被接受”误判为“任务成功”；必须核对完成输出和缓存副作用。

判为 FAIL：

- 该 Loader/MC 有可用 C2ME 构建但没有加载，或 C2ME 与 MapSyncer 发生模块冲突。
- RCON 未启用、无法登录、测试后未恢复 `enable-rcon=false`。
- 命令无输出、输出与预期不符、参数越界仍被接受、任务互斥失效。
- 出现未处理异常、崩溃、死锁、完成消息丢失且无法用 `status` 恢复判断。
- 配置文件未更新、重载后配置丢失、重启后配置回退。
- `--force` 没有清理旧缓存，或清理后没有重建。

判为 BLOCKED：

- 服务端无法启动、jar 无法部署、世界无法准备、端口被占用。
- NeoForge 1.21.1 仍因 lz4/C2ME 冲突无法进入服务器。

## 8. 需要完善的地方

### P0：不处理会让测试结果失真

1. **统一构建与部署**：当前服务端包版本混杂，必须先全量构建并逐服替换；结果中记录 SHA256。
2. **NeoForge 1.21.1 + C2ME**：旧 1.0.3 的 lz4 包与 C2ME 模块冲突。新 1.0.4 已重定位到 `com/mapsyncer/net/jpountz/lz4`，替换后复测；若仍失败，命令基线先移除 C2ME，另开兼容性用例。
3. **没有世界的服务端**：Fabric 26.2、Forge 1.21.1、Forge 1.21.11、NeoForge 26.2 必须先创建世界并生成 `r.0.0.mca`，否则单区域、单维度、全量测试没有意义。
4. **RCON 不要带斜杠**：这是最容易造成“命令不存在”的误判。帮助文案和聊天栏示例保留 `/`，RCON 发送时去掉。
5. **完成回调线程安全**：`generate` 完成消息由后台线程发送。测试中必须确认消息真的到达 RCON；若没有，应作为缺陷记录，而不是只看 `status`。
6. **服务端语言与时间格式**：RCON 输出可能是英文或翻译键；定时时间当前显示为 `4:0`、`5:0`，而不是 `04:00`、`05:00`。测试计划应接受这两种输出，并把前导零问题单列为显示缺陷。
7. **构建环境**：Gradle 9.4.1 和 Minecraft Netty 都受 `TEMP` 路径影响；用户目录含空格时会报 loopback 错误。构建和服务端启动都必须先把 `TEMP` / `TMP` / `TMPDIR` 指向仓库 `cache/tmp` 下的无空格路径，再记录本轮实际使用的 jar SHA256。
8. **C2ME 基线**：每个服务端安装匹配 Loader + MC 的 C2ME；没有对应构建时记录 N/A。安装后必须在启动日志中确认 `c2me`，并检查 lz4/模块冲突。
9. **RCON 基线**：每个服务端测试期间启用 RCON，所有命令通过 RCON 发送；结束恢复 `server.properties`，确认 RCON 关闭。

### P1：提高覆盖率和可重复性

8. **建立小世界基线**：现有 1.21.11 / NeoForge 1.21.1 / NeoForge 26.1 的世界有 1376 个 region，全量生成会拖长矩阵。建议每个版本生成同种子、5×5 region 的小世界；大世界只做代表版本回归。
9. **权限覆盖**：RCON 是最高权限，无法验证 1.20.1/1.21.1 的 `hasPermission(4)` 与 1.21.11+/26.x 的 `LEVEL_GAMEMASTERS` 差异。若要验证，需要另设不同 OP 等级的玩家或权限插件账号，单独执行一次 `generate` / `status`。
10. **文档同步**：README 和 `docs/features.md` 应补充：
   - `<x> <z>` 是 region 坐标，不是方块坐标。
   - `--force` 会清除该维度缓存和 generation_cache 记录。
   - 帮助入口 `help`、`tick` 无参和 `scheduled` 无参的语义。
   - 1.21.11+/26.x 的权限等级与“OP 等级 4”不一致。
   - Fabric 命令示例应同时给出 `/mapsyncerserver`，避免直接照抄 `/mapsyncer`。
11. **26.3 / Forge 26.x 缺口**：构建产物存在不等于有测试环境。结果矩阵要保留 N/A 行，不能把未执行算作通过。
12. **26.x 世界布局**：26.1 的 region 在 `world/dimensions/minecraft/overworld/region`；测试脚本和结果模板要同时支持旧布局和新布局，避免误报“0 region”。
13. **日志编码**：Forge 旧日志里出现 `[189��2026` 一类乱码，建议统一 `chcp 65001` / UTF-8，并在结果中记录日志编码，避免时间字段不可读。
14. **RCON 必须启用**：所有服务端在测试期间都要设置 `enable-rcon=true`，用脚本发送命令并收集响应；测试结束恢复 `enable-rcon=false`。交互控制台只用于观察启动/停止，不作为命令通道。
15. **`reloadconfig` 与生成并发**：当前测试只在空闲时重载。若要在生成中重载，应先明确预期行为；否则可能把未定义行为误判为通过或失败。
16. **Mod 维度覆盖**：只有 Forge 1.20.1 装有暮色森林。`generate twilightforest:twilight_forest` 只在该服执行；其他版本标 N/A，不要用原版维度替代后声称覆盖了 Mod 维度。
17. **缓存统计耗时**：`status` 会扫描缓存目录，大世界上可能有明显延迟。记录 `status` 响应时间，避免把扫描慢误判为卡死。
18. **Forge 1.21.1 打包契约**：`mods.toml` 的依赖必须带 `mandatory=true`；主类必须提供 Forge 52 能调用的无参构造函数。1.21.11 Forge 也需要用同样的方式验证。
19. **`reloadconfig` 后的配置写回**：Forge 1.21.1 复现了 `reloadconfig` 后执行 `incremental scheduled 5 30` 返回 generic error，重启后同一命令成功。需要补充异常日志并修复，测试计划应把“重载后立即写配置”列为独立用例。
20. **缺失维度目录的日志级别**：全量生成遇到未生成的 nether/end 目录时记录 ERROR。空维度应跳过并降级日志，避免把“没有 region”误判为转换失败。
21. **RCON 输出**：RCON 返回翻译键，完成回调在连接关闭后也可能丢失。使用 RCON 时必须用 `status` + `latest.log` 作为完成判据。
22. **NeoForge 1.21.1 兼容性已验证**：1.0.4 把 lz4 放到 `com/mapsyncer/net/jpountz/lz4` 后，NeoForge 21.1.227 + C2ME 0.3.0-alpha.0.91 可以启动，不再出现模块导出冲突。仍会有 C2ME 客户端 mixin 在专用服务端的警告/ERROR，应视为 C2ME 噪声。
23. **RCON 输出因 Loader 而异**：NeoForge RCON 返回翻译后的英文，Forge RCON 返回翻译键。自动化断言不能假设两者一致。

## 9. 每台服务器的执行清单

```text
[ ] 1. 构建完成，output 包时间戳正确
[ ] 2. 旧 mapsyncer jar 已备份，新 jar 已放入，mods 中只有一个 mapsyncer jar
[ ] 3. 记录 jar SHA256
[ ] 4. 安装匹配的 C2ME，记录版本；没有对应构建则记录 C2ME N/A
[ ] 5. 备份 server.properties，设置 enable-rcon=true / rcon.port / 随机密码
[ ] 6. 世界存在；r.0.0.mca 存在；缓存基线已记录或已移到备份
[ ] 7. 25565 和 RCON 端口空闲，上一台服务器已退出
[ ] 8. 启动成功，日志加载路径、C2ME、MapSyncer、RCON 均正确，无异常
[ ] 9. 执行 A1–A4
[ ] 10. 执行 B1–B19
[ ] 11. 执行 C1–C15
[ ] 12. 执行 D1–D6（或注明未执行）
[ ] 13. 保存 latest.log、RCON 响应、配置和缓存统计
[ ] 14. 写入结果块，判定 PASS / FAIL / BLOCKED
[ ] 15. 恢复 server.properties，确认 RCON 关闭，进程退出后进入下一台
```

## 10. 执行时的 RCON 示例

先编译 skill 自带的客户端（JDK 用该服务端对应版本）：

```bat
javac -d cache\tmp\rcon .agents\skills\mapsyncer-server-command-test\scripts\RconClient.java
```

再发送命令（密码从测试配置读取，不要写进结果文件）：

```bat
java -cp cache\tmp\rcon RconClient 127.0.0.1 25575 <password> "mapsyncer status"
```

Fabric 命令：

```text
mapsyncerserver
mapsyncerserver help
mapsyncerserver status
mapsyncerserver generate overworld 0 0
```

Forge / NeoForge 命令：

```text
mapsyncer
mapsyncer help
mapsyncer status
mapsyncer generate overworld 0 0
```

不要在 RCON 中发送：

```text
/mapsyncer generate
```

后者是聊天栏写法，在 RCON 中会被当作带前导斜杠的命令名。

## 11. 2026-10-18 的 1.21.1 试跑结论

- Fabric 1.21.1：通过。
- Forge 1.21.1：通过，但存在 `reloadconfig` 后写配置失败、缺失维度目录记录 ERROR 两个缺陷。
- NeoForge 1.21.1：通过；新包已解决 C2ME/lz4 模块冲突。
- 详细命令输出和证据见 [1.21.1 测试结果](test-results/2026-10-18-server-command-matrix.md)。

该轮发生在 C2ME/RCON 规范补充之前：Fabric 和 NeoForge 已带 C2ME；Forge 1.21.1 当时没有可用的 Forge C2ME 构建，属于 MapSyncer 基线。正式矩阵按本计划 3.4 执行时，Fabric 也必须走 RCON，Forge 在有 C2ME 构建时需要补跑。
