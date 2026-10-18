# 键盘注入与日志判据（唯一主线，命令判据不截图）

通过 `mcp__node_repl` 的 `js` 工具使用 Computer Use 的 `@oai/sky`。不要用
`mcp__cua_repl` 的浏览器 API 代替 Windows 窗口输入。Minecraft 是自绘界面，
没有可用的无障碍树，
只做键盘注入，验证以客户端 `latest.log` 的 `[System] [CHAT]` 行为准。
命令阶段不截图。断开/重连需要在暂停菜单和标题界面定位按钮，允许截图导航；
截图只在输入丢失、日志缺失或需要确认视觉状态时作为补充证据。

## 初始化与选窗

```js
if (!globalThis.sky) {
  const { sky } = await import("@oai/sky");
  globalThis.sky = sky;
}
const windows = await sky.list_windows();
const mc = windows.find(w => w.app?.includes("java.exe") && w.title?.includes("Minecraft"));
if (!mc) throw new Error("Minecraft window not found");
globalThis.mcWindow = mc;
await sky.activate_window({ window: mc });
globalThis.state = await sky.get_window_state({
  window: mc,
  include_screenshot: false,
  include_text: false,
});
```

窗口标题会随状态变化：

- 主菜单：`Minecraft* <版本>`
- 单人：`Minecraft* <版本> - 单人游戏`
- 联机：`Minecraft* <版本> - 多人游戏（第三方服务器）`

只用 `app` 和标题前缀筛选，不要猜窗口 id。

## 稳定输入一条命令（无截图）

先激活窗口一次，然后发送命令。不要依赖截图确认输入框：

```js
await sky.activate_window({ window: mcWindow });
await sky.press_key({ window: state.window, key: "slash" });
await new Promise(r => setTimeout(r, 700));
await sky.type_text({ window: state.window, text: "mapsyncer sync minecraft:overworld" });
await new Promise(r => setTimeout(r, 300));
await sky.press_key({ window: state.window, key: "Return" });
await new Promise(r => setTimeout(r, 1200));
```

命令是否被接受，看日志新增的 `[System] [CHAT]` 行：

```powershell
$log = 'E:\Game\pcl\.minecraft\versions\<实例>\logs\latest.log'
$before = (Get-Content -LiteralPath $log).Count   # 发送前记录
# 发送命令并等待 1.5-3 秒后
Get-Content -LiteralPath $log |
  Select-Object -Skip $before |
  Select-String '\[System\] \[CHAT\]' |
  ForEach-Object { $_.Line }
```

实际日志行形如：

```text
[19:12:06] [Render thread/INFO]: [System] [CHAT] [MapSyncer]客户端自动同步：已开启
```

如果 1-2 秒内没有新增 CHAT 行，说明输入没有进聊天框或命令被菜单拦截：重新激活
窗口、按一次 Escape 关闭可能的菜单，再按 `/` 重发。不要在没有日志证据时连续回车。
聊天框关闭后 Escape 会打开游戏菜单，这是常见误判。

同步类命令必须等到终止行后再发下一条。终止行包括：

- `地图已是最新，无需下载`
- `同步完成，已更新 N 个区域`
- `维度 ... 地图数据不可用`
- `正在接收或写入地图数据，请待当前步骤完成后再试`
- 其他明确的错误/失败文本

`下载进度`、`即将下载`、`地图同步完成，已发送` 都只是中间状态，不能当作客户端
会话已释放。

## 进服后切创造模式

收到 `Server has MapSyncer installed` 后，用同一条真实聊天栏发送：

```js
await sky.activate_window({ window: mcWindow });
await sky.press_key({ window: state.window, key: "slash" });
await new Promise(r => setTimeout(r, 700));
await sky.type_text({ window: state.window, text: "gamemode creative" });
await new Promise(r => setTimeout(r, 300));
await sky.press_key({ window: state.window, key: "Return" });
```

判定以客户端新增的 `[System] [CHAT]` 行为准，文本包含 `创造模式` 或
`Creative Mode` 即可。服务端不一定会记录这条命令的反馈，不能因为服务端没有对应行
就判失败。重连后玩家数据仍应为创造模式；若回到生存，重新执行一次并记录。

## 断开与重连

自动同步开关的持久化必须在同一个客户端进程里真实断开再进入，不能用重启服务端代替。
Minecraft 是自绘界面，暂停菜单和标题界面的按钮没有可用无障碍树；这一步允许截图定位
按钮，但截图只用于导航，不作为 PASS/FAIL 证据。

1. 按 Escape 打开游戏菜单；如果聊天栏还开着，先按一次 Escape 关闭聊天，再按一次打开菜单。
2. 用 `get_window_state({ include_screenshot: true })` 找到“断开连接”/“Disconnect”
   按钮，按截图中的实际坐标点击。
3. 等客户端日志新增 `Client disconnected, all resources cleaned up`，服务端日志新增
   `CodexTester left the game`。
4. 在标题界面点击“多人游戏”/“Multiplayer”，双击 `localhost` 条目；列表里没有条目时
   用“直接连接”/“Direct Connection”输入 `localhost:25565`。
5. 等客户端日志新增 `Connecting to localhost, 25565`、
   `Server has MapSyncer installed, version: ...`；Fabric 另有
   `Client joined server, checking sync state...`。

坐标路径示例（`x`/`y` 必须按当前截图替换，不能固定写死）：

```js
const observation = await sky.get_window_state({
  window: mcWindow,
  include_screenshot: true,
  include_text: false,
});
const screenshotId = observation.screenshots?.[0]?.id;
await sky.click({ window: observation.window, screenshotId, x: 640, y: 480 });
```

重连后先看日志，再发下一条命令。标题界面、服务器列表和“正在连接”画面都不是完成判据。

## 主世界同步后打开 Xaero 并截图（E）

在 D 的立即重复 sync 完成后再执行。先等待 30 秒，再激活 Minecraft 按 `M`：

```js
{
  const fs = await import("node:fs/promises");
  const outPath = "D:/开源项目/MapSyncer-for-XaeroWorldmap/cache/tmp/client-command-matrix/2026-10-18/example/xaero-overworld-20261018-120000.png";
  const windows = await sky.list_windows();
  const mc = windows.find(w => w.app?.includes("java.exe") && w.title?.includes("Minecraft"));
  if (!mc) throw new Error("Minecraft window not found");
  await sky.activate_window({ window: mc });
  const before = await sky.get_window_state({
    window: mc,
    include_screenshot: false,
    include_text: false,
  });
  await sky.press_key({ window: before.window, key: "m" });
  await new Promise(r => setTimeout(r, 2000));
  const captured = await sky.get_window_state({
    window: before.window,
    include_screenshot: true,
    include_text: false,
  });
  const shot = captured.screenshots?.[0];
  if (!shot?.url) throw new Error("No game window screenshot returned");
  await fs.mkdir(outPath.substring(0, outPath.lastIndexOf("/")), { recursive: true });
  const bytes = Buffer.from(shot.url.split(",")[1] ?? "", "base64");
  if (bytes.length === 0) throw new Error("Screenshot data is empty");
  await fs.writeFile(outPath, bytes);
  await sky.press_key({ window: before.window, key: "m" });
  nodeRepl.write(JSON.stringify({ path: outPath, bytes: bytes.length }));
}
```

把 `outPath` 换成当前日期和组合目录，例如
`cache/tmp/client-command-matrix/2026-10-18/fabric-1.21.1/xaero-overworld-<stamp>.png`。
等待 30 秒必须发生在按 `M` 之前；不要用截图代替同步终止行判据。按 `M` 后如果画面仍是
普通游戏界面，检查 Xaero 键位是否被占用，记录实际键位或 `BLOCKED`。

## 可选截图恢复

只有日志无法判断时，才调用 `get_window_state({ include_screenshot: true })`：

- 输入框只剩 `/`：补一次 `type_text`。
- 画面停在“下载进度”或“等待服务端响应”：等待并继续读日志。
- 出现“正在接收或写入地图数据”：这是状态泄漏的 FAIL 证据。

## 观察与恢复

- 无截图模式下不需要每次 `get_window_state`；窗口句柄从 `list_windows()` 取得一次，
  日志判据为主。
- 工具报 `user input was detected in this window` 时，先重新观察，再继续。
- 工具报 “stopped by the user with the physical Escape key” 时，立即停止本轮
  UI 操作，不要继续注入输入。
- 聊天栏消息几秒后会淡出；日志里的 `[System] [CHAT]` 行是长期证据。

## 常见视觉状态

- 命令成功：聊天栏出现 `[MapSyncer]...`。
- 创造模式：聊天栏出现 `你的游戏模式已更新为创造模式` /
  `Set own game mode to Creative Mode`。
- 断开后：客户端日志出现 `Client disconnected, all resources cleaned up`，画面回到标题
  界面；这不是“客户端崩溃”。
- 重连后：客户端日志再次出现 `Server has MapSyncer installed`；Fabric 另有
  `Client joined server, checking sync state...`。
- 同步中：屏幕中央 `下载进度: n/N`。
- 同步完成：`地图同步完成，已发送 N 个需更新区域` 和
  `同步完成，已更新 N 个区域（耗时 X 秒）`。
- 状态泄漏：完成后重复命令出现
  `正在接收或写入地图数据，请待当前步骤完成后再试`。
- 无效维度：`维度 'xxx' 地图数据不可用，请先运行 /mapsyncerserver generate xxx`。

## 证据

结果文件记录日志时间戳和关键 `[System] [CHAT]` 行；截图不是必需产物。
