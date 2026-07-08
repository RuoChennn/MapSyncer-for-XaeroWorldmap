# Exit Contribution Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a normal-exit safeguard that contributes local Xaero map data before disconnecting, while fixing the existing hash/timestamp metadata robustness issue.

**Architecture:** Keep ordinary `/mapsyncer sync` unchanged. Add a contribution-only request path that produces server contribution candidates without sending server map data back to the client, and drive it from a client-side pre-disconnect state machine opened by pause-menu interception. Reuse the existing `ContributionCoordinator`, `ClientContributionCollector`, whitelist check, queued execution, and contribution result payload flow for every contribution-only path.

**Tech Stack:** Java 21/17-compatible source, Minecraft client/server APIs, Fabric/Forge/NeoForge networking adapters, Mixin for pause-menu interception, JUnit 5 tests, Gradle module builds.

---

## File Structure

- Modify: `libs/common/src/main/java/com/mapsyncer/client/ClientHashManager.java` — hash-aware timestamp selection.
- Test: `mc-1.21.1/fabric/src/test/java/com/mapsyncer/client/ClientHashManagerTest.java` — regression tests for timestamp source selection.
- Create: `libs/platform-api/src/main/java/com/mapsyncer/network/payload/ContributionOnlyRequestPayload.java` — client-to-server request for contribution candidates only.
- Modify: `libs/platform-api/src/main/java/com/mapsyncer/network/NetworkHandler.java` — new send/register methods and payload ID.
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

- [ ] **Step 1: Write failing regression tests**

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

- [ ] **Step 2: Run the focused test and verify RED**

Run: `.\gradlew :mc-1.21.1:fabric:compileTestJava`

Expected before implementation: `compileTestJava` succeeds; full `test` may still be blocked by Fabric runtime TLS in this environment. If test execution is available, run:

`.\gradlew :mc-1.21.1:fabric:test --tests com.mapsyncer.client.ClientHashManagerTest`

Expected before implementation: first test fails because cached timestamp `100` is used despite hash mismatch.

- [ ] **Step 3: Implement hash-aware cached timestamp selection**

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

- [ ] **Step 4: Verify GREEN**

Run: `.\gradlew :mc-1.21.1:fabric:compileTestJava`

If dependency resolution permits, also run:

`.\gradlew :mc-1.21.1:fabric:test --tests com.mapsyncer.client.ClientHashManagerTest`

- [ ] **Step 5: Commit**

```bash
git add libs/common/src/main/java/com/mapsyncer/client/ClientHashManager.java mc-1.21.1/fabric/src/test/java/com/mapsyncer/client/ClientHashManagerTest.java
git commit -m "fix: 修复客户端元数据时间戳判新" -m "仅当缓存哈希与当前文件哈希一致时复用逻辑时间戳，否则回退到文件修改时间，避免本地内容变化后仍沿用旧同步时间。"
```

---

### Task 2: Add Contribution-Only Network Contract

**Files:**
- Create: `libs/platform-api/src/main/java/com/mapsyncer/network/payload/ContributionOnlyRequestPayload.java`
- Modify: `libs/platform-api/src/main/java/com/mapsyncer/network/NetworkHandler.java`
- Modify: `libs/platform-api/src/test/java/com/mapsyncer/network/ContributionPayloadContractTest.java`

- [ ] **Step 1: Add failing payload contract test**

Append to `ContributionPayloadContractTest`:

```java
@Test
void contributionOnlyRequestHasStableIdAndMetadata() {
    Map<String, ClientMeta> meta = Map.of(
            "null/1_2", new ClientMeta(300, "1234abcd")
    );

    ContributionOnlyRequestPayload payload = new ContributionOnlyRequestPayload(55, meta, "pre_disconnect");

    assertEquals(NetworkHandler.CONTRIBUTION_ONLY_REQUEST_ID, ContributionOnlyRequestPayload.ID);
    assertEquals(55, payload.requestId());
    assertEquals("pre_disconnect", payload.reason());
    assertEquals(300, payload.clientMeta().get("null/1_2").timestampSeconds());
}
```

Add imports:

```java
import com.mapsyncer.network.payload.ContributionOnlyRequestPayload;
import java.util.Map;
```

- [ ] **Step 2: Run platform-api tests and verify RED**

Run: `.\gradlew :libs:platform-api:test`

Expected: compile fails because `ContributionOnlyRequestPayload` and `CONTRIBUTION_ONLY_REQUEST_ID` do not exist.

- [ ] **Step 3: Create payload DTO**

Create `ContributionOnlyRequestPayload.java`:

```java
package com.mapsyncer.network.payload;

import com.mapsyncer.network.NetworkHandler;

import java.util.Collections;
import java.util.Map;

public record ContributionOnlyRequestPayload(
        int requestId,
        Map<String, ClientMeta> clientMeta,
        String reason
) {
    public static final String ID = NetworkHandler.CONTRIBUTION_ONLY_REQUEST_ID;

    public ContributionOnlyRequestPayload {
        clientMeta = clientMeta == null ? Map.of() : Collections.unmodifiableMap(clientMeta);
        reason = reason == null ? "" : reason;
    }
}
```

- [ ] **Step 4: Extend NetworkHandler**

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

- [ ] **Step 5: Run platform-api tests**

Run: `.\gradlew :libs:platform-api:test`

Expected: tests pass.

- [ ] **Step 6: Commit**

```bash
git add libs/platform-api/src/main/java/com/mapsyncer/network/NetworkHandler.java libs/platform-api/src/main/java/com/mapsyncer/network/payload/ContributionOnlyRequestPayload.java libs/platform-api/src/test/java/com/mapsyncer/network/ContributionPayloadContractTest.java
git commit -m "feat: 添加退出前贡献请求协议" -m "新增 ContributionOnlyRequestPayload，用于客户端退出前只请求服务端贡献候选，不触发服务端地图分发。"
```

---

### Task 3: Wire Contribution-Only Payload Through Platform Networks

**Files:**
- Modify every `FabricPayloadAdapters.java`, `ForgePayloadAdapters.java`, and `NeoForgePayloadAdapters.java`.
- Modify every `FabricNetworkHandler.java`, `ForgeNetworkHandler.java`, and `NeoForgeNetworkHandler.java`.

- [ ] **Step 1: Add adapter wrappers/codecs**

For Fabric adapters, add wrapper:

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
    int size = buf.readInt();
    Map<String, ClientMeta> meta = new HashMap<>();
    for (int i = 0; i < size; i++) {
        meta.put(buf.readUtf(), new ClientMeta(buf.readLong(), buf.readUtf()));
    }
    return new ContributionOnlyRequestPayload(requestId, meta, buf.readUtf());
}
```

For Forge adapters, add `ForgeContributionOnlyRequestMessage` with encode/decode methods using this field order: `requestId`, map size, repeated `path/timestamp/hash`, then `reason`.

For NeoForge adapters, add `NeoForgeContributionOnlyRequestPayload` with a `StreamCodec` using the same field order.

- [ ] **Step 2: Add NetworkHandler state and registration**

In every platform network handler, add:

```java
private BiConsumer<ContributionOnlyRequestPayload, PayloadContext> contributionOnlyRequestHandler;
```

Register the C2S receiver in the same registration block as `ContributionDataPayload`. In Forge and NeoForge handlers, add the player UUID to the existing confirmed-player set before dispatching to `contributionOnlyRequestHandler`.

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

- [ ] **Step 1: Register handler**

In `registerHandlers`, add:

```java
handler.registerContributionOnlyRequestHandler(
    (payload, context) -> handleContributionOnlyRequest(payload, context)
);
```

- [ ] **Step 2: Extract candidate collection**

Create a helper near existing sync comparison code:

```java
private static List<ContributionRegionMeta> collectContributionCandidates(
        Map<String, ClientMeta> clientMeta,
        GenerationCache genCache,
        Path cacheDir
) {
    Map<String, TimestampHashEntry> serverCache = genCache.getAll();
    List<ContributionRegionMeta> candidates = new ArrayList<>();
    Set<String> visitedServerPaths = new HashSet<>();
    DimensionPathMapping dimMapping = DimensionPathMapping.getInstance();

    Path absCacheDir = cacheDir.toAbsolutePath().normalize();
    if (Files.exists(absCacheDir)) {
        try (Stream<Path> stream = Files.walk(absCacheDir)) {
            stream.filter(p -> p.toString().endsWith(".zip")).forEach(zipPath -> {
                String normalizedPath = absCacheDir.relativize(zipPath).toString()
                        .replace(".zip", "")
                        .replace("\\", "/");
                normalizedPath = stripMwWorldId(normalizedPath);
                String[] parts = normalizedPath.split("[/\\\\]");
                String xaeroDimName = parts.length > 1 ? parts[0] : "unknown";
                String normalizedXaeroDim = dimMapping.toXaeroDimension(xaeroDimName);
                if (!normalizedXaeroDim.equals(xaeroDimName)) {
                    normalizedPath = normalizedXaeroDim + normalizedPath.substring(xaeroDimName.length());
                }

                visitedServerPaths.add(normalizedPath);
                TimestampHashEntry serverMeta = serverCache.get(normalizedPath);
                ClientMeta clientEntry = clientMeta.get(normalizedPath);
                RegionFreshnessDecision decision = RegionFreshnessDecider.decide(serverMeta, clientEntry);
                if (serverMeta != null && decision.shouldRequestContribution()) {
                    RegionSyncInfo parsed = parseRegionInfo(zipPath, normalizedPath, serverMeta.timestampSeconds());
                    if (parsed != null) {
                        candidates.add(toContributionMeta(parsed, serverMeta));
                    }
                }
            });
        } catch (IOException e) {
            LOGGER.warn("Failed to collect contribution-only candidates", e);
        }
    }

    candidates.addAll(collectClientOnlyContributionCandidates(clientMeta, visitedServerPaths));
    return candidates;
}
```

After adding this helper, replace the ordinary sync path's local contribution-candidate construction with a call to `collectContributionCandidates(clientMeta, genCache, cacheDir)` and keep `regionsToSync` construction in the existing sync loop.

- [ ] **Step 3: Add handler**

```java
private static void handleContributionOnlyRequest(ContributionOnlyRequestPayload payload, PayloadContext context) {
    ServerPlayer player = NetworkManager.getPlayerFromContext(context);
    if (player == null) {
        return;
    }
    NetworkManager.enqueueWork(context, () -> {
        if (!ContributionWhitelistBridge.isContributionAllowed(player)) {
            NetworkManager.sendToPlayer(player,
                    new ContributionResultPayload(payload.requestId(), 0, payload.clientMeta().size(), "not_allowed"));
            return;
        }
        Path cacheDir = ConversionOrchestrator.getCacheDir();
        GenerationCache cache = GenerationCache.getInstance(cacheDir);
        List<ContributionRegionMeta> candidates =
                collectContributionCandidates(payload.clientMeta(), cache, cacheDir);
        if (candidates.isEmpty()) {
            NetworkManager.sendToPlayer(player,
                    new ContributionResultPayload(payload.requestId(), 0, 0, "no_candidates"));
            return;
        }
        boolean queued = ContributionCoordinator.enqueueSession(player, candidates);
        if (!queued) {
            NetworkManager.sendToPlayer(player,
                    new ContributionResultPayload(payload.requestId(), 0, candidates.size(), "queue_full"));
        }
    });
}
```

- [ ] **Step 4: Compile representative shared consumers**

Run:

`.\gradlew :mc-1.21.1:fabric:compileJava :mc-1.21.1:neoforge:compileJava :mc-1.21.11:neoforge:compileJava`

- [ ] **Step 5: Commit**

```bash
git add mc-1.20.1/shared mc-1.21.1/shared mc-1.21.11/shared mc-26.1/shared
git commit -m "feat: 添加退出前仅贡献服务端流程" -m "服务端处理 ContributionOnlyRequestPayload，只生成贡献候选并排队，不向客户端分发地图数据。"
```

---

### Task 5: Add Pre-Disconnect Client State Machine and Screen

**Files:**
- Create: `libs/common/src/main/java/com/mapsyncer/client/PreDisconnectContributionManager.java`
- Modify: `libs/platform-api/src/main/java/com/mapsyncer/platform/Platform.java`
- Create/modify per-version shared: `mc-*/shared/src/main/java/com/mapsyncer/client/PreDisconnectSyncScreen.java`
- Modify every `mc-*/shared/src/main/java/com/mapsyncer/client/MapPacketHandler.java`
- Modify language JSON files.

- [ ] **Step 1: Add Platform accessors used by the manager**

Add to `Platform`:

```java
boolean isSyncBeforeDisconnect();

int getDisconnectSyncTimeoutSeconds();
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
                && !MapPacketHandler.isSyncInProgress();
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
        NetworkManager.sendToServer(new ContributionOnlyRequestPayload(requestId, meta, "pre_disconnect"));
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
            statusKey = "mapsyncer.predisconnect.complete";
            finish();
        }
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
        Component.translatable("mapsyncer.predisconnect.cancel"),
        button -> {
            PreDisconnectContributionManager.cancel();
            Minecraft.getInstance().setScreen(null);
        }
).bounds(this.width / 2 - 100, this.height / 2 + 48, 200, 20).build());
```

- [ ] **Step 5: Add language keys**

Add English:

```json
"mapsyncer.predisconnect.title": "Syncing local map before disconnect",
"mapsyncer.predisconnect.collecting": "Scanning local map regions...",
"mapsyncer.predisconnect.uploading": "Uploading local map contributions...",
"mapsyncer.predisconnect.complete": "Contribution sync complete.",
"mapsyncer.predisconnect.skip": "Skip and disconnect",
"mapsyncer.predisconnect.cancel": "Cancel disconnect"
```

Add Chinese:

```json
"mapsyncer.predisconnect.title": "退出前正在同步本地地图",
"mapsyncer.predisconnect.collecting": "正在扫描本地地图区域...",
"mapsyncer.predisconnect.uploading": "正在上传本地地图贡献...",
"mapsyncer.predisconnect.complete": "贡献同步已完成。",
"mapsyncer.predisconnect.skip": "跳过并退出",
"mapsyncer.predisconnect.cancel": "取消退出"
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

Each platform implementation returns config values from its local `ModConfig`.

- [ ] **Step 3: Add config screen controls**

For Fabric `ConfigScreenFactory`, add a toggle for `syncBeforeDisconnect` and an integer field/slider for `disconnectSyncTimeoutSeconds` in the client category.

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

Record the chosen method name in the implementation commit message.

- [ ] **Step 2: Add intercept helper**

Create a platform-neutral helper in shared client code:

```java
public final class PreDisconnectHooks {
    private PreDisconnectHooks() {
    }

    public static boolean tryStart(Runnable originalDisconnectAction) {
        Minecraft mc = Minecraft.getInstance();
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

Mixin must cancel the original disconnect action only when `PreDisconnectHooks.tryStart(originalAction)` returns `true`. The `originalAction` must call the exact vanilla disconnect logic for that version. If Mixin targets differ across versions, create version-specific classes instead of reflection.

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

Add manual tests to `docs/test-notes.md`:

- hash mismatch uses file timestamp
- normal disconnect opens waiting screen
- cancel returns to game
- skip exits immediately
- successful contribution exits after result
- timeout exits after configured seconds
- crash/kill process remains unsupported

- [ ] **Step 2: Run verification**

Run:

```powershell
.\gradlew :libs:platform-api:test
.\gradlew :mc-1.21.1:fabric:compileJava :mc-1.21.1:neoforge:compileJava :mc-1.21.11:neoforge:compileJava
git diff --check
```

If local `gradle.properties` points to an unavailable JDK, temporarily patch it to the local JDK path for verification and restore it before committing.

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
