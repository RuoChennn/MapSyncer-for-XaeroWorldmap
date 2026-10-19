---
name: mapsyncer-client-command-test
description: >-
  Run real-chat MapSyncer client regressions on the fixed local Fabric, Forge,
  and NeoForge instances, including sync/resume, autosync reconnects, region
  radius limits, data-pack dimensions and open-to-LAN host/guest behavior.
---

# MapSyncer 客户端完整回归

从当前源码确认命令与配置后，按
[一次会话完整流程](../../../docs/client-regression-flow.md) 执行。
流程文件是测试顺序与判据的唯一主线；旧结果、历史问题清单不能作为通过证据。

## 必须保持的边界

- 使用固定服务端 `E:\Game\服务器端` 和客户端 `E:\Game\pcl\.minecraft\versions`；每轮重新发现精确组合。无对应实例记 N/A，不能以相邻版本替代。
- 用 `cmd /c scripts\fastbuild\build-target.bat <target>` 构建，两端部署同一单文件 JAR，并核对 SHA256。Forge 1.20.1 还需确认 reobf 后的 Commands 方法名。
- 所有备份、启动输出、临时脚本、截图、测试夹具和 TEMP/TMP/TMPDIR 放仓库 `cache/tmp/`。游戏自身 `logs/latest.log` 保留原位。仓库 AGENTS.md 优先于旧参考文档中的目录示例。
- 一个组合完成部署后，在同一客户端进程串联全部功能；只有验证重启持久化时才重启。不同组合不能同时占用 25565 或接受 UI 输入。LAN 需要真实房主和访客，允许两个已存在客户端进程，但一次仅操作一个窗口。
- 客户端命令必须通过真实聊天栏输入。先观察并激活 Minecraft，再按 `/`、输入命令、回车；以本次新增 CHAT 日志确认接受与终态。不能直接调用命令类或伪造网络包。
- 每个步骤记录日志字节偏移，再结合双端新增日志、ZIP、时间戳缓存和配置判断。命令回显、截图、编译成功均不能单独作为同步通过证据。
- 当前账号必须实际处于创造模式，记录 `/gamemode creative` 成功或 force-gamemode 证据；服务端命令需 OP。自动同步 off/on 两个方向必须真实断开重连，并准备可判定的落后时间戳基线。
- 半径测试比较本轮新增/更新的 region，不能因为客户端旧地图仍存在而误判越界；0 是关闭限制。开启时 sync all 也只允许当前维度，地表与洞穴均受限。
- 数据包维度必须覆盖共享原版 dimension_type 的非 Mod 维度、minecraft 命名空间、嵌套路径和不同命名空间同名维度。补全来自真实维度列表，不能从 dimension_type 猜 ID。
- 同步写入终止后立即重复同一命令，验证状态释放和 uptodate；主世界最后一次同步后等待完整 30 秒，按 M 打开 Xaero 并保存有效截图。
- Computer Use 检测到用户接管或物理 Escape 时停止输入、记录阻塞；窗口状态变化后重新观察。用户明确授权继续时，可从新状态恢复该步骤，不能盲发按键。
- 完成后正常停止本轮服务端、关闭本轮客户端，确认端口和进程释放，恢复原 JAR、配置、自动同步值及临时移动的地图/时间戳。禁止按进程名批量杀 java。

## 会话工具

优先使用仓库 `scripts/testing/client-regression.py`：

- `begin`：先写恢复清单，备份配置与旧 JAR、部署并校验两端哈希。
- `server` / `client`：使用固定实例、匹配 JDK，启动输出和临时目录写 cache。
- `rcon`：仅用于服务端管理命令；客户端测试仍通过聊天栏。
- `step` / `collect`：按步骤保存增量证据，默认 NEEDS_REVIEW，不能自动当 PASS。
- `restore`：仅在本轮进程退出后恢复记录的原文件。

先运行 `cmd /c scripts\fastbuild\build-target.bat regression` 验证策略与文件往返；这不能替代游戏内回归。

测试结果逐项标 PASS / FAIL / BLOCKED / N/A，包含两端 SHA256、版本、创造模式、自动同步重连、半径边界、维度映射、LAN 房主/访客、截图与清理证据。无法实际运行的步骤必须保留为未验证。
