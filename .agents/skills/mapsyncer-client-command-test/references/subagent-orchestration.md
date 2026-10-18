# 子代理调度与全局锁

客户端 UI 共用一块屏幕，服务端共用 25565，Gradle 构建共用 settings 切换，
因此并行只用于不同组合的准备和日志分析，关键区段必须串行。

## 锁协议

工作目录：`cache/tmp/client-command-matrix/`。

```text
ui.lock          # UI 阶段（启动客户端、键盘注入、日志核对）
gradle.lock      # build-target.bat
port-25565.lock  # 启动服务端并占用端口
```

获取锁用原子创建，失败则等待后重试，设置超时：

```powershell
$lock = 'cache\tmp\client-command-matrix\ui.lock'
$deadline = (Get-Date).AddMinutes(10)
while ($true) {
    try {
        New-Item -ItemType File -Path $lock -ErrorAction Stop | Out-Null
        break
    } catch {
        if ((Get-Date) -gt $deadline) { throw 'ui.lock timeout' }
        Start-Sleep -Seconds 5
    }
}
```

结束时在 `finally` 中停止本组合启动的客户端/服务端进程，再删除锁。不要杀不属于
本组合的 java 进程。旧 jar 和旧地图缓存的备份放在对应游戏目录的
`codex-backups/`，不要放到仓库 `cache/`。

## 每组合结束必须清场

每组测试完成后立即终止本组所有游戏进程，不能让服务端或客户端跨组合常驻：

```powershell
$pids = @($clientPid, $serverPid) | Where-Object { $_ }
foreach ($id in $pids) {
    Get-Process -Id $id -ErrorAction SilentlyContinue | Stop-Process -Force
}
# Forge/NeoForge 的 run.bat 会派生 java，按工作目录/命令行补充清理
Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object {
        $_.CommandLine -like "*$ServerDir*" -or $_.CommandLine -like "*$VersionDir*"
    } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Start-Sleep -Seconds 2
```

清理后必须验证：

- `Test-NetConnection 127.0.0.1 -Port 25565` 失败或 `Get-NetTCPConnection` 无监听。
- `sky.list_windows()` 中没有该组合的 Minecraft 窗口。
- 结果文件写入 `BLOCKED`/`PASS` 后再释放锁。

主代理收到子代理结果时也要核验 25565；发现残留进程时先清理，再派下一批。

子代理的最终回报必须包含清理证据（25565 无监听、无本组合 Minecraft 窗口），
不能只说“已关闭”。清理代码放在 `try/finally`，即使测试中途失败也必须执行。

## 组合分配

一个子代理只负责一个“Loader + MC 版本”组合。派发时提供：

- 客户端实例绝对路径和版本 ID。
- 服务端目录绝对路径。
- 构建目标（如 `fabric-1.21.1`）。
- 结果文件路径。
- skill 路径：`.agents/skills/mapsyncer-client-command-test/SKILL.md`。
- 测试账号与权限方案：默认 `CodexTester` + 离线 UUID
  `dc94793c-8409-363d-954a-a2295f4554d1`；说明是写 `ops.json` 还是用
  `force-gamemode` 等效方案。

子代理必须自己读取 `SKILL.md` 和按需读取 references，不要依赖主代理转述。

派发时必须明确要求：

- 进服后先 `/gamemode creative`，再执行 A-D；结果里带创造模式证据。
- B 阶段做两轮真实断开/重连：`autosync off` 后先移走客户端
  `sync_timestamps.cache` 再重连，必须仍不自动同步；`autosync on` 后重连必须触发
  自动同步。
- 不能用“改配置文件成功”代替重连生效验证，也不能用重启服务端代替客户端断开。
- D 的立即重复 sync 完成后等待 30 秒，按 `M` 打开 Xaero，截取游戏窗口并把 PNG
  保存到本组合的 `cache/tmp/client-command-matrix/<date>/<loader>-<mc>/` 下。

## 执行模型

- **一次只派一个子代理，只测一个组合**。等它完成、清场并释放锁后，主代理再派
  下一个。
- 不并行构建、不并行启动服务端、不并行操作客户端。构建脚本会切换
  `settings.gradle`，并行构建会互相污染。
- 某组合在锁超时、服务端崩溃或客户端无法启动时，记录 `BLOCKED`，清场后继续
  下一个，不要无限重试。

### 子代理回报格式

```text
COMBO: <loader>-<mc>
STATUS: PASS | FAIL | BLOCKED
JARS: client=<sha256> server=<sha256> equal=<yes|no>
JOIN: <client log evidence>
CREATIVE: <client CHAT evidence / force-gamemode>
COMMANDS:
- <command> -> <terminal CHAT line> -> PASS/FAIL
AUTOSYNC_RECONNECT:
- off: <cache removed + config + shouldAutoSyncOnJoin=false + no Sync request> -> PASS/FAIL
- on: <config + shouldAutoSyncOnJoin=true + Sync request + completion> -> PASS/FAIL
XAERO_SCREENSHOT: <absolute path> / bytes=<n> / visible=<yes|no|blocked>
DEFECTS:
- <MapSyncer defect + evidence path>
CLEANUP:
- port25565=<free|in-use>
- windows=<none|details>
RESULT_FILE: <absolute path>
```

## 建议组合顺序

先测当前已构建目标最完整的版本，再测新增版本：

1. `fabric-1.21.1` / `1.21.1-Fabric 0.19.2` / `fabric 1.21.1 server`
2. `forge-1.21.1` / `1.21.1-Forge_52.1.14` / `forge 1.21.1 server`
3. `neoforge-1.21.1` / `1.21.1-NeoForge_21.1.227` / `neoforge 1.21.1 server`
4. `fabric-1.20.1` / `1.20.1-Forge_47.4.20`（客户端仅有 Forge 实例时改测 Forge）
5. `forge-1.20.1` / `1.20.1-Forge_47.4.20` / `forge 1.20.1 server`
6. `fabric-1.21.11` / `1.21.11-Fabric 0.19.3` / `fabric 1.21.11 server`
7. `neoforge-1.21.11` / `1.21.11-NeoForge_21.11.42` / `neoforge 21.11 server`
8. `fabric-26.1` / `26.1.2-Fabric 0.19.2` / `fabric 26.1 server`
9. `neoforge-26.1` / `26.1.2-NeoForge_26.1.2.68-beta` / `neoforge 26.1 server`

没有匹配实例或服务端的组合标 N/A，不跨 Loader 复用 jar。

## 结果落盘

每个组合写
`cache/tmp/client-command-matrix/<date>/<loader>-<mc>.md`，包含
[client-matrix.md](client-matrix.md) 的结果模板。主代理最后合并为一份矩阵报告。
