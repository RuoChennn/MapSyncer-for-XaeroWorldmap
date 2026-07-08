# 双向 Xaero 地图同步设计

## 背景

当前 MapSyncer 以服务端缓存为权威来源：客户端上报本地 region 元数据，服务端对比 `generation_cache.properties` 后下发较新的 region。该机制已经支持哈希比对、时间戳比对、断点续传、分片传输和客户端 `sync_timestamps.cache`。本设计在此基础上扩展为“先分发、后贡献”的双向增量同步，使服务端持续吸收可信客户端的新地图数据，并继续作为所有客户端的权威分发源。

## 目标

- 支持客户端选择禁用同步、仅接收服务端地图、或参与双向同步。
- 支持服务端配置贡献来源范围，并通过 UUID 白名单控制可信贡献者。
- 同步只处理增量 region，单个 region 的变化不扩大为全量同步。
- 判新使用逻辑时间戳和哈希，传输写入时间不得成为权威时间。
- 多名玩家同时贡献时排队处理，并在贡献任务之间加入冷却期，避免服务端缓存写竞争。

## 非目标

- 第一版不做 Xaero region 内 chunk 级精细合并。
- 第一版不做贡献审计、回滚历史和可视化管理界面。
- 第一版不改变现有服务端 MCA 生成缓存流程。

## 配置设计

现有配置注释风格为英文说明加中文说明的双语格式。新增配置也应遵循该风格，并写清配置含义、影响范围、默认值原因和候选值语义，而不是只列枚举值。

客户端配置新增：

```properties
# Client sync mode.
# 客户端同步模式。
# DISABLED: Disable all automatic, background, manual receive, and upload sync actions on this client.
# DISABLED：禁用此客户端的自动同步、后台巡检、手动接收同步和上传贡献。
# RECEIVE_ONLY: Receive newer authoritative regions from the server, but never upload local regions.
# RECEIVE_ONLY：只接收服务端较新的权威 region，不上传本地 region。
# BIDIRECTIONAL: Receive server updates and upload newer local regions when the server allows contributions.
# BIDIRECTIONAL：接收服务端更新，并在服务端允许时上传本地较新的 region。
clientSyncMode=RECEIVE_ONLY

# Background metadata check interval in minutes.
# 后台元数据巡检间隔（分钟）。
# 0 disables periodic checks. Positive values periodically run the same metadata negotiation as manual sync.
# 0 表示关闭周期巡检；正数表示周期执行与手动同步相同的元数据协商流程。
backgroundSyncIntervalMinutes=60
```

服务端配置新增：

```properties
# Client contribution permission scope.
# 客户端贡献权限范围。
# DISABLED: Reject all client uploads.
# DISABLED：拒绝所有客户端上传。
# OPS: Only server operators may contribute.
# OPS：仅服务器 OP 可贡献。
# WHITELIST: Only UUIDs listed in mapsyncer-contributors.json may contribute.
# WHITELIST：仅 mapsyncer-contributors.json 中列出的 UUID 可贡献。
# OPS_AND_WHITELIST: Operators and whitelisted UUIDs may contribute.
# OPS_AND_WHITELIST：OP 与白名单 UUID 均可贡献。
# ALL: Any player may contribute after validation.
# ALL：所有玩家均可在校验通过后贡献。
contributionScope=WHITELIST

# Cooldown between accepted contribution jobs, in seconds.
# 已接受贡献任务之间的冷却期（秒）。
# This prevents multiple players from writing server_map_cache concurrently in a short burst.
# 用于避免多名玩家短时间内并发写入 server_map_cache。
contributionQueueCooldownSeconds=10

# Maximum number of queued contribution jobs.
# 最大贡献任务排队数量。
# New bidirectional contribution phases are rejected when the queue is full; normal receive-only sync can still proceed.
# 队列满时拒绝新的双向贡献阶段；普通只接收同步仍可继续。
maxContributionQueueSize=32
```

白名单文件独立于 TOML/properties。服务端首次启动或首次读取贡献权限时创建：

```json
{
  "allowedContributors": []
}
```

建议路径：`<world>/serverconfig/mapsyncer-contributors.json`。仅记录 UUID 字符串，避免玩家改名导致权限失效。

## 协议与数据流

所有入口共用同一条状态机：进服自动同步、后台巡检、手动 `/mapsyncer sync`。后台巡检只先发送元数据，不直接传输 region 数据。

1. 客户端根据 `clientSyncMode` 判断是否允许发起同步。`DISABLED` 直接提示并退出。
2. 客户端扫描目标目录，发送 `region -> timestamp/hash` 元数据。
3. 服务端先执行分发阶段：找出服务端较新的 region，并下发给客户端。
4. 客户端写入分发数据，同时用服务端逻辑 timestamp 更新 `sync_timestamps.cache`。
5. 如果客户端为 `BIDIRECTIONAL` 且服务端贡献范围允许该玩家，服务端创建贡献任务并进入全局贡献队列。
6. 贡献任务开始时，服务端请求客户端上传仍然比服务端新的候选 region。
7. 客户端上传前二次读取文件，重新计算 hash/timestamp，只上传仍然有效且仍然较新的 region。
8. 服务端接收后再次校验路径、维度、zip 结构、payload hash、声明 timestamp 与当前服务端 meta。
9. 服务端写入临时文件，原子替换目标 region，更新 `generation_cache.properties`。

## 判新规则

- 哈希相同永远跳过，不上传、不覆盖。
- 服务端权威时间来自 `generation_cache.properties`。
- 客户端接收服务端 region 后，写入 `sync_timestamps.cache` 的时间必须是服务端逻辑 timestamp。
- 文件修改时间只能作为“本地未知数据”的候选时间，不能覆盖服务端逻辑时间。
- 传输写入时间不得成为权威时间。
- 同步过程中如果二次握手发现某个 region 的 hash/timestamp 变化，只影响该 region：
  - 文件消失、hash 无效或 zip 不合法：跳过该 region。
  - 仍比服务端新：上传当前最新版，并携带当前 meta。
  - 不再比服务端新：跳过，等待下一轮巡检收敛。

## 排队与冷却

服务端新增全局贡献协调器。分发阶段是读缓存操作，可以继续按现有 per-player 同步并发模型执行；贡献阶段会写 `server_map_cache` 和 `generation_cache.properties`，必须串行化。

贡献任务按到达顺序排队。一个任务包括某玩家一次同步中的贡献候选集合。任一时刻最多执行一个贡献任务；任务结束后等待 `contributionQueueCooldownSeconds`，再处理下一个任务。若队列长度达到 `maxContributionQueueSize`，新的贡献任务被拒绝，但该玩家仍可完成服务端到客户端的分发同步。

执行贡献任务时，服务端在每个 region 写入前重新读取当前 `generation_cache.properties` 中的 meta。即使队列中较早任务已经更新了某个 region，后续任务也会按最新服务端 meta 再判定，旧数据不会覆盖新数据。

## 组件边界

- `ClientSyncMode`：客户端三态同步模式枚举。
- `ContributionScope`：服务端贡献范围枚举。
- `ContributionWhitelist`：读取和创建 `mapsyncer-contributors.json`，只负责 UUID 集合。
- `ContributionCoordinator`：服务端全局贡献队列、冷却、任务串行化。
- `ContributionValidator`：服务端上传校验，包括路径、维度、zip、hash、timestamp。
- `ClientContributionCollector`：客户端按服务端请求读取候选 region，并在上传前二次校验。
- 新增 payload：贡献请求、贡献数据、贡献结果。payload DTO 放在 `libs/platform-api`，平台注册和序列化由各加载器薄适配实现。

## 错误处理

- 客户端禁用同步：手动命令给出本地提示，后台任务不启动。
- 服务端贡献禁用或权限不足：分发照常执行，贡献阶段跳过。
- 队列满：贡献阶段返回可读状态，不影响已完成的分发。
- 单个 region 校验失败：只拒绝该 region，不终止整批任务。
- 玩家断线：取消该玩家未开始的贡献任务；正在执行的任务在下一个 region 边界中止。
- 服务端停止：清空队列，关闭贡献协调器线程。

## 测试策略

- 单元测试覆盖逻辑判新：hash 相同、服务端新、客户端新、timestamp 被传输写入污染、二次握手变化。
- 单元测试覆盖贡献范围：`DISABLED`、`OPS`、`WHITELIST`、`OPS_AND_WHITELIST`、`ALL`。
- 单元测试覆盖白名单 JSON：首次创建空文件、读取 UUID、忽略无效 UUID。
- 集成/烟测覆盖三入口：进服自动、后台巡检、手动命令。
- 并发测试覆盖两个玩家同时贡献同一 region：先执行者写入后，后执行者必须重新判新并跳过旧数据。

## 实施顺序建议

1. 增加枚举、配置项、Platform getter 和 Fabric GUI 配置项。
2. 增加白名单 JSON 管理器。
3. 增加贡献协议 DTO 与各平台注册/序列化。
4. 增加服务端贡献队列与校验器。
5. 增加客户端贡献收集与上传前二次校验。
6. 将现有同步流程改为“先分发，再贡献”。
7. 增加后台元数据巡检，并接入 `clientSyncMode`。
8. 更新文档与测试记录。
