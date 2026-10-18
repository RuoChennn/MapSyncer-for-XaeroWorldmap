# 客户端命令矩阵与判据

## P0 进服准备（每组合一次）

前置：服务端 `server_map_cache` 非空，增量模式为 TICK 或 SCHEDULED；客户端测试账号
为 OP（可执行 `/gamemode` 和服务端命令）。

| 步骤 | 操作 | 预期 |
|---|---|---|
| P0.1 | `/gamemode creative`（或已用 `force-gamemode` 直接以创造模式进服） | 客户端 CHAT 出现创造模式更新提示（`你的游戏模式已更新为创造模式` / `Set own game mode to Creative Mode`）；若用 `force-gamemode`，记录 `server.properties` 和服务端日志/player data；后续 A-D 都在创造模式下执行 |
| P0.2 | Fabric `/mapsyncerserver incremental tick 2400`；Forge/NeoForge `/mapsyncer incremental tick 2400` | 返回 TICK / 2400 ticks |
| P0.3 | 对应 `status` | 无进行中任务；缓存维度/区域数非空且 `generation_cache.properties` 有生成时间（为空时先执行服务端 `generate`，否则 on 用例无法观察） |
| P0.4 | 检查客户端 `autoSyncEnabled` | 为 `true`；若为 `false`，先 `/mapsyncer autosync on`，再进入 A |

测试账号默认 `CodexTester`，离线 UUID 为
`dc94793c-8409-363d-954a-a2295f4554d1`（不是客户端启动参数的占位值；以专用服务端
`usercache.json`/playerdata 为准）。启动服务端前把该 UUID 以 level 4 写入 `ops.json`，
并备份原文件到服务端目录的 `codex-backups/`；也可以启动前临时设置
`force-gamemode=true` + `gamemode=creative`。结果中注明采用哪种方式，权限拒绝不能
记成 MapSyncer 缺陷。`force-gamemode` 只解决创造模式；P0.2 的服务端增量命令仍需 OP，
或在启动前直接把服务端配置设为 TICK/SCHEDULED。

`ops.json` 数组中需要包含以下对象（保留文件中已有的其他条目）：

```json
{
  "uuid": "dc94793c-8409-363d-954a-a2295f4554d1",
  "name": "CodexTester",
  "level": 4,
  "bypassesPlayerLimit": false
}
```

如果改的是 `server.properties`，测试结束后同样恢复原文件，并确认
`force-gamemode`/`gamemode` 没有被遗留。

如果启动客户端时改用其他 `-Username`，以服务端 `usercache.json` 中该名字对应的
UUID 替换上面的条目，不能继续套用 `CodexTester` 的 UUID。

## A 基线

| 命令 | 预期 |
|---|---|
| `/mapsyncer` | 显示用法，包含 sync/sync 维度/sync all/autosync |
| `/mapsyncer help` | 同上，命令不报未知参数 |
| `/mapsyncer autosync` | 显示“客户端自动同步：已开启/已关闭” |

## B 自动同步开关与断开重连

前置：B1 开始前确认 P0.4 已把客户端的 `autoSyncEnabled` 置为 `true`，避免把上次测试
留下的 `false` 当成开关失效。

配置路径：

- Fabric：`config/mapsyncer-client.properties`，键 `autoSyncEnabled=false/true`。
- Forge/NeoForge：`config/mapsyncer-client.toml`，`[client]` 段下的
  `autoSyncEnabled = false/true`。

| 步骤 | 操作 | 预期 |
|---|---|---|
| B1 | `/mapsyncer autosync` | `客户端自动同步：已开启` |
| B2 | `/mapsyncer autosync off` | CHAT 已关闭；配置文件写回 `false`；Fabric 日志还会出现 `Saved client config ... autoSyncEnabled=false`，Forge/NeoForge 以 TOML 实际写回为准 |
| B3 | Esc → 断开连接 | 客户端新增 `Client disconnected, all resources cleaned up`；服务端出现 `CodexTester left the game` |
| B4 | 备份到实例的 `codex-backups/` 后移走 `xaero/world-map/Multiplayer_localhost/sync_timestamps.cache`（旧布局为 `XaeroWorldMap/Multiplayer_localhost/`） | 文件不存在，客户端下次进服的最后同步时间为 0；此时如果 autosync 仍开启，下一次进服本应触发同步 |
| B5 | 重连 `localhost:25565`，至少观察 10 秒 | 新增 `Connecting to localhost, 25565` 和 `Server has MapSyncer installed`（Fabric 另有 `Client joined server`）；CHAT 显示 `自动同步：已被客户端禁用`；日志 `shouldAutoSyncOnJoin result: false`，且没有 `Starting TICK periodic auto-sync`（服务端此时为 TICK）；配置文件仍为 `false`；本次进服后服务端没有 `Sync request`（这是 off 生效的关键证据） |
| B6 | `/mapsyncer autosync on` | CHAT 已开启；配置文件写回 `true` |
| B7 | 按 P0.2 的 Loader 前缀执行 `incremental scheduled`（Fabric `/mapsyncerserver incremental scheduled`，Forge/NeoForge `/mapsyncer incremental scheduled`），然后断开连接 | 客户端出现断线清理日志；服务端玩家离开 |
| B8 | 确认 `sync_timestamps.cache` 仍不存在，然后重连 `localhost:25565` | 新增 `Connecting to localhost, 25565` 和 `Server has MapSyncer installed`（Fabric 另有 `Client joined server`）；CHAT 显示服务端策略（`自动同步：每 N 分钟` / `每天`，不是“已被客户端禁用”）；日志 `shouldAutoSyncOnJoin result: true`；配置文件仍为 `true`；出现 `正在自动同步地图...`；服务端出现 `Sync request` 和 `Map sync complete`；客户端出现 `同步完成，已更新 N 个区域` 或 `自动同步完成` |

说明：

- B 阶段开始前先确认没有正在进行的 sync；如果出现断点续传提示，先按提示清理或完成，
  不要把续传提示当成自动同步。
- 进服时如果已经触发自动同步，先等终止行（或至少 10 秒）再执行 B1/B2；
  `autosync off` 只停止周期任务，不会取消已经调度的进服任务。
- B3/B5 和 B7/B8 必须是同一个客户端进程内的真实断开与重连，不能用重启服务端代替。
- 每次重连后确认玩家仍是创造模式；如果变回生存，记录并重新执行 `/gamemode creative`，
  不要用生存模式继续跑 C/D。
- 本用例验证同一客户端进程内的断开重连和配置文件写回；如果需要额外验证客户端重启后
  从磁盘加载，另行重启客户端并执行 `/mapsyncer autosync`，不计入本阶段的必测项。
- B7 优先用 SCHEDULED，是为了避开 TICK 的 `lastAutoSyncTimeMs` 冷却；如果继续用 TICK，
  必须等过一个完整间隔，并在结果里写明等待时间。
- 如果该 Loader 的 `incremental scheduled` 失败（例如 Forge 1.21.1 已知的 reloadconfig
  后写回问题），记录为服务端缺陷，改用 TICK 并等过一个完整间隔。
- B4 移走时间戳缓存后，B5 和 B8 都会触发全量比较，区域数可能较多；必须等到自动同步的
  终止行出现后再继续。
- 进服自动同步有约 5 秒调度延迟；B5 的“无 `Sync request`”要观察至少 10 秒，B8 要等到
  完成行，不能只看刚进服的几行日志。
- 每次重连前记录客户端和服务端 `latest.log` 行数，只检查新增的
  `shouldAutoSyncOnJoin` / `Sync request` / `Map sync complete`，不要把断开前的旧同步
  记录算进本次进服。
- `autosync off` 后如果服务端本身是 DISABLED，进服状态也可能显示“已关闭”；只有
  `自动同步：已被客户端禁用` 才能证明本地开关生效。
- 断开/重连的按钮定位见
  [键盘注入与日志判据](windows-ui-automation.md#断开与重连)，PASS/FAIL 仍以日志为准。

## C 同步

前置：服务端 `server_map_cache` 或世界已有地图缓存。空缓存时先执行服务端
`generate` 或让服务端生成，不要用“0 区域”误判客户端失败。

| 命令 | 预期 |
|---|---|
| `sync` | 同步当前维度；聊天显示下载区域数或“地图已是最新” |
| `sync minecraft:overworld` | 服务端日志 `Sync request ... regions to sync`，随后 `Map sync complete ... regions sent, 0 failed` |
| `sync all` | 同步所有已有缓存维度；确认不是把 `all` 当成 `minecraft:all` |
| `sync minecraft:invalid_dim` | 聊天提示维度地图数据不可用，并给出服务端 `generate` 命令；状态不能卡死 |

## D 状态释放（回归重点）

这是已修复缺陷的回归用例：

建议在进入 D 前先执行 `/mapsyncer autosync off`，避免进服自动同步和手动 D 用例互相干扰；
完成 D 后再恢复为测试前的值。

1. 清空客户端 `xaero/world-map/Multiplayer_localhost`（先备份）。
2. 重新加入服务器并执行 `sync minecraft:overworld`，等到“同步完成，已更新 N 个区域”。
3. **立即**再次执行 `sync minecraft:overworld`。
4. PASS 条件：返回“地图已是最新，无需下载”，聊天栏不再出现“正在接收或写入地图数据，请待当前步骤完成后再试”。
5. 再执行一次 `sync all`，完成后立即重复，同样必须 PASS。

已知风险：2026-10-18 全版本测试中，Fabric 1.21.11 和 NeoForge 26.1 在清缓存后
出现重复下载不收敛（857→81→342、1208→636）。如果复现，记录连续请求的区域数和
日志路径，判为 FAIL，不要用“已完成过一次同步”代替“地图已是最新”。

## E 主世界同步后的 Xaero 截图

每个组合执行一次，放在 D 的立即重复 sync 之后；不要插到 D 的两次 sync 之间，否则会
破坏“完成后立即重复”的状态释放判据。

| 步骤 | 操作 | 预期 |
|---|---|---|
| E1 | 确认最近一次主世界同步出现终止行（完成或“地图已是最新”） | 客户端 CHAT 有终止行 |
| E2 | 等待 30 秒，记录开始/结束时间 | 实际等待不少于 30 秒 |
| E3 | 激活 Minecraft，按一次 `M` | Xaero 世界地图打开 |
| E4 | 截取 Minecraft 游戏窗口 | 文件保存到 `cache/tmp/client-command-matrix/<date>/<loader>-<mc>/xaero-overworld-<stamp>.png`，存在且非空，画面包含 Xaero 世界地图 |
| E5 | 再按一次 `M` 关闭地图 | 回到游戏画面，继续清理 |

判据：

- 截图文件大小大于 0，路径必须位于项目 `cache/` 下。
- 截图应能看到 Xaero 的世界地图界面，而不是聊天栏、暂停菜单或普通第一人称画面。
- `M` 键被其他模组占用、地图未加载或截图失败时记录 `BLOCKED` 和实际键位，不判为
  MapSyncer 同步失败。

## 判据

- **主判据是客户端 `latest.log` 的 `[System] [CHAT]` 行**：命令是否被接受、
  完成/错误文本都在这里，不需要截图。
- **创造模式**：客户端 CHAT 必须出现 `/gamemode creative` 的成功提示；若使用
  `force-gamemode`，记录 `server.properties` 的 `gamemode=creative` /
  `force-gamemode=true` 以及服务端启动日志或玩家 data，不能只写“已创造”。
- **B 阶段必须有两轮真实重连**：off 重连看 `shouldAutoSyncOnJoin result: false` 且无
  `Sync request`；on 重连看 `shouldAutoSyncOnJoin result: true` 且有 `Sync request`。
- 客户端 `latest.log`：`Connecting to localhost`、`Server has MapSyncer installed`、
  Fabric 的 `Client joined server`、
  `Client disconnected, all resources cleaned up`、`shouldAutoSyncOnJoin result`、
  `同步完成: 总计 N 个区域已处理`、`Marked sync complete`、写入失败/ERROR。
- 服务端 `latest.log`：`Sync request`、`Map sync complete ... sent, failed`。
- 客户端缓存：`xaero/world-map/Multiplayer_localhost/`（旧布局
  `XaeroWorldMap/Multiplayer_localhost/`）下 `mw$*` 目录、`sync_timestamps.cache`、
  `server_config.txt`。
- 服务端缓存：`server_map_cache/` 下维度目录和 `generation_cache.properties`。
- 截图只在日志缺失、输入丢失或需要确认视觉状态时作为补充证据。
- E 阶段是用户要求的固定截图项，每个组合必须留下截图路径和文件大小。

## 结果模板

```text
### <Loader> <MC> / <客户端实例>

- Java: <major>
- Client jar SHA256: <hash>
- Server jar SHA256: <hash>
- Server: <目录> / Done <耗时> / C2ME <版本或 N/A>
- Client join: <耗时>

| 阶段 | 命令 | 实际响应 | 判据 | 结果 |
|---|---|---|---|---|
| P0 | gamemode creative | ... | 客户端 CHAT log | PASS |
| A | help | ... | 客户端 CHAT log | PASS |
| B | autosync off → 断开、清时间戳、重连 | ... | 配置 + CHAT + `shouldAutoSyncOnJoin=false` + 无 `Sync request` / `Starting TICK periodic auto-sync`（条件本应触发） | PASS |
| B | autosync on → 断开重连 | ... | 配置 + CHAT + `shouldAutoSyncOnJoin=true` + 服务端 `Sync request` | PASS |
| C | sync overworld | 服务端 N sent / 客户端 M updated | 双端 log + 缓存 | PASS |
| D | 完成后立即重复 sync | 地图已是最新 | 客户端 CHAT log | PASS |
| E | 30 秒后按 M + 截图 | 文件 `<cache路径>`，大小 `<bytes>` | 截图存在且显示 Xaero 地图 | PASS |

缺陷：
- <MapSyncer 缺陷，附证据路径>
环境噪声：
- <C2ME/网络/剪贴板噪声>
```
