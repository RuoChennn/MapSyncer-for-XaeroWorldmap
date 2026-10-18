# 无截图客户端测试方案

## 结论

当前环境没有现成的 Minecraft 客户端命令 MCP 或 skill：

- `mcp__minecraft_modding` 只提供 jar 分析、映射查询、NBT 和 registry 数据，
  不能启动客户端、注入命令或读游戏日志。
- `computer-use` 可以键盘输入，但需要窗口焦点；截图不是必需的。
- 服务端 RCON 只能执行服务端命令，不能触发客户端 `/mapsyncer` 命令。

## 方案对比

| 方案 | 是否无视觉 | 是否改代码 | 并行 | 风险 |
|---|---|---|---|---|
| 键盘注入 + `latest.log` 判据 | 不需要截图，但仍需窗口焦点 | 否 | 否（单屏幕） | 输入法/丢字；需要确认命令被接受 |
| 客户端测试 harness mod | 是 | 是（测试模块） | 可以，每实例独立 | 需要维护 Fabric/Forge/NeoForge 胶水 |
| JDWP/JDI 注入 | 是 | 否 | 可以 | 依赖 intermediary/SRG 映射，版本间脆弱 |
| 服务端 RCON | 是 | 否 | 可以 | 只覆盖服务端，不覆盖客户端命令 |

## 当前采用：键盘注入 + `latest.log`

这是本 skill 的唯一执行主线。命令判据不截图，但需要 Minecraft 窗口获得焦点；
断开/重连时可以用截图定位暂停菜单和标题界面按钮。流程：

1. 激活 Minecraft 窗口。
2. 按 `/`，`type_text` 命令，按回车。
3. 读 `latest.log` 新增的 `[System] [CHAT]` 行，确认命令已被接受、完成或报错。
4. 如果日志没有对应回显，重新激活窗口并重发；不要盲目回车。
5. 等待完成行后再执行下一条，状态释放用例在完成行之后立即重发。

详细按键和恢复流程见
[键盘注入与日志判据](windows-ui-automation.md)。

## 备选：客户端测试 harness

该方案未实现，也不在当前测试范围内；只有用户明确要求时才新增。

新增一个 test-only 客户端 mod，不进入发布 jar。核心行为：

1. 启动参数读取脚本路径，例如 `-Dmapsyncer.client.test.script=<file>`。
2. 玩家加入服务器后延迟 N tick，按顺序执行脚本中的客户端命令。
3. 每条命令通过客户端命令 dispatcher 执行，等同于聊天栏 `/` 命令。
4. 等待条件：固定 tick、日志出现指定文本、同步进度结束、超时。
5. 每条结果写 `[MapSyncerTest] PASS/FAIL <command> <detail>`，最后写
   `[MapSyncerTest] DONE pass=N fail=M` 并可选自动退出客户端。
6. 失败时保留 `latest.log` 和服务端日志，不需要截图。

胶水层按 loader 分开：

- Fabric：`ClientTickEvents.END_CLIENT_TICK`、`ClientPlayConnectionEvents.JOIN`。
- Forge/NeoForge：客户端 tick 事件和登录事件。

命令执行入口优先使用客户端网络 handler 的 `sendCommand`/`sendChatCommand`
（各版本映射不同），或直接调用客户端命令 dispatcher 的 `execute`。

harness 适合需要真正并行、或无法稳定保持窗口焦点的场景；当前不实现。
