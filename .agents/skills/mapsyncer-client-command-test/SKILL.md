---
name: mapsyncer-client-command-test
description: >-
  Run MapSyncer client command matrix tests across Fabric, Forge and NeoForge
  versions by updating both jars, launching the matching dedicated server, and
  driving the Minecraft client through the real chat command path. Use when
  testing client commands, creative-mode joins, autosync reconnect persistence,
  sync state release, Xaero cache writes, or adding a new client version.
  Project-scoped to MapSyncer-for-XaeroWorldmap.
---

# MapSyncer 客户端命令矩阵测试

本项目专用。客户端固定目录 `E:\Game\pcl\.minecraft\versions`，服务端固定目录
`E:\Game\服务器端`。游戏自身的临时文件、日志和备份放在对应实例/服务端目录，
不要重定向到仓库 `cache/`；测试脚本和结果放
`cache/tmp/client-command-matrix/<date>/`。

## 不可妥协的前置条件

1. **两端 jar 必须是同一次构建**：先构建目标版本，再同时部署客户端和服务端，
   记录两边 SHA256，必须一致。禁止用客户端新包配服务端旧包。
2. **启动顺序固定**：更新两端 → 启动配套服务端 → 等 `Done` → 客户端直连
   `localhost:25565`。不要用单人内置服务端代替联机验证。
3. **一次只允许一个客户端做 UI 测试**。所有组合共用一块屏幕和 25565 端口，
   UI 阶段必须持有全局锁（见 [子代理调度](references/subagent-orchestration.md)）。
4. **命令必须走真实聊天栏**：按 `/` 打开命令输入，`type_text` 后回车，以
   `latest.log` 新增的 `[System] [CHAT]` 行确认命令已被接受。不要直接调用命令类
   或伪造网络包。
5. **判据不能只看聊天栏**：同时核对客户端 `latest.log`、服务端 `latest.log`、
   客户端 Xaero 缓存目录和服务端缓存目录。
6. **游戏目录优先用自身目录**：客户端用实例下的 `tmp/` 和 `logs/codex-client/`，
   服务端用服务端目录下的 `tmp/` 和 `logs/codex-console-*.log`。
7. **进服后必须实际切到创造模式**：默认测试账号为 `CodexTester`，
   离线 UUID 为 `dc94793c-8409-363d-954a-a2295f4554d1`（`launch-client.ps1` 已按此
   传入；专用服务端 `usercache.json`/playerdata 也是这个值）。启动服务端前把该 UUID 写入
   `ops.json`（level 4）并备份原文件，或在服务端临时设置
   `force-gamemode=true` + `gamemode=creative`。后续 A-D 阶段都在创造模式下执行，
   结果中要记录创造模式证据。`force-gamemode` 只替代 `/gamemode` 命令；P0 的服务端
   增量/状态命令仍需要 OP。
8. **自动同步开关必须用“断开→重连”验证生效**：`autosync off` 后重连，聊天应显示
   “已被客户端禁用”，且本次进服不发起自动同步；`autosync on` 后重连，必须出现
   `shouldAutoSyncOnJoin result: true` 和服务端 `Sync request`。只核对配置文件不算通过。
9. **让 off/on 两个方向都可判定**：断开后把客户端
   `xaero/world-map/Multiplayer_localhost/sync_timestamps.cache`（旧布局为
   `XaeroWorldMap/Multiplayer_localhost/`）备份到实例的 `codex-backups/` 后移走，
   让客户端时间戳落后于服务端。此时 off 重连即使条件满足也必须不自动同步；on 重连
   则应触发自动同步。否则地图已是最新时两边都可能没有下载动作，无法判定开关是否生效。
10. **配置路径按 Loader 区分**：Fabric 是
    `config/mapsyncer-client.properties`；Forge/NeoForge 是
    `config/mapsyncer-client.toml` 的 `[client]` 段。

## 快速流程

```text
[ ] 1. 枚举 Loader + MC 组合，确认两端目录和构建目标
[ ] 2. 获取构建/UI 锁，构建目标，部署两端，校验 SHA256
[ ] 3. 确认测试账号 OP 或 force-gamemode，启动配套服务端，确认 Done + C2ME + MapSyncer 版本 + 25565 监听
[ ] 4. 启动对应客户端，quickPlayMultiplayer localhost:25565
[ ] 5. 确认 joined the game 和服务端版本握手
[ ] 6. 进服后切创造模式，确认服务端增量模式与缓存非空
[ ] 7. 执行 P0/A/B/C/D；B 阶段每个开关都断开重连验证
[ ] 8. D 完成后等待 30 秒，按 M 打开 Xaero，截图保存到项目 cache
[ ] 9. 用客户端 CHAT 日志 + 双端日志 + 缓存文件 + 截图写结果
[ ] 10. 立即终止本组客户端和服务端的所有进程，确认 25565 释放，再释放锁
```

## 1. 组合发现

每次先扫描，不要假设只有已有版本：

```powershell
Get-ChildItem -LiteralPath 'E:\Game\pcl\.minecraft\versions' -Directory
Get-ChildItem -LiteralPath 'E:\Game\服务器端' -Directory
```

对每个组合记录 Loader、精确 MC 版本、客户端实例目录、服务端目录、构建目标、
Java 主版本、客户端/服务端 jar 版本。没有客户端实例或服务端目录时标 N/A，
不要用相邻版本冒充。

## 2. 构建与部署

优先用 `scripts/fastbuild/build-target.bat <target>`，Windows 只用 cmd 调用。
构建需要写用户 Gradle 缓存时，在沙箱外运行并显式设置：

```powershell
$env:GRADLE_USER_HOME = 'C:\Users\renyu Hong\.gradle'
$env:TEMP = 'D:\开源项目\MapSyncer-for-XaeroWorldmap\cache\tmp\client-command-matrix\build-temp'
$env:TMP = $env:TEMP
$env:TMPDIR = $env:TEMP
cmd /c scripts\fastbuild\build-target.bat fabric-1.21.1
```

部署时把旧 `mods/mapsyncer-*.jar` 移到对应游戏目录自身的
`codex-backups/mods-<stamp>/`，只保留一个 MapSyncer jar。
两端 SHA256 必须相同；不同则停止测试。

## 3. 启动服务端与客户端

使用本 skill 的脚本：

```powershell
pwsh -File .agents\skills\mapsyncer-client-command-test\scripts\launch-server.ps1 `
  -ServerDir 'E:\Game\服务器端\fabric 1.21.1 server'

pwsh -File .agents\skills\mapsyncer-client-command-test\scripts\launch-client.ps1 `
  -VersionDir 'E:\Game\pcl\.minecraft\versions\1.21.1-Fabric 0.19.2' `
  -QuickPlayMultiplayer 'localhost:25565'
```

脚本会按版本 json 选择 Java 17/21/25、构建完整 classpath、处理 Fabric/Forge/
NeoForge 的 JVM 参数，并把 TEMP/TMP 指向实例自身目录。

服务端启动后必须看到：

- `Starting Minecraft server on *:25565`
- `Done (...)`
- `MapSyncer initialized ... version: <目标版本>`
- `c2me`（该版本有匹配 C2ME 时）

客户端启动后必须看到：

- `Connecting to localhost, 25565`
- `Server has MapSyncer installed, version: <同一版本>`
- Fabric 另有 `Client joined server, checking sync state...`（Forge/NeoForge 没有这条）

### 进服准备（P0）

每次客户端进入服务器后先完成这四步，再开始命令矩阵：

1. 在真实聊天栏执行 `/gamemode creative`，确认客户端 `[System] [CHAT]` 出现创造模式
   更新提示（英文客户端为 `Set own game mode to Creative Mode`）。重连后玩家数据仍应为
   创造模式。
2. 把服务端增量模式设为 TICK 或 SCHEDULED。Fabric 用
   `/mapsyncerserver incremental tick 2400`，Forge/NeoForge 用
   `/mapsyncer incremental tick 2400`。B 阶段的 on 用例优先改成 `scheduled`，避免 TICK
   冷却让 `shouldAutoSyncOnJoin` 返回 false。
3. 用对应的 `status` 确认没有进行中的生成任务，且 `server_map_cache` 非空、
   `generation_cache.properties` 有生成时间。
4. 检查客户端 `autoSyncEnabled` 为 `true`；如果上次测试留下 `false`，先执行
   `/mapsyncer autosync on`，再进入 A 阶段。

测试账号不是 OP 时，先补 `ops.json`；不要在没有权限的情况下把 `/gamemode` 失败记成
MapSyncer 缺陷。

## 4. 命令阶段

前缀：Fabric 客户端命令为 `/mapsyncer`；服务端 Fabric 命令为
`/mapsyncerserver`，Forge/NeoForge 为 `/mapsyncer`。

| 阶段 | 客户端必测 |
|---|---|
| P0 进服准备 | `gamemode creative`；服务端 `incremental tick 2400`（或 `scheduled`）；`status` 确认缓存非空 |
| A 基线 | `/mapsyncer`、`help`、`autosync` |
| B 开关与重连 | `autosync off` → 断开、移走客户端时间戳缓存 → 重连（应显示客户端禁用且不自动同步）→ `autosync on` → 断开 → 重连（应显示服务端策略并触发进服自动同步） |
| C 同步 | `sync`、`sync minecraft:overworld`、`sync all`、`sync minecraft:invalid_dim` |
| D 状态释放 | 完整下载/写入完成后**立即**重复执行同一 sync，必须返回“地图已是最新/无需下载”，不能出现“正在接收或写入地图数据” |
| E Xaero 截图 | D 完成后倒计时 30 秒，按 `M` 打开 Xaero 世界地图，截取游戏窗口并保存到项目 `cache/` |

完整预期、负向用例和结果模板见
[客户端命令矩阵](references/client-matrix.md)。

### 主世界同步后截图（E）

每个组合只做一次，放在 D 的“立即重复 sync”之后，避免打断 D 的状态释放时序：

1. 确认本组最近一次主世界同步已经出现终止行（完成或“地图已是最新”）。
2. 等待完整 30 秒；在结果中记录开始和结束时间。
3. 激活 Minecraft 窗口，按一次 `M` 打开 Xaero 世界地图，等待地图界面稳定
   1-2 秒。
4. 用 Computer Use 截取 Minecraft 游戏窗口，保存到
   `cache/tmp/client-command-matrix/<date>/<loader>-<mc>/xaero-overworld-<stamp>.png`。
   文件必须存在且非空；截图内容应能看到 Xaero 世界地图，而不是聊天栏或普通游戏画面。
5. 再按一次 `M` 关闭地图，继续清理流程。

`M` 被其他模组占用、地图未打开或截图失败时记录 `BLOCKED` 和原因，不把截图问题记成
MapSyncer 同步缺陷。截图路径和文件大小写入本组结果。

## 5. 键盘注入与日志判据（唯一主线）

输入必须使用 Computer Use（`@oai/sky`）。按
[键盘注入与日志判据](references/windows-ui-automation.md) 执行：先列窗口并激活
Minecraft，按 `/` 打开命令输入，`type_text` 后回车，再读 `latest.log` 的新增
`[System] [CHAT]` 行。命令判据不靠截图；断开/重连时允许截图定位按钮，E 阶段按用户
要求固定截图。截图不作为同步是否成功的唯一证据。1-2 秒内没有新增 CHAT 行时，重新
激活窗口并重发，不要在没有日志证据时盲目重复回车。

客户端聊天栏内容会以如下形式写入实例的 `logs/latest.log`：

```text
[HH:mm:ss] [Render thread/INFO]: [System] [CHAT] [MapSyncer]...
```

每条命令发送前记录日志行数，发送后只看新增行。必须等到该命令的终止行
（完成、已是最新、维度不可用、错误、正在接收或写入）出现，才能发送下一条。
只看到命令回显或进度行不足以判定完成。

例外：Computer Use 报“用户按了物理 Escape”或窗口失去控制时，立即停止本组
输入并记录 `BLOCKED`，清场后继续下一组。

## 6. 子代理调度

**严格一次一个组合、一个子代理**：派发一个组合，等它完成测试、写结果、终止本组
所有客户端和服务端进程并释放锁后，再派下一个。不要把多个组合并行派发，即使构建
理论上可以并行。协议见
[子代理调度](references/subagent-orchestration.md)。

某组合被阻塞时记录 `BLOCKED` 和原因，清场后继续下一个，不要卡在同一组合上。

## 7. 以日志为主的测试边界

命令判据不需要截图。Minecraft 客户端会把聊天栏消息（包括客户端命令输出）写入
`latest.log` 的 `[System] [CHAT]` 行，所以判据以日志为主；E 阶段额外保存 Xaero
界面截图：

1. 用键盘注入把命令送进真实聊天栏（仍需要窗口焦点）。
2. 读客户端 `latest.log` 的 `[System] [CHAT]` 确认命令被接受、完成或报错。
3. 结合服务端 `Sync request`/`Map sync complete` 和 Xaero 缓存文件判定结果。
4. 断开/重连时允许截图定位暂停菜单和标题界面按钮；E 阶段按固定步骤保存 Xaero 截图，
   其余情况只有失败、输入丢失或需要确认视觉状态时才把截图作为补充证据。

现有工具没有可直接执行客户端命令的 MCP。客户端测试 harness 只是未来的备选，
不在本 skill 的执行范围内；不要因为“没有截图”而修改发布 jar 或引入测试入口。
方案对比和边界见 [无截图方案](references/headless-options.md)。

## 8. 结果与清理

每个组合写一个结果块，至少包含：

- Loader/MC、Java、客户端实例、服务端目录、两端 jar SHA256。
- 服务端启动耗时、客户端加入耗时、同步区域数（服务端发送/客户端更新）。
- 创造模式证据：`/gamemode creative` 的客户端 CHAT 行；若改用
  `force-gamemode`，注明未走命令。
- 每个 autosync 状态的重连证据：重连前后配置文件值、进服状态文本、
  `shouldAutoSyncOnJoin result`、服务端是否出现 `Sync request`、客户端是否出现
  `Starting TICK periodic auto-sync`、`Client disconnected, all resources cleaned up`。
- E 阶段证据：30 秒倒计时的起止时间、按 `M` 后截图文件绝对路径、文件大小，
  以及截图是否显示 Xaero 世界地图。
- 每条命令的实际聊天栏响应、证据路径、PASS/FAIL/BLOCKED。
- MapSyncer 异常与 Loader/C2ME/网络噪声分开记录。

**每一组测试完成后立即停止本组启动的所有客户端和服务端进程**，包括
`run.bat`/`cmd.exe` 派生的 java 子进程；确认 25565 不再监听、没有残留的
Minecraft 窗口后，才释放 UI/构建/端口锁并开始下一组。不要为了省时间让服务端
跨组合常驻。旧包和旧缓存保留在各自游戏目录的 `codex-backups/`，不要复制进仓库。
测试结束后把客户端 `autoSyncEnabled` 恢复为测试前的值（通常是 `true`），并恢复
为测试临时改过的 `ops.json` / `server.properties`。

## 常见坑

- **TEMP 含空格**：Netty 会报 `Unable to establish loopback connection`；必须把
  TEMP/TMP/TMPDIR 指到实例自身无空格目录。
- **沙箱只读 E:\Game**：启动游戏、部署 jar、写实例目录都需要沙箱外执行。
- **物理 Escape**：会关闭聊天并可能打开游戏菜单，也会中止 Computer Use；输入前
  确认焦点，不要在聊天框关闭后继续向游戏菜单注入。
- **聊天输入丢字**：`type_text` 后以新增 CHAT 行为准；没有回显时重新激活窗口并
  重发，不要盲目连续回车。
- **`autosync on` 不会立刻启动周期任务**：当前实现只在下一次进服时调用
  `startTickPeriodicSync`，必须在同一个客户端会话里真正断开再重连，才验证得到 on 状态。
- **`autosync off` 不会取消已调度的进服任务**：它只停止周期任务；测开关前先等进服
  自动同步结束（或至少 10 秒），否则会把残留任务误判成关闭失败。
- **on 状态没有下载动作**：客户端时间戳已是最新时进服自动同步会跳过。先断开，再备份并
  移走 `xaero/world-map/Multiplayer_localhost/sync_timestamps.cache`（或
  `XaeroWorldMap/Multiplayer_localhost/` 下的同名文件），然后重连。
- **TICK 冷却跨重连保留**：`AutoSyncManager.cancel()` 不清 `lastAutoSyncTimeMs`，
  短时间内重连可能被冷却拦住。on 用例优先用 SCHEDULED，或等过一个完整 TICK 周期。
- **Forge/NeoForge 配置不是 properties**：客户端配置在
  `config/mapsyncer-client.toml` 的 `[client]` 段；只查 `.properties` 会漏判。
- **Forge/NeoForge 翻译不加载**：JAR 必须包含 `pack.mcmeta`，否则客户端日志报
  `Missing metadata in pack mod:mapsyncer`，聊天栏只显示原始翻译键。1.21.1 用
  resource pack format 34，1.21.11 用 75，26.1 用 84。
- **NeoForge classpath 重复**：`launch-client.ps1` 已对库路径去重；不要回退成重复
  classpath，否则 `UnionFileSystem` 会报 duplicate key。
- **单个测试账号的 ops.json**：必须是 JSON 数组 `[{...}]`，不能写成单个对象。
- **离线账号登录超时**：服务端 `enforce-secure-profile=true` 时，离线 `CodexTester`
  可能在 login 阶段超时；测试期间临时设为 `false`，结束后恢复。记录为环境配置，
  不是 MapSyncer 缺陷。
- **重连按钮位置随 GUI Scale 变化**：断开/重连需要看标题界面和暂停菜单时允许截图定位
  按钮，但 PASS/FAIL 仍以新增日志行为准。
- **`M` 键冲突**：Xaero 世界地图默认键位是 `M`；如果被其他模组占用，记录实际键位或
  标 `BLOCKED`，不要用普通游戏画面冒充 Xaero 截图。
- **截图路径**：所有 E 阶段截图必须放在项目 `cache/tmp/client-command-matrix/<date>/`
  下，不要写到桌面、下载目录或游戏实例目录。
- **`sync all` 歧义**：Brigadier 会警告 `sync all` 与维度参数 `all` 歧义；实际
  执行要确认走的是“所有维度”还是 `minecraft:all`。
- **跨维度计数**：客户端 `RegionCoord` 只有 `(x,z,caveLayer)`，不同维度的同坐标
  区域可能被集合去重，出现服务端发送数大于客户端更新数。
- **CLIPBOARD GL ERROR**：`Win32: Failed to open clipboard` 是输入环境噪声，
  与 MapSyncer 无关。
- **Yggdrasil 超时**：离线测试环境常见，不影响 `online-mode=false` 的本地联机。
