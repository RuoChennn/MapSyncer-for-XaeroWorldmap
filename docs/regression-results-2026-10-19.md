# 本轮验证结果（2026-10-19）

本轮证据位于 `cache/tmp/client-regression/20261019-200622/`；构建日志和产物哈希位于 `cache/tmp/review-sync/`。这些本机运行产物不提交 Git。下列结果仅代表实际执行的范围。

## 运行角色与提示策略补充

- **专用服务端**：由 MapSyncer 执行 MCA 转换、维护缓存并分发地图。
- **远程客户端**：接收地图；连接 LAN 的访客也属于此角色。
- **客户端启动的内置服务器**：涵盖未公开的单人世界和开放到 LAN 后的房主。建议房主通过 Xaero 的“转换所有区块”生成原生地图数据。按本轮最终约定，仅增加提示，不禁用 MapSyncer 的手动、强制或自动增量生成，也不阻断现有同步流程。

新提示在进入内置服务器和执行四类生成命令时显示；专用服不显示。下文 LAN 结果仍是历史实测记录，只证明现有 MapSyncer 转换／同步流程可运行，不证明使用了 Xaero 原生转换数据。新提示的验证应单独记录，不能沿用下文旧 JAR 的通过结论。

提示改动另经 Fabric 1.20.1、1.21.1、1.21.11、26.1 的 fastbuild 离线构建通过；四个 output JAR 已核对中英文提示、common／core／platform 类及重定位后的 LZ4。未重新执行游戏内提示验证，也未重建本次改动的 Forge／NeoForge 产物。

## 后续目录复用验证（2026-10-20，仍为 v1.0.4）

内置服已改为直接复用房主 Xaero 地图目录和每个维度所选的多世界目录，不再新增 `saves/<存档>/server_map_cache` 地图副本。原生 `cm$converted`、`mw$...`、地表和洞穴按同一选中地图读取；同步按实际文件重新计算哈希和修改时间。MapSyncer 生成命令仍可使用，输出到同一目录；`--force` 不再递归删除整个 Xaero 维度目录，而是仅清理所选地图的区域 ZIP。旧独立缓存不自动删除。

- **PASS**：14 个 Fabric／Forge／NeoForge 目标全部通过 fastbuild 离线构建，产物仍为 1.0.4。14 个 output JAR 均包含新的 `ServerMapCache`、core、platform、重定位 LZ4、语言与 loader 元数据；Forge 1.20.1 注册仍使用 `Commands.m_82127_`。
- **PASS**：原有 15,187 项回归断言；新增 28 项真实 ZIP／目录断言覆盖原地读取、不创建副本、选中地图与洞穴键、原生文件更新、临时文件隔离、强制清理范围、路径拒绝、旧存档快照拒绝及专用服路径恢复。
- **未执行**：此后续实现的游戏内提示、Xaero 原生转换后实际分发、房主生成后的地图显示和 LAN 访客回归。下文 10 月 19 日的运行证据验证的是旧独立缓存流程，不能用于宣称本次目录复用已完成真机验证。

本次证据在 `cache/tmp/integrated-cache-reuse/`：`build-<loader>-<mc>.log`、`regression-final.log`、`release-jars.json`；Xaero 1.40.16 的 `javap` 核查确认单人根目录通过 `MapWorld.convertWorldFolderToRootId` 与 `MapSaveLoad.getRootFolder` 定位。目录和文件回归使用受控夹具，不等于 Minecraft 运行测试。

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
