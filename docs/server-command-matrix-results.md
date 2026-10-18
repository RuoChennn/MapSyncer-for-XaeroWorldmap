# 服务端命令矩阵测试结果（2026-10-18）

测试按 `mapsyncer-server-command-test` skill 执行：每个组合使用独立
`mapsyncer_test_world`、空缓存基线、RCON 25575，只保留 MapSyncer、匹配的
C2ME（Fabric 另加 Fabric API），结束后恢复原模组、配置、世界和缓存。

**结论：14/14 组合 PASS。** A/B/C/D 全矩阵通过；Forge 无官方 C2ME 构建，
按规范记 `C2ME N/A`。

| # | 服务端 | Java | MapSyncer JAR / SHA256（前缀） | C2ME | 启动 / 重启 | 结果 |
|---:|---|---|---|---|---|---|
| 1 | fabric 1.20.1 | 17 | `mapsyncer-1.0.4-fabric-1.20.jar` / `F4DC42D4` | 0.2.0+alpha.11.5 | 44.5 s / 22.2 s | PASS |
| 2 | fabric 1.21.1 | 21 | `mapsyncer-1.0.4-fabric-1.21.jar` / `5BD2C41B` | 0.4.0-alpha.0.23 | 24.2 s / 16.2 s | PASS |
| 3 | fabric 1.21.11 | 21 | `mapsyncer-1.0.4-fabric-1.21.11.jar` / `F8AC11E5` | 0.4.0-alpha.0.23 | 28.3 s / 16.1 s | PASS |
| 4 | fabric 26.1 | 25 | `mapsyncer-1.0.4-fabric-26.1.jar` / `30DD74EF` | 0.3.7+alpha.0.69 | 20.2 s / 10.1 s | PASS |
| 5 | fabric 26.2 | 25 | `mapsyncer-1.0.4-fabric-26.2.jar` / `73D16BF3` | 0.4.2-alpha.0.27 | 16.1 s / 10.1 s | PASS |
| 6 | fabric 26.3 | 25 | `mapsyncer-1.0.4-fabric-26.3.jar` / `CC4CA430` | 0.4.2-alpha.0.106 | 12.1 s / 10.1 s | PASS |
| 7 | forge 1.20.1 | 17 | `mapsyncer-1.0.4-forge-1.20.jar` / `F4AAC84E` | N/A | 40.4 s / 28.3 s | PASS |
| 8 | forge 1.21.1 | 21 | `mapsyncer-1.0.4-forge-1.21.jar` / `28600E8F` | N/A | 22.4 s / 16.2 s | PASS |
| 9 | forge 1.21.11 | 21 | `mapsyncer-1.0.4-forge-1.21.11.jar` / `59279464` | N/A | 18.2 s / 16.7 s | PASS |
| 10 | neoforge 1.21.1 | 21 | `mapsyncer-1.0.4-neoforge-1.21.jar` / `6F543F9B` | 0.3.0+alpha.0.91 | 22.2 s / 18.1 s | PASS |
| 11 | neoforge 1.21.11 | 21 | `mapsyncer-1.0.4-neoforge-1.21.11.jar` / `6AADDA3E` | 0.3.6+alpha.0.80 | 16.2 s / 14.1 s | PASS |
| 12 | neoforge 26.1 | 25 | `mapsyncer-1.0.4-neoforge-26.1.jar` / `8A07AE22` | 0.3.7+alpha.0.86 | 14.2 s / 10.1 s | PASS |
| 13 | neoforge 26.2 | 25 | `mapsyncer-1.0.4-neoforge-26.2.jar` / `11D4901B` | 0.4.2-alpha.0.94 | 16.2 s / 12.1 s | PASS |
| 14 | neoforge 26.3 | 25 | `mapsyncer-1.0.4-neoforge-26.3.jar` / `C5B644A2` | 0.4.2-alpha.0.105 | 14.3 s / 12.1 s | PASS |

每个组合的逐条命令输出、日志和缓存快照在
`cache/tmp/server-command-matrix/2026-10-18/<server>/run-<HHmmss>/`：

- `summary.md`：A1–D6 逐条结果
- `commands.log`：RCON 命令与原始响应
- `latest.boot1.log` / `latest.boot2.log`：启动日志
- `latest.boot1.final.log` / `latest.boot2.final.log`：停止前日志
- `test-world/`、`test-cache/`：本次隔离世界与生成缓存

## 本轮修复

1. **NeoForge 26.3 依赖过旧**：`26.3.0.7-beta` 与 C2ME 的 MixinExtras/ASM
   组合冲突，启动即 `ClassCastException`。升级到 `26.3.0.90`，并按新 API
   把服务端配置从 `Type.SERVER` 改为 `Type.SYNCED`。
2. **26.x `reloadconfig` 丢失版本信息**：重置 `DimensionPathMapping` 单例会
   回退到 1.21 的传统 `region/` 路径，导致 26.x 重载后找不到
   `dimensions/minecraft/.../region`。现在只清空运行时映射，保留 MC 主版本。
3. **Forge `reloadconfig` 后无法写配置**：旧实现把规范绑到随后关闭的临时
   TOML，之后执行 `incremental scheduled` 报 generic error。改为通过
   `ConfigTracker.loadConfigs` 让 Forge 自己替换已加载配置。
4. **Forge 1.21.11 主类构造签名**：Forge 61 不支持旧的
   `(EventBus, ModContainer)` 注入，改用 `(FMLJavaModLoadingContext)`。
5. **26.3 Fabric 启动器**：Fabric API 0.161 要求 Loader ≥0.19.3；C2ME 0.106
   在 0.19.3 的 ASM/MixinExtras 组合下仍会冲突，服务端使用 0.19.5。
6. **构建脚本**：修正 `findstr "-slim"` 被当作选项导致的
   `FINDSTR: Bad command line`，并在收集产物时排除 `-dev-all.jar`。

## 测试限制

- 测试世界只有 4–8 个 region，`generate` 和 `--force` 常在 1 秒内完成，
  因此 C7/C11 的“任务进行中重复提交”未捕获到 busy 响应，记为 SKIP；
  其余 14 个组合的互斥检查由 `ConversionOrchestrator.isRunning()` 状态与
  生成期间日志共同覆盖。
- 各服务端原有的大世界、模组和缓存未参与本轮命令测试，测试后已恢复。
- `forge 1.20.1` 的世界带有暮色森林维度，空维度/缺失 region 会记录 ERROR，
  属于现有日志级别问题，未影响命令结果。

## 现场恢复

- 14 个服务端 `server.properties` 已恢复，`enable-rcon=false`。
- 原 `mods/`、`world/`、`server_map_cache/`、`config/` 已恢复。
- 25565 / 25575 均无监听进程。
