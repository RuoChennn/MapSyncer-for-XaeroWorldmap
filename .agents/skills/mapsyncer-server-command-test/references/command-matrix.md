# 服务端命令矩阵

`<P>`：Fabric = `mapsyncerserver`，Forge / NeoForge = `mapsyncer`。
所有命令通过 RCON 发送，不带前导 `/`。

## A. 基线

| # | RCON 命令 | 预期 |
|---:|---|---|
| A1 | `<P>` | 帮助头 + 10 条命令 |
| A2 | `<P> help` | 与 A1 相同 |
| A3 | `<P> status` | 无任务；当前增量模式；有缓存时显示维度/region/大小 |
| A4 | `<P> incremental` | 当前模式 + 子命令提示 |

## B. 增量配置

| # | RCON 命令 | 预期 |
|---:|---|---|
| B1 | `<P> incremental off` | 已禁用 |
| B2 | `<P> status` | `DISABLED` |
| B3 | `<P> incremental tick` | TICK，使用当前配置间隔；默认 6000，已被改过则沿用当前值 |
| B4 | `<P> status` | TICK + 当前间隔 + 下次更新时间 |
| B5 | `<P> incremental tick 2400` | 2400 ticks = 120 秒 |
| B6 | `<P> status` | 2400 ticks |
| B7 | `<P> incremental tick 72000` | 72000 ticks = 3600 秒 |
| B8 | `<P> status` | 72000 ticks |
| B9 | `<P> incremental tick 2399` | 越界失败，不得改配置 |
| B10 | `<P> incremental tick 72001` | 越界失败 |
| B11 | `<P> incremental scheduled` | SCHEDULED，使用当前/默认时间；默认 04:00 |
| B12 | `<P> status` | SCHEDULED + 时间 |
| B13 | `<P> incremental scheduled 5` | 小时 5，分钟沿用当前值 |
| B14 | `<P> incremental scheduled 5 30` | 每日 05:30 |
| B15 | `<P> incremental scheduled 24` | 越界失败 |
| B16 | `<P> incremental scheduled 5 60` | 越界失败 |
| B17 | `<P> incremental off` | 关闭后台更新，隔离生成测试 |
| B18 | `<P> reloadconfig` | 重载成功 |
| B19 | `<P> incremental` | 仍为关闭，证明配置持久化 |
| B20 | 重载后 `<P> incremental scheduled 5 30` | 写回成功；失败则记录 Loader 缺陷 |

## C. 生成

建议 `<dim>` 用 `overworld`。确认 `0 0` 对应的 region 存在；不存在时先
`forceload add 0 0`、`save-all flush`。

| # | RCON 命令 | 预期 |
|---:|---|---|
| C1 | `<P> status` | 记录缓存基线 |
| C2 | `<P> generate <dim> 0 0` | 开始单区域；日志转换完成；缓存新增/更新 |
| C3 | `<P> generate <dim> 999999 999999` | 区域不存在，且不启动任务 |
| C4 | `<P> generate <dim>` | 单维度增量完成；记录 total/converted/updated |
| C5 | `<P> status` | 任务结束；缓存统计更新 |
| C6 | `<P> generate` | 全量启动；记录维度、区域、跳过/失败 |
| C7 | 生成进行中 `<P> generate <dim>` | 返回任务忙；若小世界太快，改用 `--force` 期间复测 |
| C8 | 等待 C6 完成 | 日志出现全量完成；`status` 无任务 |
| C9 | `<P> status` | 所有有 region 的维度有统计；空维度有跳过记录 |
| C10 | `<P> generate <dim> --force` | 清除该维度缓存并重建 |
| C11 | 检查缓存 | 旧文件被清空/替换，时间戳更新，generation_cache 更新 |
| C12 | `<P> generate <dim>` | 强制后增量；记录 updated，通常为 0 或少量 |
| C13 | `<P> status` | 任务结束，缓存统计正常 |
| C14 | `<P> reloadconfig` | 重载成功，缓存仍在 |
| C15 | `<P> status` | 重载后可读缓存和状态 |

判据：

- 单区域可能生成地表 + 洞穴多个 zip；按配置层数判断，不要只按 +1 文件。
- 完成回调可能丢失；以 `status` + `latest.log` 为准。
- `--force` 是破坏性操作，放在全量之后执行。
- 空 nether/end 目录当前可能记录 ERROR；记录为日志级别缺陷。

## D. 持久化

| # | RCON 命令 | 预期 |
|---:|---|---|
| D1 | `<P> incremental scheduled 5 30` | 设置成功 |
| D2 | `save-all flush` | 保存成功 |
| D3 | `stop` | 正常停止 |
| D4 | 重启后 `<P> incremental` + `status` | SCHEDULED 05:30；无残留任务；缓存仍在 |
| D5 | `<P> incremental off` | 关闭 |
| D6 | `stop` | 正常停止 |

## 结果模板

```markdown
### <顺序>. <Loader> <MC> — PASS / FAIL / BLOCKED

- 服务端目录：
- Java：
- MapSyncer jar / SHA256：
- C2ME 版本 / 文件（或 N/A）：
- RCON 端口 / 是否恢复：
- 世界 / region：
- 缓存基线 / 结束：
- 启动 / 停止耗时：
- 结果：

| # | RCON 命令 | 预期 | 实际关键输出 | 结果 | 证据 |
|---:|---|---|---|---|---|
| A1 | mapsyncer | 帮助 |  |  |  |
```

证据至少保留：

- `logs/latest.log` 副本；
- RCON 响应 / 日志行号 / 终端截图；
- 配置快照（Fabric properties、Forge/NeoForge toml）；
- 缓存文件数、维度、总大小。

## 通过标准

PASS 必须同时满足：

1. 启动成功，加载指定 MapSyncer 和匹配 C2ME；没有 MapSyncer 异常/模块冲突。
2. RCON 正常监听，所有命令经 RCON 执行；结束后 RCON 关闭。
3. A/B/C/D 必测命令完成，成功/失败符合预期。
4. `status` 能观察生成开始、进行中、完成。
5. 单区域/单维度/强制生成有真实缓存副作用。
6. 配置写入、`reloadconfig`、跨重启持久化正确。
7. 停止后无 25565/RCON 端口残留。

FAIL：

- 有 C2ME 构建但未加载，或 C2ME 与 MapSyncer 冲突。
- RCON 不可用，或测试后仍开启。
- 参数越界被接受、任务互斥失效、配置回退。
- `--force` 没清缓存或没重建。

BLOCKED：

- 服务端无法启动、jar 无法部署、世界无法准备、端口被占用。
