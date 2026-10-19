# 客户端完整回归流程

本流程直接依据当前命令树、协议、缓存与生命周期源码编写。旧测试结果不能替代本轮证据。

## 运行规则

- 固定使用 `E:\Game\服务器端` 和 `E:\Game\pcl\.minecraft\versions` 中匹配的实例；每次重新发现组合，缺实例标 `N/A`。
- 每个组合只构建、部署和启动一次；使用同一个客户端进程串联专用服阶段与 LAN 阶段，只有验证重启持久化时再启动一次。
- 同一时刻仅一个客户端接受 UI 输入。LAN 访客需要第二个客户端进程，复用已安装的固定实例，保留房主进程并串行切换焦点；同实例双进程使用各自 cache 启动输出取证，不能混用 latest.log。
- 所有客户端命令都通过真实聊天栏执行。服务器管理命令可走 RCON；不能用伪造数据包替代功能测试。
- 证据包括本步骤新增客户端/服务端日志、地图 ZIP、时间戳缓存、配置值。收到命令回显不能直接判 PASS。
- 所有备份、脚本、下载、快照与测试数据置于仓库 `cache/tmp/client-regression/<run>/`。游戏本身的 `logs/latest.log` 保留原位。
- 旧地图必须备份；半径限制不会删除客户端已经探索或下载的地图，半径断言必须比较本轮新写入的文件集合。

## 准备与工具

先运行 `cmd /c scripts\fastbuild\build-target.bat <loader>-<mc> -Offline`。
运行 `cmd /c scripts\fastbuild\build-target.bat regression` 验证纯 Java 策略与文件往返。

创建一次会话（以下路径为实际匹配的示例）：

```text
python scripts/testing/client-regression.py begin --client "E:/Game/pcl/.minecraft/versions/1.21.1-Fabric 0.19.2" --server "E:/Game/服务器端/fabric 1.21.1 server" --jar output/mapsyncer-1.0.4-fabric-1.21.jar
python scripts/testing/client-regression.py server --session <返回的session.json>
python scripts/testing/client-regression.py client --session <session.json>
```

工具先保存恢复清单，再替换两端 JAR 并校验 SHA256；备份配置、为 CodexTester 配置创造模式和 OP、启用本地 RCON。启动输出与 TEMP 均在仓库 cache。该工具不下载模组，不变更 online-mode，不自动点击 UI。

内置服可用 `client --session ... --singleplayer <已有存档> --role host`。第二个已安装客户端进程使用 `--username CodexGuest --role guest --address localhost:<公开端口>`；工具启动的是离线测试身份。原版 LAN 要求有效会话时，访客会在登录阶段失败，此时记 `BLOCKED`，需通过正常启动器使用有效账号才能继续；不要把认证失败当成 MapSyncer 握手失败，也不要关闭认证来伪造通过。

服务器必须出现 `Done`、MapSyncer 版本与对应 C2ME（无适配版记 `N/A`）；客户端必须出现同版本握手。先检查语言文本正常，不能出现原始翻译键。

每一步之前执行 `step --session ... --name <步骤ID>` 记录日志字节偏移，完成后执行 `collect --session ... --name <步骤ID>` 保存增量日志。工具只输出 `NEEDS_REVIEW`，由下述判据核实后记录 PASS / FAIL / BLOCKED。配置或界面阻塞必须保留原因和后续未执行的项目。

## 一次专用服会话的执行顺序

服务端前缀：Fabric 为 `/mapsyncerserver`，Forge/NeoForge 为 `/mapsyncer`。以下以 `<server>` 表示。客户端统一 `/mapsyncer`。

1. **P0 基线**：确认 `/gamemode creative` 成功；执行客户端根命令、`help`、`autosync`。管理端执行 `<server> status`、`radius`、`incremental`、`help`，确认权限与翻译。先将增量设为 `off`，避免准备数据时自动生成干扰。
2. **P1 生成与层**：先生成三个原版维度和数据包测试维度，再开始下载。覆盖 `generate <dim> <x> <z>`、`generate <dim>`、`--force`、全量生成、运行中重复生成的 busy 提示；status 终止且缓存非空。地表和至少一个洞穴层均需有有效 ZIP。数据包定义保存在 cache，按对应 MC 的数据包格式加载。
3. **P2 首次下载**：客户端时间戳基线为空；执行 `sync`。确认服务端发送、客户端写入完成、ZIP CRC 可读、缓存键与时间戳存在。紧接着再次执行完全相同命令，必须返回 uptodate，不能卡在写入中。
4. **P3 请求范围**：执行 `sync minecraft:overworld`、`sync all`、`sync minecraft:invalid_dim`；确认目标维度、所有维度、不可用维度各自终止。`sync all` 同坐标跨维度必须全部落盘；只刷新当前维度的 Xaero 内存。再次 sync all 应稳定收敛。
5. **P4 自定义维度**：数据包包含 `test:moon`、`test:group/moon`、`minecraft:custom`，并让两个维度共用原版 dimension_type。验证聊天补全能列出真实维度 ID；完整 ID 可同步；嵌套路径对应 `test$group%moon`；配置层计划有效。另加入另一 namespace 的同名 moon，确认 MCA 时间戳和生成结果独立。
6. **P5 半径正向**：在实际有足够 region 的测试世界中设置 `<server> radius 1`。以请求时玩家 region 为中心，缓存准备 `(cx±1,cz±1)` 和超出一格的 ZIP；清空本轮下载基线后 sync all。仅当前维度的 3×3 region 方形内文件允许新写入，地表与洞穴层一并裁剪。没有边界内外数据只能 BLOCKED，不能根据文件总数猜 PASS。
7. **P6 半径边界**：在 X/Z 为 `-1`、`-512`、`-513` 和正坐标 `511/512` 附近重复 P5；边界使用 floorDiv。两个玩家分别请求时应以各自位置为中心。开启限制后显式请求其他维度不得发送其数据。
8. **P7 配置与关闭**：执行 `radius 0` 后 sync all，原先范围外数据应可发送；负值被 Brigadier 拒绝。执行 `radius 2` 后核对持久化文件，`reloadconfig` 后仍为 2；编辑配置再 reload，随后新请求使用新值。配置路径：Fabric `config/mapsyncer-server.properties` 的 `syncRadiusRegions`；Forge/NeoForge 服务端配置的 `[server]` 段 `syncRadiusRegions`。最后恢复 0。
9. **P8 中断恢复**：限速下开始足量下载，真实断开并重连；保留已落盘哈希，仅补未完成项。另测请求分片中断、同步中切维度、立即重发请求，确认旧线程不取消新请求；哈希扫描中断线切服后不得将旧请求发送到新连接。所有任务必须有终态，断线需有资源清理日志。
10. **P9 失败恢复**：选定一个测试 ZIP 目标制造可恢复的写入失败（先备份）；客户端必须提示 partial 并保留恢复标记。解除故障后重复同步应补齐；服务器某一生成层写入失败后，MCA mtime 不变也要重试。只在 cache 夹具或固定测试实例进行故障注入。
11. **P10 自动同步 off→on**：设置 `<server> incremental scheduled`，等当前同步终止；客户端 autosync off→断开→把时间戳备份到 cache 并移走→重连，应明确禁用且不发自动请求；autosync on→断开→重连，应触发自动同步。随后切服务端 TICK 模式并重连，等待一个真实周期，确认在线自动同步。最后 off，确认待执行的进服任务也被取消。
12. **P11 显示与统计**：完成主世界同步后等待完整 30 秒，按 M 打开地图，保存截图到会话目录；检查地表、洞穴、自定义维度实际可见。截图补充文件与日志证据，不替代它们。

## LAN 与同客户端跨存档

专用服阶段结束后先正常停止服务端，确认 25565/RCON 释放。保留客户端，进入固定实例中的测试存档 A，通过“对局域网开放”公开端口。

1. 房主与访客均有本轮 MapSyncer，访客连接实际公开端口。核对两端握手，分别执行首次同步、立即重复同步、指定维度、sync all、自定义维度和 radius 1→0。
2. 房主地图写入 Xaero `MapWorld.getMainId()` / `MapSaveLoad.getRootFolder()` 返回的目录；访客写入它自己的 Xaero 多人目录。不得生成或复用 `Multiplayer_Singleplayer`。房主转换缓存必须位于 `saves/<A>/server_map_cache`，不能覆盖 Xaero 实时地图。
3. 房主离开 A，再进入 B，并生成地图；核对 B 的 MCA 时间戳、维度配置和生成目录不复用 A 的实例。返回 A 再同步验证可用。
4. 自定义 `syncRadiusRegions` 在内置服同样生效；房主无 ServerData 也应能完成地图写入和第二轮哈希扫描。

只有一个可用实例时 LAN 访客一项必须记 BLOCKED；单人内置服不能冒充远程访客验证。

## 收尾与结果

通过 RCON `stop` 正常停服，关闭本轮客户端，确认所记录进程均退出；执行 `restore --session ...` 恢复两端旧 JAR 与备份配置。新创建的测试维度、缓存故障夹具和时间戳备份也需按会话清单恢复。不得按进程名批量终止 java。

结果按步骤逐项记录状态、实际新增日志、SHA256、ZIP/配置路径与截图；`NEEDS_REVIEW`、缺实例、缺边界数据、未跑项目都不能计入通过率。构建通过只代表编译和打包成功，不能替代游戏内行为验证。
