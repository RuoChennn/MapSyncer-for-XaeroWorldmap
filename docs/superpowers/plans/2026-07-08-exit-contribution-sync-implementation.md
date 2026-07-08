# Exit Contribution Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a normal-exit safeguard that contributes local Xaero map data before disconnecting, while fixing the existing hash/timestamp metadata robustness issue.

**Architecture:** Keep ordinary `/mapsyncer sync` unchanged. Add a contribution-only request path that produces server contribution candidates without sending server map data back to the client, and drive it from a client-side pre-disconnect state machine opened by pause-menu interception. Reuse the existing `ContributionCoordinator`, `ClientContributionCollector`, whitelist check, queued execution, and contribution result payload flow for every contribution-only path.

**Tech Stack:** Java 17/21/25 toolchains, Minecraft client/server APIs, Fabric/Forge/NeoForge networking adapters, Mixin for pause-menu interception, JUnit 5 tests, Gradle module builds. Shared source must avoid APIs and language syntax unavailable to the lowest target module that consumes it.

## Mandatory Review Amendments

The following amendments supersede the first-draft snippets below wherever they differ:

- `ContributionOnlyRequestPayload` must support the same metadata fragmentation pattern as `SyncRequestPayload`: `partIndex`, `totalParts`, `split(requestId, meta, reason)`, and server-side per-player assembly before candidate comparison.
- Task 2 must extend both `NetworkHandler` and `NetworkManager`; later code must use `PayloadContext` or the real handler APIs for player lookup and work scheduling, not invented `NetworkManager` static helpers.
- Contribution-only sessions must preserve the request id supplied by the client. Add an overload such as `ContributionCoordinator.enqueueSession(player, candidates, requestId)` for this path; ordinary sync keeps the existing generated id path.
- `PreDisconnectContributionManager` may disconnect only on terminal result statuses (`done`, `timeout`, `queue_full`, `no_candidates`, `not_allowed`, `inactive_request`, `wrong_player`, `permission_changed`, `write_failed`). Intermediate statuses such as per-region `accepted` must update UI only.
- Candidate collection must run off the server thread. Main-thread work is limited to player/context validation and final enqueue/send calls.
- Do not replace ordinary `/mapsyncer sync` semantics with a broad new full-cache helper. Extract only the existing contribution-candidate logic while preserving requested-dimension filtering, skipped-dimension behavior, visited server paths, and existing `regionsToSync` construction.
- Fabric 1.20.1 uses the legacy `ResourceLocation` + `FriendlyByteBuf` networking style; Fabric 1.21+ uses `CustomPacketPayload` / `RegistryFriendlyByteBuf`.
- Add client contribution-in-progress tracking so pre-disconnect sync does not overlap with the contribution phase of an ordinary sync.
- `Cancel disconnect` means returning to the game. If a contribution-only session has already been queued, the UI text must make clear that the queued contribution may continue; implementing true server-side cancellation is out of scope for this plan.

---

## File Structure

- Modify: `libs/common/src/main/java/com/mapsyncer/client/ClientHashManager.java` — hash-aware timestamp selection.
- Test: `mc-1.21.1/fabric/src/test/java/com/mapsyncer/client/ClientHashManagerTest.java` — regression tests for timestamp source selection.
- Create: `libs/platform-api/src/main/java/com/mapsyncer/network/payload/ContributionOnlyRequestPayload.java` — client-to-server request for contribution candidates only.
- Modify: `libs/platform-api/src/main/java/com/mapsyncer/network/NetworkHandler.java` — new send/register methods and payload ID.
- Modify: `libs/platform-api/src/main/java/com/mapsyncer/network/NetworkManager.java` — static send/register helpers for the new payload.
- Modify: `libs/platform-api/src/test/java/com/mapsyncer/network/ContributionPayloadContractTest.java` — DTO and handler contract coverage.
- Modify platform adapters in:
  - `mc-1.20.1/fabric/src/main/java/com/mapsyncer/network/FabricPayloadAdapters.java`
  - `mc-1.21.1/fabric/src/main/java/com/mapsyncer/network/FabricPayloadAdapters.java`
  - `mc-1.21.11/fabric/src/main/java/com/mapsyncer/network/FabricPayloadAdapters.java`
  - `mc-26.1/fabric/src/main/java/com/mapsyncer/network/FabricPayloadAdapters.java`
  - `mc-1.20.1/forge/src/main/java/com/mapsyncer/network/ForgePayloadAdapters.java`
  - `mc-1.21.1/forge/src/main/java/com/mapsyncer/network/ForgePayloadAdapters.java`
  - `mc-1.21.11/forge/src/main/java/com/mapsyncer/network/ForgePayloadAdapters.java`
  - `mc-1.21.1/neoforge/src/main/java/com/mapsyncer/network/NeoForgePayloadAdapters.java`
  - `mc-1.21.11/neoforge/src/main/java/com/mapsyncer/network/NeoForgePayloadAdapters.java`
  - `mc-26.1/neoforge/src/main/java/com/mapsyncer/network/NeoForgePayloadAdapters.java`
- Modify platform network handlers in every Fabric/Forge/NeoForge module — register and send `ContributionOnlyRequestPayload`.
- Modify: every `mc-*/shared/src/main/java/com/mapsyncer/server/ServerSyncHandlerLogic.java` — register and handle contribution-only request.
- Modify: every `mc-*/shared/src/main/java/com/mapsyncer/client/MapPacketHandler.java` — notify pre-disconnect manager when contribution result arrives.
- Create: `libs/common/src/main/java/com/mapsyncer/client/PreDisconnectContributionManager.java` — shared state machine.
- Create per-version client UI class: `mc-*/shared/src/main/java/com/mapsyncer/client/PreDisconnectSyncScreen.java` — waiting screen.
- Modify platform config classes to add `syncBeforeDisconnect` and `disconnectSyncTimeoutSeconds`.
- Modify language files:
  - `libs/common/src/main/resources/assets/mapsyncer/lang/en_us.json`
  - `libs/common/src/main/resources/assets/mapsyncer/lang/zh_cn.json`
- Add Mixin config and pause-screen mixins in each loader/version resource/source set.
- Update: `docs/features.md` and `docs/test-notes.md`.

---

### Task 1: Fix Client Metadata Timestamp Robustness

**Files:**
- Modify: `libs/common/src/main/java/com/mapsyncer/client/ClientHashManager.java:183-194`
- Test: `mc-1.21.1/fabric/src/test/java/com/mapsyncer/client/ClientHashManagerTest.java`

- [x] **Step 1: Write failing regression tests**

Create `ClientHashManagerTest.java`:

```java
package com.mapsyncer.client;

import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.util.HashUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientHashManagerTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void resetCache() {
        ClientTimestampCache.resetInstance();
        ClientHashManager.shutdown();
    }

    @Test
    void usesCachedTimestampOnlyWhenCachedHashMatchesCurrentHash() throws Exception {
        Path serverDir = tempDir.resolve("Multiplayer_test");
        Path region = serverDir.resolve("null").resolve("mw$0").resolve("1_2.zip");
        Files.createDirectories(region.getParent());
        byte[] currentData = "current-local-region".getBytes();
        Files.write(region, currentData);
        Files.setLastModifiedTime(region, FileTime.fromMillis(300_000));

        ClientTimestampCache cache = ClientTimestampCache.getInstance(serverDir);
        cache.update("null/1_2", 100, "11111111");
        cache.save();

        Map<String, ClientMeta> meta = ClientHashManager.computeMetaForSync(serverDir);

        assertEquals(HashUtils.computeHash(currentData), meta.get("null/1_2").hash());
        assertEquals(300, meta.get("null/1_2").timestampSeconds());
    }

    @Test
    void keepsCachedLogicalTimestampWhenHashStillMatches() throws Exception {
        Path serverDir = tempDir.resolve("Multiplayer_test");
        Path region = serverDir.resolve("null").resolve("mw$0").resolve("3_4.zip");
        Files.createDirectories(region.getParent());
        byte[] data = "server-downloaded-region".getBytes();
        Files.write(region, data);
        Files.setLastModifiedTime(region, FileTime.fromMillis(999_000));

        String hash = HashUtils.computeHash(data);
        ClientTimestampCache cache = ClientTimestampCache.getInstance(serverDir);
        cache.update("null/3_4", 200, hash);
        cache.save();

        Map<String, ClientMeta> meta = ClientHashManager.computeMetaForSync(serverDir);

        assertEquals(hash, meta.get("null/3_4").hash());
        assertEquals(200, meta.get("null/3_4").timestampSeconds());
    }
}
```

- [x] **Step 2: Run the focused test and verify RED**

Run:

`.\gradlew :mc-1.21.1:fabric:test --tests com.mapsyncer.client.ClientHashManagerTest`

Expected before implementation: first test fails because cached timestamp `100` is used despite hash mismatch. If dependency resolution prevents the test task from running in this local environment, run `.\gradlew :mc-1.21.1:fabric:compileTestJava` as a compile-only fallback and record that RED execution was blocked.

- [x] **Step 3: Implement hash-aware cached timestamp selection**

Change the timestamp block in `ClientHashManager.computeMetaForSync()` to:

```java
TimestampHashEntry cached = cachedTimestamps.get(relativePath);
long timestampSeconds;
if (cached != null && cached.hash().equals(hash)) {
    timestampSeconds = cached.timestampSeconds();
    LOGGER.debug("Region {}: using cached ts={}s, hash={}",
            relativePath, timestampSeconds, hash);
} else {
    long timestampMillis = getFileModificationTime(zipPath);
    timestampSeconds = timestampMillis / 1000;
    LOGGER.debug("Region {}: using file ts={}s, hash={} (cache missing or hash changed)",
            relativePath, timestampSeconds, hash);
}
```

- [x] **Step 4: Verify GREEN**

Run: `.\gradlew :mc-1.21.1:fabric:compileTestJava`

If dependency resolution permits, also run:

`.\gradlew :mc-1.21.1:fabric:test --tests com.mapsyncer.client.ClientHashManagerTest`

Preferred GREEN verification is the `test` command. Use `compileTestJava` only when the test runtime is blocked by dependency resolution, and record the limitation in the task summary.

- [x] **Step 5: Commit**

```bash
git add libs/common/src/main/java/com/mapsyncer/client/ClientHashManager.java mc-1.21.1/fabric/src/test/java/com/mapsyncer/client/ClientHashManagerTest.java
git commit -m "fix: 修复客户端元数据时间戳判新" -m "仅当缓存哈希与当前文件哈希一致时复用逻辑时间戳，否则回退到文件修改时间，避免本地内容变化后仍沿用旧同步时间。"
```

---

### Task 2: Add Contribution-Only Network Contract

**Files:**
- Create: `libs/platform-api/src/main/java/com/mapsyncer/network/payload/ContributionOnlyRequestPayload.java`
- Modify: `libs/platform-api/src/main/java/com/mapsyncer/network/NetworkHandler.java`
- Modify: `libs/platform-api/src/main/java/com/mapsyncer/network/NetworkManager.java`
- Modify: `libs/platform-api/src/test/java/com/mapsyncer/network/ContributionPayloadContractTest.java`

- [x] **Step 1: Add failing payload contract test**

Append to `ContributionPayloadContractTest`:

```java
@Test
void contributionOnlyRequestHasStableIdAndMetadata() {
    Map<String, ClientMeta> meta = Map.of(
            "null/1_2", new ClientMeta(300, "1234abcd")
    );

    ContributionOnlyRequestPayload payload = new ContributionOnlyRequestPayload(55, 0, 1, meta, "pre_disconnect");

    assertEquals(NetworkHandler.CONTRIBUTION_ONLY_REQUEST_ID, ContributionOnlyRequestPayload.ID);
    assertEquals(55, payload.requestId());
    assertEquals(0, payload.partIndex());
    assertEquals(1, payload.totalParts());
    assertEquals("pre_disconnect", payload.reason());
    assertEquals(300, payload.clientMeta().get("null/1_2").timestampSeconds());
}
```

Add imports:

```java
import com.mapsyncer.network.payload.ContributionOnlyRequestPayload;
import java.util.Map;
```

- [x] **Step 2: Run platform-api tests and verify RED**

Run: `.\gradlew :libs:platform-api:test`

Expected: compile fails because `ContributionOnlyRequestPayload` and `CONTRIBUTION_ONLY_REQUEST_ID` do not exist.

- [x] **Step 3: Create payload DTO**

Create `ContributionOnlyRequestPayload.java`:

```java
package com.mapsyncer.network.payload;

import com.mapsyncer.network.NetworkHandler;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public record ContributionOnlyRequestPayload(
        int requestId,
        int partIndex,
        int totalParts,
        Map<String, ClientMeta> clientMeta,
        String reason
) {
    public static final String ID = NetworkHandler.CONTRIBUTION_ONLY_REQUEST_ID;
    public static final int MAX_PAYLOAD_BYTES = SyncRequestPayload.MAX_PAYLOAD_BYTES;

    public ContributionOnlyRequestPayload {
        if (partIndex < 0) {
            throw new IllegalArgumentException("partIndex must be non-negative");
        }
        if (totalParts < 1) {
            throw new IllegalArgumentException("totalParts must be positive");
        }
        if (partIndex >= totalParts) {
            throw new IllegalArgumentException("partIndex must be less than totalParts");
        }
        clientMeta = clientMeta == null ? Map.of() : Collections.unmodifiableMap(clientMeta);
        reason = reason == null ? "" : reason;
    }

    public static List<ContributionOnlyRequestPayload> split(
            int requestId,
            Map<String, ClientMeta> clientMeta,
            String reason
    ) {
        return SyncRequestPayload.split(requestId, clientMeta).stream()
                .map(part -> new ContributionOnlyRequestPayload(
                        requestId,
                        part.partIndex(),
                        part.totalParts(),
                        part.clientMeta(),
                        reason
                ))
                .toList();
    }
}
```

- [x] **Step 4: Extend NetworkHandler**

Add import:

```java
import com.mapsyncer.network.payload.ContributionOnlyRequestPayload;
```

Add ID:

```java
String CONTRIBUTION_ONLY_REQUEST_ID = "contribution_only_request";
```

Add methods:

```java
void sendToServer(ContributionOnlyRequestPayload payload);

void registerContributionOnlyRequestHandler(BiConsumer<ContributionOnlyRequestPayload, PayloadContext> handler);
```

Extend `NetworkManager` with matching static helpers:

```java
public static void sendToServer(ContributionOnlyRequestPayload payload) {
    getHandler().sendToServer(payload);
}

public static void registerContributionOnlyRequestHandler(
        BiConsumer<ContributionOnlyRequestPayload, PayloadContext> handler
) {
    getHandler().registerContributionOnlyRequestHandler(handler);
}
```

Update `ContributionPayloadContractTest.FakeNetworkHandler` to store the sent `ContributionOnlyRequestPayload` and registered handler, then assert both static `NetworkManager` helpers delegate correctly.

- [x] **Step 5: Run platform-api tests**

Run: `.\gradlew :libs:platform-api:test`

Expected: tests pass.

- [x] **Step 6: Commit**

```bash
git add libs/platform-api/src/main/java/com/mapsyncer/network/NetworkHandler.java libs/platform-api/src/main/java/com/mapsyncer/network/NetworkManager.java libs/platform-api/src/main/java/com/mapsyncer/network/payload/ContributionOnlyRequestPayload.java libs/platform-api/src/test/java/com/mapsyncer/network/ContributionPayloadContractTest.java
git commit -m "feat: 添加退出前贡献请求协议" -m "新增 ContributionOnlyRequestPayload，用于客户端退出前只请求服务端贡献候选，不触发服务端地图分发。"
```

---

### Task 3: Wire Contribution-Only Payload Through Platform Networks

**Files:**
- Modify every `FabricPayloadAdapters.java`, `ForgePayloadAdapters.java`, and `NeoForgePayloadAdapters.java`.
- Modify every `FabricNetworkHandler.java`, `ForgeNetworkHandler.java`, and `NeoForgeNetworkHandler.java`.

- [ ] **Step 1: Add adapter wrappers/codecs**

For Fabric 1.21+ adapters, add wrapper:

```java
public record ContributionOnlyRequestWrapper(ContributionOnlyRequestPayload payload) implements CustomPacketPayload {
    public static final Type<ContributionOnlyRequestWrapper> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("mapsyncer", ContributionOnlyRequestPayload.ID));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
```

Add codec methods with the same `Map<String, ClientMeta>` encoding used by `SyncRequestPayload`:

```java
private static void writeContributionOnlyRequest(RegistryFriendlyByteBuf buf, ContributionOnlyRequestPayload payload) {
    buf.writeInt(payload.requestId());
    buf.writeInt(payload.partIndex());
    buf.writeInt(payload.totalParts());
    buf.writeInt(payload.clientMeta().size());
    payload.clientMeta().forEach((path, meta) -> {
        buf.writeUtf(path);
        buf.writeLong(meta.timestampSeconds());
        buf.writeUtf(meta.hash());
    });
    buf.writeUtf(payload.reason());
}

private static ContributionOnlyRequestPayload readContributionOnlyRequest(RegistryFriendlyByteBuf buf) {
    int requestId = buf.readInt();
    int partIndex = buf.readInt();
    int totalParts = buf.readInt();
    int size = buf.readInt();
    Map<String, ClientMeta> meta = new HashMap<>();
    for (int i = 0; i < size; i++) {
        meta.put(buf.readUtf(), new ClientMeta(buf.readLong(), buf.readUtf()));
    }
    return new ContributionOnlyRequestPayload(requestId, partIndex, totalParts, meta, buf.readUtf());
}
```

For Fabric 1.20.1, do not use `CustomPacketPayload`. Add a legacy channel constant, `writeContributionOnlyRequest(FriendlyByteBuf, ContributionOnlyRequestPayload)`, and `readContributionOnlyRequest(FriendlyByteBuf)` using the same field order. Client sending goes through the existing 1.20.1 `FabricClientNetworkHandler.sendToServer(...)` pattern.

For Forge adapters, add `ForgeContributionOnlyRequestMessage` with encode/decode methods using this field order: `requestId`, `partIndex`, `totalParts`, map size, repeated `path/timestamp/hash`, then `reason`. Add it after the existing message id `7` as stable id `8`.

For NeoForge adapters, add `NeoForgeContributionOnlyRequestPayload` with a `StreamCodec` using the same field order.

- [ ] **Step 2: Add NetworkHandler state and registration**

In every platform network handler, add:

```java
private BiConsumer<ContributionOnlyRequestPayload, PayloadContext> contributionOnlyRequestHandler;
```

Register the C2S receiver in the same registration block as `ContributionDataPayload`. In Forge and NeoForge handlers, add the player UUID to the existing confirmed-player set before dispatching to `contributionOnlyRequestHandler`, otherwise later S2C contribution request/result packets may be dropped by the confirmed-player gate.

Add Fabric send/register methods:

```java
@Override
public void sendToServer(ContributionOnlyRequestPayload payload) {
    ClientPlayNetworking.send(new FabricPayloadAdapters.ContributionOnlyRequestWrapper(payload));
}

@Override
public void registerContributionOnlyRequestHandler(
        BiConsumer<ContributionOnlyRequestPayload, PayloadContext> handler) {
    this.contributionOnlyRequestHandler = handler;
}
```

Add NeoForge send method:

```java
@Override
public void sendToServer(ContributionOnlyRequestPayload payload) {
    PacketDistributor.sendToServer(
            new NeoForgePayloadAdapters.NeoForgeContributionOnlyRequestPayload(payload));
}
```

Add Forge send method:

```java
@Override
public void sendToServer(ContributionOnlyRequestPayload payload) {
    CHANNEL.sendToServer(new ForgePayloadAdapters.ForgeContributionOnlyRequestMessage(payload));
}
```

In NeoForge `registrar.playToServer`, confirm the sender UUID before invoking the handler. In Forge message consumers, confirm the sender UUID before invoking the handler.

- [ ] **Step 3: Compile representative platform**

Run:

`.\gradlew :mc-1.21.1:fabric:compileJava :mc-1.21.1:neoforge:compileJava :mc-1.21.11:neoforge:compileJava`

Expected: compile succeeds. If adapter compilation fails, fix exact wrapper names and imports before proceeding.

- [ ] **Step 4: Commit**

```bash
git add mc-1.20.1 mc-1.21.1 mc-1.21.11 mc-26.1
git commit -m "feat: 接入退出前贡献请求网络适配" -m "为 Fabric、Forge、NeoForge 网络层注册 ContributionOnlyRequestPayload，并暴露客户端发送和服务端处理接口。"
```

---

### Task 4: Implement Server Contribution-Only Handler

**Files:**
- Modify every `mc-*/shared/src/main/java/com/mapsyncer/server/ServerSyncHandlerLogic.java`
- Modify: `libs/common/src/main/java/com/mapsyncer/server/ContributionCoordinator.java`
- Add or modify tests covering contribution-only request assembly and handler behavior.

- [ ] **Step 1: Register handler**

In `registerHandlers`, add:

```java
handler.registerContributionOnlyRequestHandler(
    (payload, context) -> handleContributionOnlyRequest(payload, context)
);
```

- [ ] **Step 2: Extract candidate collection**

Create helpers near existing sync comparison code. The helper must be extracted from the current ordinary sync contribution-candidate code rather than copied as a new broad full-cache scan. Preserve these ordinary sync semantics exactly:

- requested dimension derivation from client metadata
- skipped/invalid dimension filtering
- `visitedServerPaths`
- materialized `allZipPaths`
- existing `regionsToSync` construction
- existing client-only candidate behavior

The contribution-only path may call the helper with an empty `regionsToSync` target, but ordinary `/mapsyncer sync` must still build both `regionsToSync` and contribution candidates exactly as before.

```java
private static ContributionSelection collectContributionSelection(
        Map<String, ClientMeta> clientMeta,
        GenerationCache genCache,
        Path cacheDir,
        boolean includeServerDistribution
) {
    // Extract the existing ordinary sync comparison loop into this helper.
    // When includeServerDistribution is false, do not add any SyncResponse/region entries.
    // Always return contribution candidates with the same filtering as ordinary sync.
}
```

After adding this helper, update ordinary sync to call it with `includeServerDistribution=true`, then keep its outgoing `SyncResponsePayload` behavior unchanged. The contribution-only handler calls it with `includeServerDistribution=false` and must never send `SyncResponsePayload`.

- [ ] **Step 3: Add request assembly and requestId bridge**

Add a per-player contribution-only request assembler in each `ServerSyncHandlerLogic` or as a shared helper. It must collect `ContributionOnlyRequestPayload` parts by `(player UUID, requestId)`, validate `partIndex/totalParts`, discard stale incomplete requests, and call the handler only after all parts arrive.

Add an overload to `ContributionCoordinator`:

```java
public static boolean enqueueSession(ServerPlayer player, List<ContributionRegionMeta> candidates, int requestId) {
    return enqueueSessionInternal(player, candidates, requestId);
}
```

Refactor the existing generated-id method to call the same private helper:

```java
public static boolean enqueueSession(ServerPlayer player, List<ContributionRegionMeta> candidates) {
    return enqueueSessionInternal(player, candidates, NEXT_REQUEST_ID.getAndIncrement());
}
```

The contribution-only path must pass the assembled payload `requestId` into the overload so the server's `ContributionRequestPayload`, client uploads, and final `ContributionResultPayload` all use the same id that `PreDisconnectContributionManager` tracks.

- [ ] **Step 4: Add handler**

```java
private static void handleContributionOnlyRequest(ContributionOnlyRequestPayload payload, PayloadContext context) {
    Object playerObj = context.getPlayer();
    if (!(playerObj instanceof ServerPlayer player)) {
        return;
    }

    context.enqueueWork(() -> {
        if (!ContributionWhitelistBridge.isContributionAllowed(player)) {
            NetworkManager.sendToPlayer(player,
                    new ContributionResultPayload(payload.requestId(), 0, payload.clientMeta().size(), "not_allowed"));
            return;
        }
        startContributionOnlyCandidateWorker(player, payload);
    });
}

private static void startContributionOnlyCandidateWorker(ServerPlayer player, ContributionOnlyRequestPayload payload) {
    Thread worker = new Thread(() -> {
        Path cacheDir = ConversionOrchestrator.getCacheDir();
        GenerationCache cache = GenerationCache.getInstance(cacheDir);
        List<ContributionRegionMeta> candidates =
                collectContributionSelection(payload.clientMeta(), cache, cacheDir, false).contributionCandidates();
        Runnable enqueue = () -> {
            if (!ContributionWhitelistBridge.isContributionAllowed(player)) {
                NetworkManager.sendToPlayer(player,
                        new ContributionResultPayload(payload.requestId(), 0, payload.clientMeta().size(), "not_allowed"));
                return;
            }
            if (candidates.isEmpty()) {
                NetworkManager.sendToPlayer(player,
                        new ContributionResultPayload(payload.requestId(), 0, 0, "no_candidates"));
                return;
            }
            boolean queued = ContributionCoordinator.enqueueSession(player, candidates, payload.requestId());
            if (!queued) {
                NetworkManager.sendToPlayer(player,
                        new ContributionResultPayload(payload.requestId(), 0, candidates.size(), "queue_full"));
            }
        };
        var server = player.level().getServer();
        if (server != null) {
            server.execute(enqueue);
        } else {
            enqueue.run();
        }
    });
    worker.setDaemon(true);
    worker.setName("MapSyncer-ContributionOnly-" + player.getUUID());
    worker.start();
}
```

- [ ] **Step 5: Add handler tests**

Add focused tests or a FakeNetworkHandler integration test for contribution-only behavior:

- fragmented metadata is assembled before candidate comparison
- `not_allowed` uses the original request id
- `no_candidates` uses the original request id
- `queue_full` uses the original request id
- success path does not send `SyncResponsePayload`
- success path enqueues a contribution session with the original request id

- [ ] **Step 6: Compile representative shared consumers**

Run:

`.\gradlew :mc-1.21.1:fabric:compileJava :mc-1.21.1:neoforge:compileJava :mc-1.21.11:neoforge:compileJava`

- [ ] **Step 7: Commit**

```bash
git add libs/common/src/main/java/com/mapsyncer/server/ContributionCoordinator.java mc-1.20.1/shared mc-1.21.1/shared mc-1.21.11/shared mc-26.1/shared
git commit -m "feat: 添加退出前仅贡献服务端流程" -m "服务端处理 ContributionOnlyRequestPayload，只生成贡献候选并排队，不向客户端分发地图数据。"
```

---

### Task 5: Add Pre-Disconnect Client State Machine and Screen

**Files:**
- Create: `libs/common/src/main/java/com/mapsyncer/client/PreDisconnectContributionManager.java`
- Modify: `libs/platform-api/src/main/java/com/mapsyncer/platform/Platform.java`
- Modify platform config classes enough to provide default-safe accessors before compiling this task.
- Create/modify per-version shared: `mc-*/shared/src/main/java/com/mapsyncer/client/PreDisconnectSyncScreen.java`
- Modify every `mc-*/shared/src/main/java/com/mapsyncer/client/MapPacketHandler.java`
- Modify language JSON files.

- [ ] **Step 1: Add Platform accessors used by the manager**

Add default-safe accessors to `Platform` so every existing implementation continues to compile before Task 6 wires real config values:

```java
default boolean isSyncBeforeDisconnect() {
    return true;
}

default int getDisconnectSyncTimeoutSeconds() {
    return 15;
}
```

- [ ] **Step 2: Add manager**

Create `PreDisconnectContributionManager`:

```java
package com.mapsyncer.client;

import com.mapsyncer.config.ClientSyncMode;
import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.network.payload.ContributionOnlyRequestPayload;
import com.mapsyncer.network.payload.ContributionResultPayload;
import com.mapsyncer.platform.PlatformManager;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class PreDisconnectContributionManager {
    private static final AtomicInteger NEXT_REQUEST_ID = new AtomicInteger(10_000);
    private static volatile int activeRequestId = -1;
    private static volatile Runnable disconnectAction;
    private static volatile long deadlineMillis;
    private static volatile String statusKey = "mapsyncer.predisconnect.idle";

    private PreDisconnectContributionManager() {
    }

    public static boolean canStart() {
        return PlatformManager.getPlatform().getClientSyncMode() == ClientSyncMode.BIDIRECTIONAL
                && PlatformManager.getPlatform().isSyncBeforeDisconnect()
                && PlatformManager.getPlatform().getDisconnectSyncTimeoutSeconds() > 0
                && MapPacketHandler.isServerInstalled()
                && !MapPacketHandler.isSyncInProgress()
                && !MapPacketHandler.isContributionInProgress();
    }

    public static void start(Path serverDir, Runnable originalDisconnectAction) {
        if (serverDir == null || originalDisconnectAction == null || !canStart()) {
            if (originalDisconnectAction != null) {
                originalDisconnectAction.run();
            }
            return;
        }
        int requestId = NEXT_REQUEST_ID.incrementAndGet();
        activeRequestId = requestId;
        disconnectAction = originalDisconnectAction;
        deadlineMillis = System.currentTimeMillis()
                + PlatformManager.getPlatform().getDisconnectSyncTimeoutSeconds() * 1000L;
        statusKey = "mapsyncer.predisconnect.collecting";

        Map<String, ClientMeta> meta = ClientHashManager.computeMetaForSync(serverDir);
        statusKey = "mapsyncer.predisconnect.uploading";
        for (ContributionOnlyRequestPayload part
                : ContributionOnlyRequestPayload.split(requestId, meta, "pre_disconnect")) {
            NetworkManager.sendToServer(part);
        }
    }

    public static boolean isActive() {
        return activeRequestId >= 0;
    }

    public static String getStatusKey() {
        return statusKey;
    }

    public static boolean isTimedOut() {
        return isActive() && System.currentTimeMillis() >= deadlineMillis;
    }

    public static void handleContributionResult(ContributionResultPayload payload) {
        if (payload.requestId() == activeRequestId) {
            statusKey = "mapsyncer.predisconnect.status." + payload.status();
            if (isTerminalStatus(payload.status())) {
                finish();
            }
        }
    }

    private static boolean isTerminalStatus(String status) {
        return "done".equals(status)
                || "timeout".equals(status)
                || "queue_full".equals(status)
                || "no_candidates".equals(status)
                || "not_allowed".equals(status)
                || "inactive_request".equals(status)
                || "wrong_player".equals(status)
                || "permission_changed".equals(status)
                || "write_failed".equals(status);
    }

    public static void skipAndDisconnect() {
        statusKey = "mapsyncer.predisconnect.skipped";
        finish();
    }

    public static void cancel() {
        activeRequestId = -1;
        disconnectAction = null;
        statusKey = "mapsyncer.predisconnect.idle";
    }

    public static void finish() {
        Runnable action = disconnectAction;
        activeRequestId = -1;
        disconnectAction = null;
        if (action != null) {
            action.run();
        }
    }
}
```

- [ ] **Step 3: Notify manager from contribution result handler**

In every `MapPacketHandler`, add client contribution state tracking. Set `contributionInProgress=true` when handling `ContributionRequestPayload`; clear it after sending `ContributionCompletePayload` or after receiving a terminal `ContributionResultPayload`. Expose:

```java
public static boolean isContributionInProgress() {
    return contributionInProgress;
}
```

In every `MapPacketHandler.handleContributionResult`, add before debug logging:

```java
PreDisconnectContributionManager.handleContributionResult(payload);
```

- [ ] **Step 4: Add waiting screen**

Create `PreDisconnectSyncScreen` in each shared source set with version-compatible UI imports. Use `Screen`, `Button`, and `Component` only.

Core behavior:

```java
@Override
public void tick() {
    if (PreDisconnectContributionManager.isTimedOut()) {
        PreDisconnectContributionManager.skipAndDisconnect();
    }
}

@Override
public boolean shouldCloseOnEsc() {
    return false;
}

@Override
public boolean isPauseScreen() {
    return false;
}
```

Buttons:

```java
addRenderableWidget(Button.builder(
        Component.translatable("mapsyncer.predisconnect.skip"),
        button -> PreDisconnectContributionManager.skipAndDisconnect()
).bounds(this.width / 2 - 100, this.height / 2 + 24, 200, 20).build());

addRenderableWidget(Button.builder(
        Component.translatable("mapsyncer.predisconnect.return_to_game"),
        button -> {
            PreDisconnectContributionManager.cancel();
            Minecraft.getInstance().setScreen(null);
        }
).bounds(this.width / 2 - 100, this.height / 2 + 48, 200, 20).build());
```

`return_to_game` deliberately cancels only the pending disconnect action. If the server has already queued a contribution session, that contribution may continue in the background.

- [ ] **Step 5: Add language keys**

Add English:

```json
"mapsyncer.predisconnect.title": "Syncing local map before disconnect",
"mapsyncer.predisconnect.collecting": "Scanning local map regions...",
"mapsyncer.predisconnect.uploading": "Uploading local map contributions...",
"mapsyncer.predisconnect.complete": "Contribution sync complete.",
"mapsyncer.predisconnect.skip": "Skip and disconnect",
"mapsyncer.predisconnect.return_to_game": "Return to game",
"mapsyncer.predisconnect.status.accepted": "Contribution accepted, waiting for remaining regions...",
"mapsyncer.predisconnect.status.done": "Contribution sync complete.",
"mapsyncer.predisconnect.status.timeout": "Contribution sync timed out."
```

Add Chinese:

```json
"mapsyncer.predisconnect.title": "退出前正在同步本地地图",
"mapsyncer.predisconnect.collecting": "正在扫描本地地图区域...",
"mapsyncer.predisconnect.uploading": "正在上传本地地图贡献...",
"mapsyncer.predisconnect.complete": "贡献同步已完成。",
"mapsyncer.predisconnect.skip": "跳过并退出",
"mapsyncer.predisconnect.return_to_game": "返回游戏",
"mapsyncer.predisconnect.status.accepted": "已有 region 被接受，正在等待剩余贡献...",
"mapsyncer.predisconnect.status.done": "贡献同步已完成。",
"mapsyncer.predisconnect.status.timeout": "贡献同步已超时。"
```

- [ ] **Step 6: Compile**

Run:

`.\gradlew :mc-1.21.1:fabric:compileJava :mc-1.21.1:neoforge:compileJava :mc-1.21.11:neoforge:compileJava`

- [ ] **Step 7: Commit**

```bash
git add libs/platform-api/src/main/java/com/mapsyncer/platform/Platform.java libs/common/src/main/java/com/mapsyncer/client/PreDisconnectContributionManager.java mc-1.20.1/shared mc-1.21.1/shared mc-1.21.11/shared mc-26.1/shared libs/common/src/main/resources/assets/mapsyncer/lang
git commit -m "feat: 添加退出前贡献同步状态机" -m "客户端在正常断开前可进入等待界面并发起仅贡献请求，贡献完成、跳过、取消或超时后再执行原始断开动作。"
```

---

### Task 6: Wire Config Storage and Screens

**Files:**
- Modify every platform `ModConfig.java`
- Modify every platform `Platform` implementation.
- Modify Fabric `ConfigScreenFactory.java` files.
- Modify language JSON files for Fabric config labels and tooltips.

- [ ] **Step 1: Add config fields**

Fabric defaults:

```java
private volatile boolean syncBeforeDisconnect = true;
private volatile int disconnectSyncTimeoutSeconds = 15;
```

Properties comments must explain behavior:

```java
sb.append("# Whether the client should try to upload local Xaero map contributions before a normal disconnect.\n");
sb.append("# This only runs for BIDIRECTIONAL clients and cannot protect crashes, force closes, or network loss.\n");
sb.append("syncBeforeDisconnect=" + syncBeforeDisconnect + "\n\n");
sb.append("# Maximum seconds to wait on the pre-disconnect contribution screen. 0 disables waiting.\n");
sb.append("disconnectSyncTimeoutSeconds=" + disconnectSyncTimeoutSeconds + "\n\n");
```

Forge/NeoForge builder comments:

```java
syncBeforeDisconnect = builder
        .comment("Try to upload local Xaero map contributions before a normal disconnect.",
                 "Only applies to BIDIRECTIONAL clients. Crashes, force closes, and network loss cannot be protected.")
        .define("syncBeforeDisconnect", true);

disconnectSyncTimeoutSeconds = builder
        .comment("Maximum seconds to wait on the pre-disconnect contribution screen.",
                 "Set to 0 to disable the waiting flow.")
        .defineInRange("disconnectSyncTimeoutSeconds", 15, 0, 60);
```

- [ ] **Step 2: Wire platform getters**

Each platform implementation overrides the default `Platform` methods and returns config values from its local `ModConfig`.

- [ ] **Step 3: Add config screen controls and translations**

For Fabric `ConfigScreenFactory`, add a toggle for `syncBeforeDisconnect` and an integer field/slider for `disconnectSyncTimeoutSeconds` in the client category. Add English and Chinese `option.mapsyncer.*` keys matching the existing language style:

```json
"option.mapsyncer.sync_before_disconnect": "Sync before disconnect",
"option.mapsyncer.sync_before_disconnect.tooltip": "Try to upload local Xaero map contributions before a normal disconnect. Only BIDIRECTIONAL clients use this.",
"option.mapsyncer.disconnect_sync_timeout_seconds": "Disconnect sync timeout",
"option.mapsyncer.disconnect_sync_timeout_seconds.tooltip": "Maximum seconds to wait before disconnecting. Set to 0 to disable pre-disconnect waiting."
```

```json
"option.mapsyncer.sync_before_disconnect": "退出前同步",
"option.mapsyncer.sync_before_disconnect.tooltip": "正常断开连接前尝试上传本地 Xaero 地图贡献。仅 BIDIRECTIONAL 客户端启用。",
"option.mapsyncer.disconnect_sync_timeout_seconds": "退出同步超时",
"option.mapsyncer.disconnect_sync_timeout_seconds.tooltip": "断开连接前最多等待的秒数。设为 0 会禁用退出前等待。"
```

For Forge and NeoForge, add `ModConfigSpec` client entries, comments, and platform getters. If the loader version has no custom in-game config screen, document that the options are managed through the generated config file or loader-provided config UI.

- [ ] **Step 4: Compile**

Run:

`.\gradlew :mc-1.21.1:fabric:compileJava :mc-1.21.1:neoforge:compileJava :mc-1.21.11:neoforge:compileJava`

- [ ] **Step 5: Commit**

```bash
git add mc-1.20.1 mc-1.21.1 mc-1.21.11 mc-26.1
git commit -m "feat: 添加退出前同步配置项" -m "客户端可配置是否在正常退出前尝试贡献地图，以及等待贡献完成的最大秒数，配置注释说明能力边界。"
```

---

### Task 7: Intercept Normal Disconnect Button

**Files:**
- Add Mixin config/resources in each loader module that should intercept normal disconnect.
- Create these mixin class paths in the listed loader modules:
  - `mc-1.20.1/fabric/src/main/java/com/mapsyncer/mixin/PauseScreenMixin.java`
  - `mc-1.20.1/forge/src/main/java/com/mapsyncer/mixin/PauseScreenMixin.java`
  - `mc-1.21.1/fabric/src/main/java/com/mapsyncer/mixin/PauseScreenMixin.java`
  - `mc-1.21.1/forge/src/main/java/com/mapsyncer/mixin/PauseScreenMixin.java`
  - `mc-1.21.1/neoforge/src/main/java/com/mapsyncer/mixin/PauseScreenMixin.java`
  - `mc-1.21.11/fabric/src/main/java/com/mapsyncer/mixin/PauseScreenMixin.java`
  - `mc-1.21.11/forge/src/main/java/com/mapsyncer/mixin/PauseScreenMixin.java`
  - `mc-1.21.11/neoforge/src/main/java/com/mapsyncer/mixin/PauseScreenMixin.java`
  - `mc-26.1/fabric/src/main/java/com/mapsyncer/mixin/PauseScreenMixin.java`
  - `mc-26.1/neoforge/src/main/java/com/mapsyncer/mixin/PauseScreenMixin.java`
- Modify each mod metadata file to include the mixin config.

- [ ] **Step 1: Identify actual PauseScreen injection point for each MC version**

Run decompile/source inspection for the target version:

`.\gradlew :mc-1.21.1:fabric:genSources`

Inspect `net.minecraft.client.gui.screens.PauseScreen` for the method that handles disconnect. Use the smallest stable hook:

- Prefer injecting into a dedicated disconnect method if present.
- Otherwise inject into the button callback in pause menu creation.

Record the chosen method name in the implementation commit message and in the task summary. Maintain a short implementation table while working:

```text
MC version | loader(s) | target method/callback | vanilla action used | multiplayer Disconnect covered | single-player Return to Title untouched
1.20.1    | Fabric/Forge | ... | ... | yes/no | yes/no
1.21.1    | Fabric/Forge/NeoForge | ... | ... | yes/no | yes/no
1.21.11   | Fabric/Forge/NeoForge | ... | ... | yes/no | yes/no
26.1      | Fabric/NeoForge | ... | ... | yes/no | yes/no
```

- [ ] **Step 2: Add intercept helper**

Create a platform-neutral helper in shared client code:

```java
public final class PreDisconnectHooks {
    private PreDisconnectHooks() {
    }

    public static boolean tryStart(Runnable originalDisconnectAction) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || mc.isLocalServer()) {
            return false;
        }
        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
        if (!PreDisconnectContributionManager.canStart() || serverDir == null) {
            return false;
        }
        mc.setScreen(new PreDisconnectSyncScreen());
        PreDisconnectContributionManager.start(serverDir, originalDisconnectAction);
        return true;
    }
}
```

- [ ] **Step 3: Add Mixin implementation**

Mixin must cancel the original disconnect action only when `PreDisconnectHooks.tryStart(originalAction)` returns `true`. The `originalAction` must call the exact vanilla disconnect logic for that version. If Mixin targets differ across versions, create version-specific classes instead of reflection. The mixin must cover multiplayer `Disconnect` and must not trigger for single-player `Return to Title`.

Fabric metadata example:

```json
"mixins": [
  "mapsyncer.mixins.json"
]
```

Mixin config:

```json
{
  "required": true,
  "package": "com.mapsyncer.mixin",
  "compatibilityLevel": "JAVA_21",
  "client": [
    "PauseScreenMixin"
  ],
  "injectors": {
    "defaultRequire": 1
  }
}
```

Use `JAVA_17` for 1.20.1 if required by that module.

- [ ] **Step 4: Manual smoke behavior**

In dev client:

1. Join a server with MapSyncer installed.
2. Set client mode `BIDIRECTIONAL`.
3. Press Esc → Disconnect.
4. Expected: pre-disconnect screen appears instead of immediate disconnect.
5. Click `Cancel disconnect`.
6. Expected: screen closes and player remains connected.
7. Press Disconnect again, click `Skip and disconnect`.
8. Expected: vanilla disconnect proceeds.
9. Set client mode `DISABLED` and `RECEIVE_ONLY`; expected: vanilla disconnect proceeds immediately with no pre-disconnect screen and no `ContributionOnlyRequestPayload`.
10. During the pre-disconnect screen, press WASD, Esc, inventory, and chat; expected: no player command/input UI action proceeds through the screen. Server-side world/entity ticking is not frozen.
11. In single-player or local integrated-server flow, press Return to Title; expected: pre-disconnect flow does not start.

- [ ] **Step 5: Commit**

```bash
git add mc-1.20.1 mc-1.21.1 mc-1.21.11 mc-26.1
git commit -m "feat: 拦截正常退出并触发贡献同步" -m "通过客户端暂停菜单 Mixin 在正常断开前进入退出前同步界面，贡献完成、跳过或超时后再执行原始断开逻辑。"
```

---

### Task 8: Documentation and Verification

**Files:**
- Modify: `docs/features.md`
- Modify: `docs/test-notes.md`
- Modify: `docs/superpowers/specs/2026-07-08-exit-contribution-sync-design.md` only when implementation proves the documented design inaccurate; otherwise leave the committed spec unchanged.

- [ ] **Step 1: Update docs**

Add to `docs/features.md`:

- `syncBeforeDisconnect`
- `disconnectSyncTimeoutSeconds`
- limitation that crashes and forced exits cannot be protected
- contribution-only exit flow does not perform server-to-client distribution

Add manual tests to `docs/test-notes.md` using the document's existing table style. Include environment, expected result, actual result, and notes for:

- hash mismatch uses file timestamp
- normal disconnect opens waiting screen
- cancel returns to game
- skip exits immediately
- successful contribution exits after result
- timeout exits after configured seconds
- crash/kill process remains unsupported
- `DISABLED` and `RECEIVE_ONLY` disconnect immediately without pre-disconnect sync
- waiting screen blocks normal input while server/entity ticking is not frozen

- [ ] **Step 2: Run verification**

Run:

```powershell
.\gradlew :libs:platform-api:test
.\gradlew :mc-1.21.1:fabric:compileJava :mc-1.21.1:neoforge:compileJava :mc-1.21.11:neoforge:compileJava
.\scripts\fastbuild\build-target.ps1 all -NoTest
Get-ChildItem -Recurse -Path mc-* -Filter *.jar | Where-Object { $_.FullName -match '\\build\\libs\\' }
git diff --check
```

If local `gradle.properties` points to an unavailable JDK, temporarily patch it to the local JDK path for verification and restore it before committing.

`buildPackager` is not required for this plan unless implementation unexpectedly touches packager/core packaging entry points.

- [ ] **Step 3: Commit docs**

```bash
git add docs/features.md docs/test-notes.md
git commit -m "docs: 补充退出前贡献同步说明" -m "记录正常退出前贡献同步的配置、用户流程、能力边界和手动验收项。"
```

- [ ] **Step 4: Final status**

Run:

```powershell
git status --short --branch
git log -8 --oneline
```

Expected: clean worktree on `codex/bidirectional-xaero-sync`, with the new task commits on top.

---

## Self-Review Notes

- Spec coverage: Task 1 covers metadata robustness; Tasks 2-4 cover contribution-only protocol and server behavior; Tasks 5-7 cover the normal-exit waiting state and interception; Task 8 covers docs and verification.
- Scope: This plan intentionally does not implement Xaero region semantic merging or crash-safe persistence because the spec excludes them.
- Risk: Mixin injection points are version-sensitive. The plan requires source inspection per MC version before writing each mixin and compile verification afterward.
