# C2ME 与 RCON 规范

## C2ME

### 选择构建

C2ME 的 jar 是 Loader 敏感的。先解析服务端的 Loader 和精确 MC 版本，再选择匹配构建：

- Fabric：`c2me-fabric-mc<MC版本>-<C2ME版本>.jar`
- NeoForge：`c2me-neoforge-mc<MC版本>-<C2ME版本>.jar`
- Forge：只有存在官方/可信 Forge 构建时才安装；没有就记录 `C2ME N/A`。

不要用目录名猜测版本。优先从 `start.bat`、`server.properties`、loader 日志或 mod 元数据确认。

### 安装

1. 备份已有 `mods/*c2me*.jar` 到 `cache/tmp/server-command-matrix/<run>/<server>/backup/`。
2. `mods/` 中只保留一个顶层 C2ME jar。C2ME 的 `jarJar` 子模块不需要手动展开。
3. 记录 C2ME 文件名、版本、SHA256。
4. 启动后检查日志：
   - `Found mod file "...c2me...jar"`
   - mod 列表中有 `c2me` / `Concurrent Chunk Management Engine`
   - 没有 `ResolutionException`、duplicate class、module export 冲突

### MapSyncer + lz4

MapSyncer 使用 lz4 处理压缩数据。确认 jar 中 lz4 类位于：

```text
com/mapsyncer/net/jpountz/lz4/...
```

如果仍是 `net/jpountz/lz4/...`，NeoForge 可能报：

```text
Modules org.lz4.java and mapsyncer export package net.jpountz.lz4 to module ...
```

修复方式是调整 MapSyncer 的 shade/relocation，并重新构建。不要把“移除 C2ME”作为最终矩阵结果；临时移除只能用于定位问题，且结果必须标注。

### 专用服务端噪声

C2ME 可能尝试加载客户端/可选 mod 的 mixin，日志出现：

- Starlight / Sodium / VulkanMod 目标缺失；
- `Attempted to load class net/minecraft/client/Options for invalid dist DEDICATED_SERVER`。

这些通常是 C2ME 的模块探测噪声。只要 C2ME 继续加载、MapSyncer 初始化成功、服务器进入 `Done`，就不要把它们算作 MapSyncer 失败；在结果中单列“C2ME 噪声”。

## RCON

### 配置

测试前备份：

```text
cache/tmp/server-command-matrix/<run>/<server>/server.properties.before
```

测试期间：

```properties
enable-rcon=true
rcon.port=25575
rcon.password=<random test password>
broadcast-rcon-to-ops=false
```

串行矩阵可以复用 25575；并行或端口冲突时换端口并记录。不要把密码写入结果文件或提交到仓库。

### 启动检查

1. 确认 25565 和 RCON 端口空闲。
2. 启动 `start.bat`，等待 `Done (...)! For help, type "help"`。
3. 等待日志出现 `RCON running on ...`。
4. 确认 `enable-rcon=true` 且 RCON 端口在 LISTENING。
5. 先发一个无副作用命令（`<P> status`）验证登录和响应。

### 客户端

`scripts/RconClient.java` 使用标准 RCON 协议：

- 登录包 type 3；
- 命令包 type 2；
- 读取 type 0 响应；
- UTF-8，去除 `§` 颜色码；
- 支持 `stop` 导致的 EOF。

编译：

```bat
javac -d cache\tmp\rcon .agents\skills\mapsyncer-server-command-test\scripts\RconClient.java
```

调用：

```bat
java -cp cache\tmp\rcon RconClient 127.0.0.1 25575 <password> "mapsyncer status"
```

### 输出差异

| Loader | 常见输出 |
|---|---|
| Fabric | 翻译后的英文文本 |
| NeoForge | 翻译后的英文文本 |
| Forge | 翻译键，例如 `mapsyncer.prefixmapsyncer.command.incremental_disabled` |

不要用单一字符串同时断言所有 Loader。优先按以下顺序判断：

1. 解析 RCON 响应中的翻译键/文本；
2. 查配置文件值；
3. 查缓存文件和时间戳；
4. 查 `logs/latest.log` 的 worker 日志和完成行。

### 异步命令

`generate` 在后台线程执行，完成回调可能发往已经关闭的 RCON 连接。可靠判据：

- 轮询 `<P> status` 直到 `No conversion in progress` / `当前无转换任务`；
- 查 `latest.log` 的 `Dimension ... completed`、`Conversion completed`、
  `Map generation completed`；
- 查缓存文件数和时间戳变化。

任务互斥测试要在任务确实进行中时发送第二条命令；4-region 小世界可能在 0.1 秒内完成，
此时改用 `generate <dim> --force` 期间重复发送。

### 清理

1. RCON 执行 `save-all flush`。
2. RCON 执行 `stop`。
3. 等待进程退出。
4. 恢复 `server.properties`，确认 `enable-rcon=false`。
5. 确认 25565 / RCON 端口不再监听。
6. 保留 `latest.log`、RCON 响应和配置快照。

## 常见故障

| 现象 | 处理 |
|---|---|
| `Unable to establish loopback connection` | 把 `TEMP` / `TMP` / `TMPDIR` 指向仓库无空格目录 |
| RCON 登录失败 | 检查 `enable-rcon`、端口、密码、是否已出现 `RCON running` |
| RCON 命令返回翻译键 | 正常，按翻译键+状态+日志判断 |
| `ResolutionException` lz4 | 检查 MapSyncer 是否把 lz4 重定位到 `com/mapsyncer/...` |
| Forge 判为 invalid mod | 检查 `mods.toml` 的 `mandatory=true` 和主类无参构造函数 |
| 全量生成报 DIM-1/DIM1 目录不存在 | 记录为“空维度日志级别”缺陷，不要当崩溃 |
| RCON 停止时 EOF | `stop` 会关闭连接，客户端应按成功处理 |
