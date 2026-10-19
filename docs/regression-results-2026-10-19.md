# 本轮验证结果（2026-10-19）

本轮证据位于 `cache/tmp/client-regression/20261019-200622/`；构建日志和产物哈希位于 `cache/tmp/review-sync/`。这些本机运行产物不提交 Git。下列结果仅代表实际执行的范围。

## 构建与静态审查

- **PASS**：14 个 Fabric／Forge／NeoForge 目标经 fastbuild 分别构建成功。覆盖 1.20.1、1.21.1、1.21.11、26.1、26.2、26.3 的现有交付组合。
- **PASS**：14 个 output 单文件 JAR 包含 core、platform、新半径策略、语言与 loader 元数据、重定位后的 LZ4；不存在未重定位的 `net/jpountz/`。清单为 `cache/tmp/review-sync/release-jars.json`。
- **PASS**：最终 Forge 1.20.1 JAR 的 Brigadier 指令注册调用为 reobf 后的 `Commands.m_82127_`。
- **PASS**：`build-target.bat regression` 的 15,187 项断言，覆盖半径正负边界与溢出、维度路径往返、递归扫描、分片预算、MCA 重试／毫秒持久化、真实 ZIP 写盘、房主直写／多世界目录与远程目录、安全路径拒绝。
- **PASS**：Python 语法与 Git diff 空白检查。服务端最终只读复审未发现新的可复现阻断／高风险问题；客户端复审发现的房主多世界目录边界已修复。

## 真机环境与 JAR 阶段

使用固定 `fabric 1.21.1 server` 与 `1.21.1-Fabric 0.19.2` 客户端，JDK 21、Xaero World Map 1.40.16、已安装 C2ME；测试账号 CodexTester。LAN 使用已有 `MapSyncerClientTest` 存档，测试前完整备份。管理指令与客户端指令均有本轮日志，客户端功能通过真实聊天栏输入。

修复是实测驱动的，因此发生过重建与进程重启，不能把整轮宣称为单一 JAR 的一次运行。专用服阶段 JAR 为 `9ef7eccd…`，初次 LAN 为 `cba1c40c…`，实际多世界目录修复后的维度／失败恢复为 `aaeb3dff…`。最终两端部署相同 JAR SHA256：`929750a780f3405b8441c70552ab4d67c59e2c0b7cf3d7c7efb76536586b7e32`，并重新进行了 LAN 冒烟验证。完整阶段哈希记录在 session.json。

## 实际通过

- **专用服半径**：玩家中心 region 为 `(0,-1)`，radius 1 首轮接收 18 项（9 个地表、9 个洞穴层 3），全部属于当前维度且在 3×3 网格内，无越界键。`radius-one-verdict.json` 保存具体集合。立即重复为 18 个哈希一致。
- **关闭半径**：radius 0 后接收额外 178 项，随后重复为 196 个哈希一致。负数指令被拒绝；radius 2 保存配置，reload 后保持为 2。
- **客户端基本流程**：help、autosync 状态、sync all、完整维度 ID、无效维度终态、立即重复同步均有真实聊天日志。
- **自动同步重连**：Scheduled 模式下 autosync off 后真实断开重连，20:16:56 判定不发起进服自动请求；on 后再次断开重连，20:18:17 判定需要同步，随后完成 196 项下载。
- **LAN 房主**：实际公开 25565；主世界首次 4 项成功，立即重复收敛。转换缓存独立于客户端地图；没有写入 `Multiplayer_Singleplayer`。最终 JAR 的 radius 1→0 分别只统计当前维度 4 项和所有维度 20 项。
- **非 Mod 维度**：同一数据包定义四个共用原版 overworld 类型的维度：`mapsyncer_test:moon`、`mapsyncer_test:group/moon`、`mapsyncer_other:moon`、`minecraft:mapsyncer_custom`。全部真实进入、生成、下载；嵌套路径、minecraft namespace、不同 namespace 同名维度分别落盘。补全截图为 `dimension-completion.png`。20 个 ZIP 完整性检查通过，见 `lan-zip-verdict.json`；时间戳快照包含独立完整 ID。
- **写盘失败恢复**：对非当前维度的目标 ZIP 使用可读、拒绝替换的真实 Windows 文件占用。23:11:47 客户端只计成功的 3 项，提示 partial，并保留 in_progress；释放句柄后 23:12:30 只补传失败的 1 项，恢复 completed；随后重复无需下载。日志为 `host-directory-and-failure.out.log`，截图为 `write-failure-recovery.png`。
- **地图显示**：专用服与 LAN 主世界同步后均等待超过 30 秒，再实际打开 Xaero 地图。截图 `xaero-overworld.png`、`lan-overworld.png` 包含有效地形；实例开启了 Xaero 详细调试叠层。
- **清理**：本轮进程正常退出、端口释放；原 LAN 存档和地图按文件集合与 SHA256 核对恢复。原始 JAR、配置、OP、server.properties 和暂移的时间戳均已恢复；测试数据、快照和日志留在 cache。

## 阻塞与尚未覆盖

- **BLOCKED：LAN 访客登录**。实际第二进程 CodexGuest 连接房主，但原版登录界面报“无效会话”，未进入 MapSyncer 握手。需通过正常启动器使用有效账号继续访客完整矩阵；本轮未修改认证策略。
- **环境异常**：关闭访客时房主发生一次 `glfw.dll+0x101ab` 原生访问违规，保存 `host-hs_err_pid24536.log`；栈位于 Minecraft Render thread／LWJGL JNI。仅据此不能认定具体诱因。重新启动后完成后续测试，最终正常退出。
- **未执行**：其余 13 个 loader／版本组合的完整游戏内客户端矩阵、真实慢网络分片超时、同步中断线／切服竞争、TICK 自动同步完整周期、两个玩家不同中心的半径比较、全部方块边界的真机重复、跨两个存档切换、Xaero UI 中自选多世界地图的实测。相应部分有源码／纯 Java 覆盖，不能替代这些运行测试。

一次会话的完整待执行顺序与各项判据已整理到 [客户端回归流程](client-regression-flow.md)，并由项目客户端测试 skill 引用。不能把本轮结果描述成“所有功能、所有版本均已通过”。
