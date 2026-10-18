---
name: mapsyncer-server-command-test
description: >-
  Run MapSyncer dedicated-server command matrix tests across Fabric, Forge and
  NeoForge versions with matching C2ME builds and RCON control. Use when testing
  server commands, cache generation, reload/persistence, C2ME compatibility, or
  adding a new server version. Project-scoped to MapSyncer-for-XaeroWorldmap.
---

# MapSyncer 服务端命令矩阵测试

本项目专用。固定测试环境：服务端 `E:\Game\服务器端`，客户端
`E:\Game\pcl\.minecraft\versions`；临时文件、日志和备份放
`cache/tmp/server-command-matrix/<date>/<server>/`。

## 不可妥协的前置条件

1. **每个服务端都安装匹配的 C2ME**：按 Loader + MC 版本选择；启动日志必须出现 `c2me`。
   没有对应构建时记录 `C2ME N/A`，禁止把 Fabric/NeoForge 的 jar 塞进其他 Loader。
2. **每个服务端都启用 RCON**：所有命令通过 RCON 发送，不依赖交互控制台；测试结束恢复
   `server.properties`，确认 `enable-rcon=false`。
3. **TEMP/TMP/TMPDIR 指向仓库内无空格目录**，例如
   `cache/tmp/server-command-matrix/sys-temp`。否则 Gradle 和 Minecraft Netty 都可能报
   `Unable to establish loopback connection`。
4. **一次只启动一个服务端**：确认 25565 和 RCON 端口空闲后再启动下一台。
5. **部署最新构建并记录 SHA256**：不从旧 `mods` 直接测；旧包、旧缓存先备份。
6. **命令不带前导 `/`**：RCON 里发送 `mapsyncer status`，不是 `/mapsyncer status`。
7. **通过结果判据不只看一行 RCON 文本**：结合 `status`、`latest.log`、配置文件、缓存文件。

## 快速流程

```text
[ ] 1. 枚举服务端，生成 Loader/MC 矩阵
[ ] 2. 构建目标版本，部署 MapSyncer
[ ] 3. 安装匹配 C2ME
[ ] 4. 备份 server.properties，启用 RCON
[ ] 5. 准备世界和空缓存基线
[ ] 6. 启动，确认 Done + C2ME + RCON
[ ] 7. 执行 A/B/C/D 命令阶段
[ ] 8. 停止、恢复 RCON、记录结果
[ ] 9. 继续下一台
```

## 1. 发现矩阵

不要假设只有当前已有版本。每次先扫描：

```powershell
Get-ChildItem -LiteralPath 'E:\Game\服务器端' -Directory |
  Where-Object { $_.Name -match '^(fabric|forge|neoforge) ' }
```

对每个目录记录：

- Loader、精确 MC 版本、启动脚本和 JDK。
- `mods/mapsyncer-*.jar`、`mods/*c2me*.jar` 的版本和 SHA256。
- 世界是否存在、region 目录布局、缓存文件数和大小。
- 该组合是否有构建目标；没有就标 N/A，不要用相邻版本冒充。

排序规则：MC 版本组从旧到新；同组按 Fabric → Forge → NeoForge。没有服务端目录或没有
对应构建的组合保留 N/A 行。

## 2. 构建与部署

1. 优先用 `scripts/fastbuild/`：

   ```bat
   cmd /c scripts\fastbuild\build-all.bat
   cmd /c scripts\fastbuild\copy-release-jars.bat
   ```

   单目标用 `build-target.bat <target>`。目标不存在时，按项目约定同时补 `.bat` 和 `.sh`，
   不要引入 `.ps1`。

2. 只部署正式 jar，不部署 `-dev-all`、`-slim`、`-sources`、`-javadoc`。
3. 旧 `mapsyncer-*.jar` 移到备份目录，`mods/` 中只保留一个 MapSyncer jar。
4. 记录部署 jar 的 SHA256。测试期间不再替换。

部署前先读 [references/rcon-and-c2me.md](references/rcon-and-c2me.md) 的 C2ME 部分。

## 3. C2ME 与 RCON

### C2ME

- 选择与 Loader + MC 精确匹配的 C2ME；一个顶层 C2ME jar。
- 启动日志必须出现 `c2me`，且没有 `ResolutionException`、重复类或模块导出冲突。
- MapSyncer 的 lz4 必须重定位到 `com/mapsyncer/net/jpountz/lz4`；否则会与 C2ME 冲突。
- C2ME 在专用服务端可能打印客户端 mixin/Starlight/Sodium 警告；分类为 C2ME 噪声。
- 没有对应 C2ME 构建：结果写 `C2ME N/A`，不要强行安装不兼容 jar。

### RCON

1. 备份 `server.properties`。
2. 设置 `enable-rcon=true`、`rcon.port=25575`、随机 `rcon.password`、
   `broadcast-rcon-to-ops=false`。
3. 启动后等待 `RCON running on ...`。
4. 编译并调用 skill 自带客户端：

   ```bat
   javac -d cache\tmp\rcon .agents\skills\mapsyncer-server-command-test\scripts\RconClient.java
   java -cp cache\tmp\rcon RconClient 127.0.0.1 25575 <password> "mapsyncer status"
   ```

5. 命令全部经 RCON 发送。Fabric/NeoForge 可能返回翻译文本，Forge 可能返回翻译键；
   `generate` 完成回调可能因连接关闭丢失，必须用 `status` 和 `latest.log` 确认完成。
6. 结束后 `save-all flush`、`stop`，恢复 `server.properties`，确认 RCON 端口关闭。

## 4. 命令阶段

`<P>`：Fabric 为 `mapsyncerserver`，Forge/NeoForge 为 `mapsyncer`。

| 阶段 | 必测内容 |
|---|---|
| A 基线 | `<P>`、`<P> help`、`<P> status`、`<P> incremental` |
| B 配置 | `incremental off`、`tick`、`tick 2400`、`tick 72000`、越界 2399/72001、`scheduled`、`scheduled 5`、`scheduled 5 30`、越界 24/60、`incremental off`、`reloadconfig`、`incremental` |
| C 生成 | `generate <dim> 0 0`、无效区域 `999999 999999`、`generate <dim>`、`generate`、生成中重复命令、`generate <dim> --force`、再次 `generate <dim>`、`status`、`reloadconfig`、`status` |
| D 持久化 | `incremental scheduled 5 30`、`save-all flush`、`stop`、重启后 `incremental`/`status`、`incremental off`、`stop` |

完整预期输出、负向用例和结果模板见 [references/command-matrix.md](references/command-matrix.md)。

关键判据：

- `generate` 必须经过 `status` 的“进行中 → 无任务”或日志完成行，不能只看“命令已接受”。
- 单区域缓存可能按配置生成地表 + 洞穴多个 zip，不能只按“+1 文件”判断。
- `--force` 必须清除并重建该维度缓存；记录旧/新时间戳。
- `reloadconfig` 后配置仍保持；重启后模式仍保持。
- 生成任务互斥要在任务确实进行中时验证；小世界太快时用 `--force` 期间重复发命令。

## 5. 结果与清理

每个版本写一个结果块，至少包含：

- Loader/MC、Java、MapSyncer jar + SHA256、C2ME 版本或 N/A、RCON 端口。
- 世界/region 数、缓存基线/结束、启动/停止耗时。
- 每条命令的实际关键响应、证据路径、PASS/FAIL/BLOCKED。
- 缺陷单独列出；Loader 噪声与 MapSyncer 异常分开。

清理：

- 停止服务端，恢复 `server.properties`（`enable-rcon=false`）。
- 旧缓存/旧包保留在 `cache/tmp/.../backup/`。
- 临时世界/缓存移到 `cache/tmp/.../`，不要散落在实例目录。
- 确认 25565/RCON 端口不再监听。

## 6. 常见坑

- **Forge 控制台不读后台 stdin**：用 RCON；RCON 返回翻译键不是失败。
- **Forge 52 打包契约**：`mods.toml` 依赖要 `mandatory=true`；主类要能被无参构造。
- **Forge 1.21.1 `reloadconfig` 后写定时配置**：可能返回 generic error；单独记录并复测。
- **NeoForge + C2ME lz4**：确认 MapSyncer 已重定位 lz4；C2ME 客户端模块警告是噪声。
- **26.x 世界布局**：region 在 `world/dimensions/minecraft/overworld/region`，不要只查旧路径。
- **空维度 ERROR**：nether/end 没有 region 目录时当前实现会记录 ERROR；记录为缺陷，不当作崩溃。
- **定时时间格式**：`%02d` 可能显示为 `4:0`；记录为显示问题。
- **RCON 权限**：RCON 是最高权限，不能证明普通 OP 的权限等级；权限测试另设玩家/权限插件。

## 7. 新增版本

新增 MC/Loader 时：

1. 检查 `gradle/versions.toml` 和现有 fastbuild 脚本。
2. 补构建目标；没有现成组合时同时补 `.bat`/`.sh`。
3. 找匹配的 C2ME；没有就标 N/A。
4. 启动，确认 C2ME、MapSyncer、RCON 都加载。
5. 执行 A/B/C/D 全矩阵；不要把旧版本结果复制成新版本结果。
6. 记录新版本的 jar SHA256、C2ME 版本、RCON 输出风格。

### 26.3 实测基线

- NeoForge 必须使用 `26.3.0.90` 或更新版本；`26.3.0.7-beta` 会与 C2ME 的
  MixinExtras/ASM 组合冲突，启动即 `ClassCastException`。
- NeoForge 26.3 的服务端配置类型是 `ModConfig.Type.SYNCED`，配置文件为
  `config/mapsyncer-synced.toml`。
- Fabric 使用 Loader `0.19.5`；Fabric API 0.161 要求至少 0.19.3，而 0.19.3
  仍会与 C2ME 0.106 的 MixinExtras 冲突。
- C2ME 26.3 基线：Fabric `0.4.2-alpha.0.106`，NeoForge `0.4.2-alpha.0.105`。

详细说明：

- [RCON 与 C2ME 规范](references/rcon-and-c2me.md)
- [完整命令矩阵](references/command-matrix.md)
- [RCON 客户端](scripts/RconClient.java)
