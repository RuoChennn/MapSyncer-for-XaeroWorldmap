# Bidirectional Xaero Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build safe bidirectional Xaero map sync where clients can disable sync, receive only, or contribute newer region data back to the server under server-controlled permissions.

**Architecture:** Keep the existing client-to-server metadata handshake and server-to-client distribution path. Add a contribution phase after distribution, with region-level freshness decisions, upload-before-write validation, a global contribution queue with cooldown, and config-controlled participation. Put pure DTOs and decision helpers in `libs/platform-api` / `libs/core`; put Minecraft-dependent behavior in shared version code and platform network adapters.

**Tech Stack:** Java 17 bytecode with Java 21 toolchain, Gradle multi-project build, Minecraft Forge/Fabric/NeoForge platform adapters, JUnit Jupiter for new pure Java tests.

**Review-Driven Constraints:** Three read-only review agents checked this plan before implementation. The following constraints are mandatory:

- The contribution queue must own the full server-side contribution session: request issuance, chunk receipt, assembly, final validation, writes to `server_map_cache`, cache metadata updates, completion, timeout, and cooldown. Queueing only the request send is invalid.
- Every contribution upload must be bound to a server-created session (`requestId`, player UUID, expected relative paths, observed server timestamp/hash). Reject uploads that are unrequested, stale, from another player, duplicated, outside the expected region list, or submitted after permission changes.
- Contributions are region-level and chunked. Reuse `ChunkMapData.split(original)` for upload fragments and assemble them on the server before zip validation and atomic file replacement.
- Do not use transfer time as freshness. Client file mtime may only be a candidate freshness hint when there is no logical cache entry; the server must store a server-side authoritative logical timestamp when accepting a contribution.
- The server must add contribution candidates from both visited server regions and valid client metadata paths missing on the server, otherwise client-only regions are never requested.
- `mc-1.20.1/fabric` uses the legacy Fabric networking style and must not be implemented with the 1.21+ `PayloadTypeRegistry` / `StreamCodec` path.

---

## File Structure

Create:
- `libs/platform-api/src/main/java/com/mapsyncer/config/ClientSyncMode.java` - client sync mode enum.
- `libs/platform-api/src/main/java/com/mapsyncer/config/ContributionScope.java` - server contribution permission enum.
- `libs/platform-api/src/main/java/com/mapsyncer/sync/RegionFreshnessDecision.java` - immutable freshness decision result.
- `libs/platform-api/src/main/java/com/mapsyncer/sync/RegionFreshnessDecider.java` - pure region freshness rules.
- `libs/platform-api/src/main/java/com/mapsyncer/network/payload/ContributionRegionMeta.java` - contribution candidate metadata.
- `libs/platform-api/src/main/java/com/mapsyncer/network/payload/ContributionRequestPayload.java` - server request for client uploads.
- `libs/platform-api/src/main/java/com/mapsyncer/network/payload/ContributionDataPayload.java` - client upload fragment payload.
- `libs/platform-api/src/main/java/com/mapsyncer/network/payload/ContributionCompletePayload.java` - client upload completion/no-more-data signal.
- `libs/platform-api/src/main/java/com/mapsyncer/network/payload/ContributionResultPayload.java` - server result summary.
- `libs/core/src/main/java/com/mapsyncer/util/UuidWhitelistFile.java` - minimal JSON UUID whitelist reader/writer.
- `libs/common/src/main/java/com/mapsyncer/client/ClientContributionCollector.java` - client-side upload candidate reader.
- `libs/common/src/main/java/com/mapsyncer/client/BackgroundSyncManager.java` - online periodic metadata checks.
- `libs/common/src/main/java/com/mapsyncer/server/ContributionCoordinator.java` - server contribution queue and cooldown.
- `libs/common/src/main/java/com/mapsyncer/server/ContributionSession.java` - active server-side contribution session state.
- `libs/common/src/main/java/com/mapsyncer/server/ContributionUploadAssembler.java` - server-side chunk assembler for uploaded regions.
- `libs/common/src/main/java/com/mapsyncer/server/ContributionValidator.java` - server-side upload validation.
- `libs/common/src/main/java/com/mapsyncer/server/ContributionWhitelistBridge.java` - Minecraft-world-aware contributor whitelist loader.

Modify:
- `libs/core/build.gradle` - add JUnit test dependencies.
- `libs/platform-api/build.gradle` - add JUnit test dependencies.
- `libs/platform-api/src/main/java/com/mapsyncer/platform/Platform.java` - expose new config getters.
- `libs/platform-api/src/main/java/com/mapsyncer/network/NetworkHandler.java` - add contribution payload send/register methods.
- `libs/platform-api/src/main/java/com/mapsyncer/network/NetworkManager.java` - delegate contribution payload methods.
- Version shared files:
  - `mc-1.20.1/shared/src/main/java/com/mapsyncer/client/MapSyncerCommandLogic.java`
  - `mc-1.20.1/shared/src/main/java/com/mapsyncer/client/MapPacketHandler.java`
  - `mc-1.20.1/shared/src/main/java/com/mapsyncer/server/ServerSyncHandlerLogic.java`
  - `mc-1.20.1/shared/src/main/java/com/mapsyncer/server/PlayerJoinHandlerLogic.java`
  - `mc-1.21.1/shared/src/main/java/com/mapsyncer/client/MapSyncerCommandLogic.java`
  - `mc-1.21.1/shared/src/main/java/com/mapsyncer/client/MapPacketHandler.java`
  - `mc-1.21.1/shared/src/main/java/com/mapsyncer/server/ServerSyncHandlerLogic.java`
  - `mc-1.21.1/shared/src/main/java/com/mapsyncer/server/PlayerJoinHandlerLogic.java`
  - `mc-1.21.11/shared/src/main/java/com/mapsyncer/client/MapSyncerCommandLogic.java`
  - `mc-1.21.11/shared/src/main/java/com/mapsyncer/client/MapPacketHandler.java`
  - `mc-1.21.11/shared/src/main/java/com/mapsyncer/server/ServerSyncHandlerLogic.java`
  - `mc-1.21.11/shared/src/main/java/com/mapsyncer/server/PlayerJoinHandlerLogic.java`
  - `mc-26.1/shared/src/main/java/com/mapsyncer/client/MapSyncerCommandLogic.java`
  - `mc-26.1/shared/src/main/java/com/mapsyncer/client/MapPacketHandler.java`
  - `mc-26.1/shared/src/main/java/com/mapsyncer/server/ServerSyncHandlerLogic.java`
  - `mc-26.1/shared/src/main/java/com/mapsyncer/server/PlayerJoinHandlerLogic.java`
- Platform config files:
  - `mc-1.20.1/fabric/src/main/java/com/mapsyncer/config/ModConfig.java`
  - `mc-1.20.1/fabric/src/main/java/com/mapsyncer/client/ConfigScreenFactory.java`
  - `mc-1.20.1/fabric/src/main/java/com/mapsyncer/platform/impl/FabricPlatform.java`
  - `mc-1.20.1/forge/src/main/java/com/mapsyncer/config/ModConfig.java`
  - `mc-1.20.1/forge/src/main/java/com/mapsyncer/platform/impl/ForgeLegacyPlatform.java`
  - `mc-1.21.1/fabric/src/main/java/com/mapsyncer/config/ModConfig.java`
  - `mc-1.21.1/fabric/src/main/java/com/mapsyncer/client/ConfigScreenFactory.java`
  - `mc-1.21.1/fabric/src/main/java/com/mapsyncer/platform/impl/FabricPlatform.java`
  - `mc-1.21.1/forge/src/main/java/com/mapsyncer/config/ModConfig.java`
  - `mc-1.21.1/forge/src/main/java/com/mapsyncer/platform/impl/ForgePlatform.java`
  - `mc-1.21.1/neoforge/src/main/java/com/mapsyncer/config/ModConfig.java`
  - `mc-1.21.1/neoforge/src/main/java/com/mapsyncer/platform/impl/NeoForgePlatform.java`
  - `mc-1.21.11/fabric/src/main/java/com/mapsyncer/config/ModConfig.java`
  - `mc-1.21.11/fabric/src/main/java/com/mapsyncer/client/ConfigScreenFactory.java`
  - `mc-1.21.11/fabric/src/main/java/com/mapsyncer/platform/impl/FabricPlatform.java`
  - `mc-1.21.11/forge/src/main/java/com/mapsyncer/config/ModConfig.java`
  - `mc-1.21.11/forge/src/main/java/com/mapsyncer/platform/impl/ForgePlatform.java`
  - `mc-1.21.11/neoforge/src/main/java/com/mapsyncer/config/ModConfig.java`
  - `mc-1.21.11/neoforge/src/main/java/com/mapsyncer/platform/impl/NeoForgePlatform.java`
  - `mc-26.1/fabric/src/main/java/com/mapsyncer/config/ModConfig.java`
  - `mc-26.1/fabric/src/main/java/com/mapsyncer/client/ConfigScreenFactory.java`
  - `mc-26.1/fabric/src/main/java/com/mapsyncer/platform/impl/FabricPlatform.java`
  - `mc-26.1/neoforge/src/main/java/com/mapsyncer/config/ModConfig.java`
  - `mc-26.1/neoforge/src/main/java/com/mapsyncer/platform/impl/NeoForge26Platform.java`
- Platform network files for each supported loader/version:
  - `mc-1.20.1/fabric/src/main/java/com/mapsyncer/network/FabricPayloadAdapters.java`
  - `mc-1.20.1/fabric/src/main/java/com/mapsyncer/network/impl/FabricNetworkHandler.java`
  - `mc-1.20.1/fabric/src/main/java/com/mapsyncer/network/impl/FabricClientNetworkHandler.java`
  - `mc-1.20.1/forge/src/main/java/com/mapsyncer/network/ForgePayloadAdapters.java`
  - `mc-1.20.1/forge/src/main/java/com/mapsyncer/network/impl/ForgeNetworkHandler.java`
  - `mc-1.21.1/fabric/src/main/java/com/mapsyncer/network/FabricPayloadAdapters.java`
  - `mc-1.21.1/fabric/src/main/java/com/mapsyncer/network/impl/FabricNetworkHandler.java`
  - `mc-1.21.1/forge/src/main/java/com/mapsyncer/network/ForgePayloadAdapters.java`
  - `mc-1.21.1/forge/src/main/java/com/mapsyncer/network/impl/ForgeNetworkHandler.java`
  - `mc-1.21.1/neoforge/src/main/java/com/mapsyncer/network/NeoForgePayloadAdapters.java`
  - `mc-1.21.1/neoforge/src/main/java/com/mapsyncer/network/impl/NeoForgeNetworkHandler.java`
  - `mc-1.21.11/fabric/src/main/java/com/mapsyncer/network/FabricPayloadAdapters.java`
  - `mc-1.21.11/fabric/src/main/java/com/mapsyncer/network/impl/FabricNetworkHandler.java`
  - `mc-1.21.11/forge/src/main/java/com/mapsyncer/network/ForgePayloadAdapters.java`
  - `mc-1.21.11/forge/src/main/java/com/mapsyncer/network/impl/ForgeNetworkHandler.java`
  - `mc-1.21.11/neoforge/src/main/java/com/mapsyncer/network/NeoForgePayloadAdapters.java`
  - `mc-1.21.11/neoforge/src/main/java/com/mapsyncer/network/impl/NeoForgeNetworkHandler.java`
  - `mc-26.1/fabric/src/main/java/com/mapsyncer/network/FabricPayloadAdapters.java`
  - `mc-26.1/fabric/src/main/java/com/mapsyncer/network/impl/FabricNetworkHandler.java`
  - `mc-26.1/neoforge/src/main/java/com/mapsyncer/network/NeoForgePayloadAdapters.java`
  - `mc-26.1/neoforge/src/main/java/com/mapsyncer/network/impl/NeoForgeNetworkHandler.java`

Test:
- `libs/platform-api/src/test/java/com/mapsyncer/sync/RegionFreshnessDeciderTest.java`
- `libs/core/src/test/java/com/mapsyncer/util/UuidWhitelistFileTest.java`

## Task 1: Add Pure Java Test Harness

**Files:**
- Modify: `libs/core/build.gradle`
- Modify: `libs/platform-api/build.gradle`

- [ ] **Step 1: Add JUnit dependencies to `libs/core/build.gradle`**

Add this inside `dependencies`:

```groovy
testImplementation 'org.junit.jupiter:junit-jupiter-api:5.10.2'
testRuntimeOnly 'org.junit.jupiter:junit-jupiter-engine:5.10.2'
```

Add this after `tasks.withType(JavaCompile).configureEach`:

```groovy
tasks.withType(Test).configureEach {
    useJUnitPlatform()
}
```

- [ ] **Step 2: Add JUnit dependencies to `libs/platform-api/build.gradle`**

Add this inside `dependencies`:

```groovy
testImplementation 'org.junit.jupiter:junit-jupiter-api:5.10.2'
testRuntimeOnly 'org.junit.jupiter:junit-jupiter-engine:5.10.2'
```

Add this after `tasks.withType(JavaCompile).configureEach`:

```groovy
tasks.withType(Test).configureEach {
    useJUnitPlatform()
}
```

- [ ] **Step 3: Run empty test tasks**

Run:

```powershell
.\gradlew :libs:core:test :libs:platform-api:test
```

Expected: both tasks finish with `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```powershell
git add libs/core/build.gradle libs/platform-api/build.gradle
git commit -m "test: 添加纯 Java 测试基础" -m "为 core 与 platform-api 模块接入 JUnit Jupiter，便于先覆盖同步判新和白名单等平台无关逻辑。"
```

## Task 2: Add Sync Mode and Contribution Scope Enums

**Files:**
- Create: `libs/platform-api/src/main/java/com/mapsyncer/config/ClientSyncMode.java`
- Create: `libs/platform-api/src/main/java/com/mapsyncer/config/ContributionScope.java`

- [ ] **Step 1: Create `ClientSyncMode`**

```java
package com.mapsyncer.config;

import java.util.Locale;

/**
 * 客户端同步能力模式。
 */
public enum ClientSyncMode {
    DISABLED,
    RECEIVE_ONLY,
    BIDIRECTIONAL;

    public boolean allowsReceive() {
        return this != DISABLED;
    }

    public boolean allowsContribution() {
        return this == BIDIRECTIONAL;
    }

    public static ClientSyncMode fromConfig(String value, ClientSyncMode fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return ClientSyncMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
```

- [ ] **Step 2: Create `ContributionScope`**

```java
package com.mapsyncer.config;

import java.util.Locale;

/**
 * 服务端接受客户端地图贡献的权限范围。
 */
public enum ContributionScope {
    DISABLED,
    OPS,
    WHITELIST,
    OPS_AND_WHITELIST,
    ALL;

    public boolean allowsAnyContributor() {
        return this == ALL;
    }

    public boolean allowsWhitelist() {
        return this == WHITELIST || this == OPS_AND_WHITELIST;
    }

    public boolean allowsOperators() {
        return this == OPS || this == OPS_AND_WHITELIST;
    }

    public static ContributionScope fromConfig(String value, ContributionScope fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return ContributionScope.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
```

- [ ] **Step 3: Compile platform API**

Run:

```powershell
.\gradlew :libs:platform-api:compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```powershell
git add libs/platform-api/src/main/java/com/mapsyncer/config/ClientSyncMode.java libs/platform-api/src/main/java/com/mapsyncer/config/ContributionScope.java
git commit -m "feat: 添加双向同步配置枚举" -m "新增客户端同步模式和服务端贡献范围枚举，为配置系统和同步状态机提供稳定类型。"
```

## Task 3: Add Region Freshness Rules With Tests

**Files:**
- Create: `libs/platform-api/src/test/java/com/mapsyncer/sync/RegionFreshnessDeciderTest.java`
- Create: `libs/platform-api/src/main/java/com/mapsyncer/sync/RegionFreshnessDecision.java`
- Create: `libs/platform-api/src/main/java/com/mapsyncer/sync/RegionFreshnessDecider.java`

- [ ] **Step 1: Write failing tests**

```java
package com.mapsyncer.sync;

import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RegionFreshnessDeciderTest {
    @Test
    void hashMatchSkipsBothDirections() {
        var server = new TimestampHashEntry(100, "abc");
        var client = new ClientMeta(200, "abc");
        var decision = RegionFreshnessDecider.decide(server, client);
        assertEquals(RegionFreshnessDecision.Action.SKIP_HASH_MATCH, decision.action());
        assertFalse(decision.shouldDownloadToClient());
        assertFalse(decision.shouldRequestContribution());
    }

    @Test
    void missingClientDownloadsServerRegion() {
        var server = new TimestampHashEntry(100, "server");
        var decision = RegionFreshnessDecider.decide(server, null);
        assertTrue(decision.shouldDownloadToClient());
        assertFalse(decision.shouldRequestContribution());
    }

    @Test
    void olderClientDownloadsServerRegion() {
        var server = new TimestampHashEntry(200, "server");
        var client = new ClientMeta(100, "client");
        var decision = RegionFreshnessDecider.decide(server, client);
        assertEquals(RegionFreshnessDecision.Action.DOWNLOAD_SERVER_NEWER, decision.action());
    }

    @Test
    void newerClientRequestsContribution() {
        var server = new TimestampHashEntry(100, "server");
        var client = new ClientMeta(200, "client");
        var decision = RegionFreshnessDecider.decide(server, client);
        assertTrue(decision.shouldRequestContribution());
        assertFalse(decision.shouldDownloadToClient());
    }

    @Test
    void missingServerWithClientDoesNotDownloadAndRequestsContribution() {
        var client = new ClientMeta(200, "client");
        var decision = RegionFreshnessDecider.decide(null, client);
        assertEquals(RegionFreshnessDecision.Action.REQUEST_CLIENT_CONTRIBUTION, decision.action());
    }
}
```

- [ ] **Step 2: Run tests and verify failure**

Run:

```powershell
.\gradlew :libs:platform-api:test --tests com.mapsyncer.sync.RegionFreshnessDeciderTest
```

Expected: compile fails because `RegionFreshnessDecider` and `RegionFreshnessDecision` do not exist.

- [ ] **Step 3: Add `RegionFreshnessDecision`**

```java
package com.mapsyncer.sync;

public record RegionFreshnessDecision(Action action) {
    public enum Action {
        SKIP_NO_DATA,
        SKIP_HASH_MATCH,
        DOWNLOAD_SERVER_NEWER,
        REQUEST_CLIENT_CONTRIBUTION
    }

    public boolean shouldDownloadToClient() {
        return action == Action.DOWNLOAD_SERVER_NEWER;
    }

    public boolean shouldRequestContribution() {
        return action == Action.REQUEST_CLIENT_CONTRIBUTION;
    }
}
```

- [ ] **Step 4: Add `RegionFreshnessDecider`**

```java
package com.mapsyncer.sync;

import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry;

public final class RegionFreshnessDecider {
    private RegionFreshnessDecider() {}

    public static RegionFreshnessDecision decide(TimestampHashEntry serverMeta, ClientMeta clientMeta) {
        if (serverMeta == null && clientMeta == null) {
            return new RegionFreshnessDecision(RegionFreshnessDecision.Action.SKIP_NO_DATA);
        }
        if (serverMeta == null) {
            return new RegionFreshnessDecision(RegionFreshnessDecision.Action.REQUEST_CLIENT_CONTRIBUTION);
        }
        if (clientMeta == null) {
            return new RegionFreshnessDecision(RegionFreshnessDecision.Action.DOWNLOAD_SERVER_NEWER);
        }
        if (serverMeta.hash().equals(clientMeta.hash())) {
            return new RegionFreshnessDecision(RegionFreshnessDecision.Action.SKIP_HASH_MATCH);
        }
        if (clientMeta.timestampSeconds() > serverMeta.timestampSeconds()) {
            return new RegionFreshnessDecision(RegionFreshnessDecision.Action.REQUEST_CLIENT_CONTRIBUTION);
        }
        return new RegionFreshnessDecision(RegionFreshnessDecision.Action.DOWNLOAD_SERVER_NEWER);
    }
}
```

- [ ] **Step 5: Run tests and verify pass**

Run:

```powershell
.\gradlew :libs:platform-api:test --tests com.mapsyncer.sync.RegionFreshnessDeciderTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```powershell
git add libs/platform-api/src/main/java/com/mapsyncer/sync libs/platform-api/src/test/java/com/mapsyncer/sync
git commit -m "test: 覆盖 region 判新规则" -m "新增平台无关的新鲜度判定器，明确服务端下发、客户端贡献和哈希一致跳过的基础规则。"
```

## Task 4: Add UUID Whitelist JSON Utility

**Files:**
- Create: `libs/core/src/test/java/com/mapsyncer/util/UuidWhitelistFileTest.java`
- Create: `libs/core/src/main/java/com/mapsyncer/util/UuidWhitelistFile.java`

- [ ] **Step 1: Write failing whitelist tests**

```java
package com.mapsyncer.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UuidWhitelistFileTest {
    @TempDir Path tempDir;

    @Test
    void createsEmptyWhitelistWhenMissing() throws Exception {
        Path file = tempDir.resolve("mapsyncer-contributors.json");
        var whitelist = UuidWhitelistFile.loadOrCreate(file);
        assertTrue(Files.exists(file));
        assertTrue(whitelist.allowedContributors().isEmpty());
        assertTrue(Files.readString(file).contains("\"allowedContributors\": []"));
    }

    @Test
    void readsUuidValuesAndIgnoresInvalidValues() throws Exception {
        UUID uuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
        Path file = tempDir.resolve("mapsyncer-contributors.json");
        Files.writeString(file, """
            {
              "allowedContributors": [
                "11111111-2222-3333-4444-555555555555",
                "not-a-uuid"
              ]
            }
            """);
        var whitelist = UuidWhitelistFile.loadOrCreate(file);
        assertTrue(whitelist.contains(uuid));
        assertEquals(1, whitelist.allowedContributors().size());
    }
}
```

- [ ] **Step 2: Run tests and verify failure**

Run:

```powershell
.\gradlew :libs:core:test --tests com.mapsyncer.util.UuidWhitelistFileTest
```

Expected: compile fails because `UuidWhitelistFile` does not exist.

- [ ] **Step 3: Add `UuidWhitelistFile`**

```java
package com.mapsyncer.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record UuidWhitelistFile(Set<UUID> allowedContributors) {
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "\"([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\"");

    public UuidWhitelistFile {
        allowedContributors = Collections.unmodifiableSet(new LinkedHashSet<>(allowedContributors));
    }

    public boolean contains(UUID uuid) {
        return allowedContributors.contains(uuid);
    }

    public static UuidWhitelistFile loadOrCreate(Path file) {
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, "{\n  \"allowedContributors\": []\n}\n");
                return new UuidWhitelistFile(Set.of());
            }
            String json = Files.readString(file);
            Set<UUID> uuids = new LinkedHashSet<>();
            Matcher matcher = UUID_PATTERN.matcher(json);
            while (matcher.find()) {
                try {
                    uuids.add(UUID.fromString(matcher.group(1)));
                } catch (IllegalArgumentException ignored) {
                    // Regex already filters UUID shape; keep this guard for defensive parsing.
                }
            }
            return new UuidWhitelistFile(uuids);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read contributor whitelist: " + file, e);
        }
    }
}
```

- [ ] **Step 4: Run tests and verify pass**

Run:

```powershell
.\gradlew :libs:core:test --tests com.mapsyncer.util.UuidWhitelistFileTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```powershell
git add libs/core/src/main/java/com/mapsyncer/util/UuidWhitelistFile.java libs/core/src/test/java/com/mapsyncer/util/UuidWhitelistFileTest.java
git commit -m "feat: 添加贡献白名单 JSON 工具" -m "新增 UUID 白名单文件的首次创建和读取逻辑，为服务端贡献权限控制提供纯 Java 基础。"
```

## Task 5: Extend Platform Config Contract

**Files:**
- Modify: `libs/platform-api/src/main/java/com/mapsyncer/platform/Platform.java`

- [ ] **Step 1: Add imports**

```java
import com.mapsyncer.config.ClientSyncMode;
import com.mapsyncer.config.ContributionScope;
```

- [ ] **Step 2: Add config getters after `getClientHashThreads()`**

```java
/**
 * 获取客户端同步模式。
 */
ClientSyncMode getClientSyncMode();

/**
 * 获取后台元数据巡检间隔（分钟）。
 * 0 表示关闭后台巡检。
 */
int getBackgroundSyncIntervalMinutes();

/**
 * 获取服务端接受客户端贡献的权限范围。
 */
ContributionScope getContributionScope();

/**
 * 获取贡献队列任务间冷却期（秒）。
 */
int getContributionQueueCooldownSeconds();

/**
 * 获取最大贡献任务排队数量。
 */
int getMaxContributionQueueSize();
```

- [ ] **Step 3: Compile and capture expected platform failures**

Run:

```powershell
.\gradlew :libs:platform-api:compileJava
```

Expected: `BUILD SUCCESSFUL`.

Run:

```powershell
.\gradlew :mc-1.21.1:fabric:compileJava
```

Expected: compile fails listing platform classes that do not implement the new methods.

- [ ] **Step 4: Commit only Platform contract**

```powershell
git add libs/platform-api/src/main/java/com/mapsyncer/platform/Platform.java
git commit -m "feat: 扩展同步配置平台接口" -m "在 Platform 抽象中加入客户端同步模式、后台巡检和服务端贡献队列配置访问方法。"
```

## Task 6: Add Config Fields to Each Loader

**Files:**
- Modify all `ModConfig.java` and platform implementation files listed in File Structure under Platform config files.

- [ ] **Step 1: Add Fabric properties fields**

In each Fabric `ModConfig.ClientConfig`, add fields:

```java
private volatile ClientSyncMode clientSyncMode = ClientSyncMode.RECEIVE_ONLY;
private volatile int backgroundSyncIntervalMinutes = 60;
```

In `load()`, parse:

```java
clientSyncMode = ClientSyncMode.fromConfig(props.getProperty("clientSyncMode"), ClientSyncMode.RECEIVE_ONLY);
backgroundSyncIntervalMinutes = Math.max(0, Math.min(1440,
        Integer.parseInt(props.getProperty("backgroundSyncIntervalMinutes", "60"))));
```

In `save()`, write bilingual comments before each property. Include meaning, effect, allowed values, and default reason:

```java
sb.append("# Client sync mode.\n");
sb.append("# 客户端同步模式。\n");
sb.append("# DISABLED disables automatic sync, background checks, manual receive sync, and upload contributions on this client.\n");
sb.append("# DISABLED 会禁用此客户端的自动同步、后台巡检、手动接收同步和上传贡献。\n");
sb.append("# RECEIVE_ONLY receives newer authoritative regions from the server but never uploads local regions.\n");
sb.append("# RECEIVE_ONLY 只接收服务端较新的权威 region，不上传本地 region。\n");
sb.append("# BIDIRECTIONAL receives server updates and uploads newer local regions when the server allows contributions.\n");
sb.append("# BIDIRECTIONAL 会接收服务端更新，并在服务端允许时上传本地较新的 region。\n");
sb.append("# Default: RECEIVE_ONLY. Safe for public servers because clients do not contribute unless they opt in.\n");
sb.append("# 默认：RECEIVE_ONLY。这个默认值适合公开服务器，因为客户端不会在未主动开启时贡献数据。\n");
```

Then append:

```java
sb.append("clientSyncMode=" + clientSyncMode.name() + "\n");
sb.append("\n");
sb.append("# Background metadata check interval in minutes.\n");
sb.append("# 后台元数据巡检间隔（分钟）。\n");
sb.append("# 0 disables periodic checks. Positive values periodically run metadata negotiation while connected.\n");
sb.append("# 0 表示关闭周期巡检；正数表示在线时周期执行元数据协商流程。\n");
sb.append("backgroundSyncIntervalMinutes=" + backgroundSyncIntervalMinutes + "\n");
```

Add getters/setters:

```java
public ClientSyncMode getClientSyncMode() { return clientSyncMode; }
public void setClientSyncMode(ClientSyncMode value) { clientSyncMode = value; }
public int getBackgroundSyncIntervalMinutes() { return backgroundSyncIntervalMinutes; }
public void setBackgroundSyncIntervalMinutes(int value) {
    backgroundSyncIntervalMinutes = Math.max(0, Math.min(1440, value));
}
```

- [ ] **Step 2: Add Fabric server fields**

In each Fabric `ModConfig.ServerConfig`, add:

```java
private volatile ContributionScope contributionScope = ContributionScope.WHITELIST;
private volatile int contributionQueueCooldownSeconds = 10;
private volatile int maxContributionQueueSize = 32;
```

Parse in `load()`:

```java
contributionScope = ContributionScope.fromConfig(props.getProperty("contributionScope"), ContributionScope.WHITELIST);
contributionQueueCooldownSeconds = Math.max(0, Math.min(3600,
        Integer.parseInt(props.getProperty("contributionQueueCooldownSeconds", "10"))));
maxContributionQueueSize = Math.max(1, Math.min(1024,
        Integer.parseInt(props.getProperty("maxContributionQueueSize", "32"))));
```

Save with bilingual comments before each property. Include meaning, effect, allowed values, and default reason:

```java
sb.append("# Server contribution permission scope.\n");
sb.append("# 服务端接受客户端贡献的权限范围。\n");
sb.append("# DISABLED refuses all client uploads.\n");
sb.append("# DISABLED 拒绝所有客户端上传。\n");
sb.append("# OPS allows server operators to contribute.\n");
sb.append("# OPS 允许服务器管理员贡献。\n");
sb.append("# WHITELIST allows UUIDs listed in <world>/serverconfig/mapsyncer-contributors.json.\n");
sb.append("# WHITELIST 允许 <world>/serverconfig/mapsyncer-contributors.json 中记录的 UUID 贡献。\n");
sb.append("# OPS_AND_WHITELIST allows either operators or whitelisted UUIDs.\n");
sb.append("# OPS_AND_WHITELIST 允许管理员或白名单 UUID 贡献。\n");
sb.append("# ALL allows every player to contribute. Use only on trusted servers.\n");
sb.append("# ALL 允许所有玩家贡献。仅建议在可信服务器使用。\n");
sb.append("# Default: WHITELIST. This keeps contribution opt-in and world-specific.\n");
sb.append("# 默认：WHITELIST。该默认值使贡献保持显式授权且按世界隔离。\n");
sb.append("contributionScope=" + contributionScope.name() + "\n");
sb.append("\n");
sb.append("# Cooldown in seconds between completed contribution sessions.\n");
sb.append("# 每个贡献会话完成后的冷却期（秒）。\n");
sb.append("# This serializes bursts from multiple players and reduces cache write contention.\n");
sb.append("# 用于串行化多玩家同时贡献，降低缓存写入竞争。\n");
sb.append("contributionQueueCooldownSeconds=" + contributionQueueCooldownSeconds + "\n");
sb.append("\n");
sb.append("# Maximum number of queued contribution sessions.\n");
sb.append("# 最大贡献会话排队数量。\n");
sb.append("# New contribution requests are rejected with queue_full when this limit is reached.\n");
sb.append("# 达到该限制后，新贡献请求会以 queue_full 拒绝。\n");
sb.append("maxContributionQueueSize=" + maxContributionQueueSize + "\n");
```

Add getters/setters:

```java
public ContributionScope getContributionScope() { return contributionScope; }
public void setContributionScope(ContributionScope value) { contributionScope = value; }
public int getContributionQueueCooldownSeconds() { return contributionQueueCooldownSeconds; }
public void setContributionQueueCooldownSeconds(int value) {
    contributionQueueCooldownSeconds = Math.max(0, Math.min(3600, value));
}
public int getMaxContributionQueueSize() { return maxContributionQueueSize; }
public void setMaxContributionQueueSize(int value) {
    maxContributionQueueSize = Math.max(1, Math.min(1024, value));
}
```

- [ ] **Step 3: Add Forge and NeoForge ConfigSpec fields**

In each Forge/NeoForge `ClientConfig`, add:

```java
public final EnumValue<ClientSyncMode> clientSyncMode;
public final IntValue backgroundSyncIntervalMinutes;
```

Define them in the `client` section using bilingual comments from the spec:

```java
clientSyncMode = builder
        .comment("Client sync mode.",
                 "客户端同步模式。",
                 "DISABLED disables automatic sync, background checks, manual receive sync, and upload contributions on this client.",
                 "DISABLED 会禁用此客户端的自动同步、后台巡检、手动接收同步和上传贡献。",
                 "RECEIVE_ONLY receives newer authoritative regions from the server but never uploads local regions.",
                 "RECEIVE_ONLY 只接收服务端较新的权威 region，不上传本地 region。",
                 "BIDIRECTIONAL receives server updates and uploads newer local regions when the server allows contributions.",
                 "BIDIRECTIONAL 会接收服务端更新，并在服务端允许时上传本地较新的 region。",
                 "Default: RECEIVE_ONLY. This is safe for public servers because clients do not contribute unless they opt in.",
                 "默认：RECEIVE_ONLY。这个默认值适合公开服务器，因为客户端不会在未主动开启时贡献数据。")
        .defineEnum("clientSyncMode", ClientSyncMode.RECEIVE_ONLY);

backgroundSyncIntervalMinutes = builder
        .comment("Background metadata check interval in minutes.", "后台元数据巡检间隔（分钟）。",
                 "0 disables periodic checks. Positive values periodically run metadata negotiation.",
                 "0 表示关闭周期巡检；正数表示周期执行元数据协商流程。")
        .defineInRange("backgroundSyncIntervalMinutes", 60, 0, 1440);
```

In each Forge/NeoForge `ServerConfig`, add and define the contribution fields inside a dedicated section:

```java
builder.push("contribution");
// define contributionScope, contributionQueueCooldownSeconds, maxContributionQueueSize here
builder.pop();
```

The enum constant names are serialized config values; do not rename existing constants in later migrations. Add fields:

```java
public final EnumValue<ContributionScope> contributionScope;
public final IntValue contributionQueueCooldownSeconds;
public final IntValue maxContributionQueueSize;
```

Use `WHITELIST`, `10`, and `32` defaults with the same complete English and Chinese comment content described above.

- [ ] **Step 4: Add Platform method implementations**

In every platform implementation listed in File Structure, implement:

```java
@Override
public ClientSyncMode getClientSyncMode() {
    return ModConfig.CLIENT().getClientSyncMode();
}

@Override
public int getBackgroundSyncIntervalMinutes() {
    return ModConfig.CLIENT().getBackgroundSyncIntervalMinutes();
}

@Override
public ContributionScope getContributionScope() {
    return ModConfig.SERVER().getContributionScope();
}

@Override
public int getContributionQueueCooldownSeconds() {
    return ModConfig.SERVER().getContributionQueueCooldownSeconds();
}

@Override
public int getMaxContributionQueueSize() {
    return ModConfig.SERVER().getMaxContributionQueueSize();
}
```

For Forge/NeoForge `ConfigSpec` implementations use direct `.get()` calls:

```java
return ModConfig.CLIENT.clientSyncMode.get();
return ModConfig.CLIENT.backgroundSyncIntervalMinutes.get();
return ModConfig.SERVER.contributionScope.get();
return ModConfig.SERVER.contributionQueueCooldownSeconds.get();
return ModConfig.SERVER.maxContributionQueueSize.get();
```

- [ ] **Step 5: Update Fabric config GUI**

In each Fabric `ConfigScreenFactory.createClientConfigScreen`, add:

```java
client.addEntry(entryBuilder.startSelector(
        Component.translatable("option.mapsyncer.client_sync_mode"),
        ClientSyncMode.values(),
        config.getClientSyncMode())
    .setDefaultValue(ClientSyncMode.RECEIVE_ONLY)
    .setTooltip(Component.translatable("option.mapsyncer.client_sync_mode.tooltip"))
    .setSaveConsumer(config::setClientSyncMode)
    .build());

client.addEntry(entryBuilder.startIntSlider(
        Component.translatable("option.mapsyncer.background_sync_interval"),
        config.getBackgroundSyncIntervalMinutes(), 0, 1440)
    .setDefaultValue(60)
    .setTooltip(Component.translatable("option.mapsyncer.background_sync_interval.tooltip"))
    .setSaveConsumer(config::setBackgroundSyncIntervalMinutes)
    .build());
```

In `createServerConfigScreen`, add a dedicated `category.mapsyncer.contribution` category and server contribution controls for `ContributionScope`, cooldown, and queue size.

- [ ] **Step 6: Compile canonical targets**

Run:

```powershell
.\gradlew :mc-1.20.1:fabric:compileJava :mc-1.20.1:forge:compileJava :mc-1.21.1:fabric:compileJava :mc-1.21.1:forge:compileJava :mc-1.21.1:neoforge:compileJava :mc-1.21.11:fabric:compileJava :mc-1.21.11:forge:compileJava :mc-1.21.11:neoforge:compileJava :mc-26.1:fabric:compileJava :mc-26.1:neoforge:compileJava
```

Expected: all touched config targets compile. If a module is intentionally absent from `settings.gradle`, record it in the task notes and compile it through the matching fastbuild target in Task 15.

- [ ] **Step 7: Commit**

```powershell
git add libs/platform-api/src/main/java/com/mapsyncer/config mc-*/fabric/src/main/java/com/mapsyncer/config/ModConfig.java mc-*/fabric/src/main/java/com/mapsyncer/client/ConfigScreenFactory.java mc-*/fabric/src/main/java/com/mapsyncer/platform/impl/*.java mc-*/forge/src/main/java/com/mapsyncer/config/ModConfig.java mc-*/forge/src/main/java/com/mapsyncer/platform/impl/*.java mc-*/neoforge/src/main/java/com/mapsyncer/config/ModConfig.java mc-*/neoforge/src/main/java/com/mapsyncer/platform/impl/*.java
git commit -m "feat: 添加双向同步配置项" -m "为客户端同步模式、后台巡检、服务端贡献范围、贡献队列冷却和最大排队数量接入各加载器配置系统。"
```

## Task 7: Add Contribution Payload DTOs and Network Contract

**Files:**
- Create payload files listed in File Structure.
- Modify: `libs/platform-api/src/main/java/com/mapsyncer/network/NetworkHandler.java`
- Modify: `libs/platform-api/src/main/java/com/mapsyncer/network/NetworkManager.java`

- [ ] **Step 1: Create payload DTOs**

Use these records:

```java
public record ContributionRegionMeta(
        String relativePath,
        int regionX,
        int regionZ,
        String dimension,
        int caveLayer,
        long serverTimestampSeconds,
        String serverHash) {}
```

```java
public record ContributionRequestPayload(
        int requestId,
        List<ContributionRegionMeta> regions,
        String status) {
    public static final String ID = NetworkHandler.CONTRIBUTION_REQUEST_ID;
}
```

```java
public record ContributionDataPayload(
        int requestId,
        ChunkMapData chunk,
        String relativePath,
        long observedServerTimestampSeconds,
        String observedServerHash) {
    public static final String ID = NetworkHandler.CONTRIBUTION_DATA_ID;
}
```

`ContributionDataPayload` carries one `ChunkMapData` fragment. Large regions must be split with `ChunkMapData.split(original)` and reassembled by the server before validation.

```java
public record ContributionCompletePayload(
        int requestId,
        int sentRegions,
        String status) {
    public static final String ID = NetworkHandler.CONTRIBUTION_COMPLETE_ID;
}
```

```java
public record ContributionResultPayload(
        int requestId,
        int accepted,
        int rejected,
        String status) {
    public static final String ID = NetworkHandler.CONTRIBUTION_RESULT_ID;
}
```

- [ ] **Step 2: Extend `NetworkHandler` IDs and methods**

Add IDs:

```java
String CONTRIBUTION_REQUEST_ID = "contribution_request";
String CONTRIBUTION_DATA_ID = "contribution_data";
String CONTRIBUTION_COMPLETE_ID = "contribution_complete";
String CONTRIBUTION_RESULT_ID = "contribution_result";
```

Add send methods:

```java
void sendToServer(ContributionDataPayload payload);
void sendToServer(ContributionCompletePayload payload);
void sendToPlayer(PLAYER_TYPE player, ContributionRequestPayload payload);
void sendToPlayer(PLAYER_TYPE player, ContributionResultPayload payload);
```

Add handler registration:

```java
void registerContributionRequestHandler(BiConsumer<ContributionRequestPayload, PayloadContext> handler);
void registerContributionDataHandler(BiConsumer<ContributionDataPayload, PayloadContext> handler);
void registerContributionCompleteHandler(BiConsumer<ContributionCompletePayload, PayloadContext> handler);
void registerContributionResultHandler(BiConsumer<ContributionResultPayload, PayloadContext> handler);
```

- [ ] **Step 3: Extend `NetworkManager` delegates**

Add static methods matching the new interface methods and delegate to `getHandler()`.

- [ ] **Step 4: Compile platform API**

Run:

```powershell
.\gradlew :libs:platform-api:compileJava
```

Expected: compile succeeds for platform-api; platform modules fail until adapter tasks are complete.

- [ ] **Step 5: Commit**

```powershell
git add libs/platform-api/src/main/java/com/mapsyncer/network
git commit -m "feat: 添加地图贡献网络协议 DTO" -m "新增服务端请求上传、客户端贡献分片、贡献完成和贡献结果 payload，并扩展跨平台网络接口。"
```

## Task 8: Implement Platform Network Adapters

**Files:**
- Modify all platform network files listed in File Structure.

- [ ] **Step 1: Add modern Fabric payload types and codecs**

For Fabric 1.21.1, 1.21.11, and 26.1 adapters, add wrapper records and codecs for `ContributionRequestPayload`, `ContributionDataPayload`, `ContributionCompletePayload`, and `ContributionResultPayload`. Reuse existing `ChunkMapData` encode/decode logic for `ContributionDataPayload.chunk()`.

The contribution data encoder must write:

```java
buf.writeInt(payload.data.requestId());
encodeChunkMapData(buf, payload.data.chunk());
buf.writeUtf(payload.data.relativePath());
buf.writeLong(payload.data.observedServerTimestampSeconds());
buf.writeUtf(payload.data.observedServerHash());
```

The decoder must read in the same order.

- [ ] **Step 2: Add Fabric 1.20.1 legacy network adapters**

For `mc-1.20.1/fabric`, do not use `PayloadTypeRegistry` or `StreamCodec`. Add four new `ResourceLocation` channel IDs in `FabricPayloadAdapters` for request, data, complete, and result. Encode/decode with `FriendlyByteBuf` methods matching the DTO field order. Register:

- server receiver for `CONTRIBUTION_DATA` and `CONTRIBUTION_COMPLETE` in `FabricNetworkHandler`;
- client receivers for `CONTRIBUTION_REQUEST` and `CONTRIBUTION_RESULT` in `FabricClientNetworkHandler`;
- send methods in both handler classes matching `NetworkHandler`.

- [ ] **Step 3: Register modern Fabric payloads**

In each `FabricNetworkHandler.registerPayloadTypes()`:

```java
PayloadTypeRegistry.playS2C().register(FabricPayloadAdapters.CONTRIBUTION_REQUEST_TYPE,
        FabricPayloadAdapters.CONTRIBUTION_REQUEST_CODEC);
PayloadTypeRegistry.playC2S().register(FabricPayloadAdapters.CONTRIBUTION_DATA_TYPE,
        FabricPayloadAdapters.CONTRIBUTION_DATA_CODEC);
PayloadTypeRegistry.playC2S().register(FabricPayloadAdapters.CONTRIBUTION_COMPLETE_TYPE,
        FabricPayloadAdapters.CONTRIBUTION_COMPLETE_CODEC);
PayloadTypeRegistry.playS2C().register(FabricPayloadAdapters.CONTRIBUTION_RESULT_TYPE,
        FabricPayloadAdapters.CONTRIBUTION_RESULT_CODEC);
```

Register server receivers for contribution data/complete and client receivers for request/result. Add handler fields and send methods matching `NetworkHandler`.

- [ ] **Step 4: Add Forge messages**

In each `ForgePayloadAdapters`, add message classes for the four contribution payloads. Use message IDs after existing IDs:

```java
CONTRIBUTION_REQUEST = 4
CONTRIBUTION_DATA = 5
CONTRIBUTION_COMPLETE = 6
CONTRIBUTION_RESULT = 7
```

In `ForgeNetworkHandler.init()`, register all four messages with matching directions.

- [ ] **Step 5: Add NeoForge payload adapters**

In each `NeoForgePayloadAdapters`, add `CustomPacketPayload` records and `StreamCodec`s for the four new DTOs. Register them in each `NeoForgeNetworkHandler`.

- [ ] **Step 6: Compile adapter targets**

Run:

```powershell
.\gradlew :mc-1.20.1:fabric:compileJava :mc-1.20.1:forge:compileJava :mc-1.21.1:fabric:compileJava :mc-1.21.1:forge:compileJava :mc-1.21.1:neoforge:compileJava :mc-1.21.11:fabric:compileJava :mc-1.21.11:forge:compileJava :mc-1.21.11:neoforge:compileJava :mc-26.1:fabric:compileJava :mc-26.1:neoforge:compileJava
```

Expected: all touched network adapters compile, with 1.20.1 Fabric using the legacy channel path.

- [ ] **Step 7: Commit**

```powershell
git add mc-*/fabric/src/main/java/com/mapsyncer/network mc-*/forge/src/main/java/com/mapsyncer/network mc-*/neoforge/src/main/java/com/mapsyncer/network
git commit -m "feat: 接入贡献协议平台网络适配" -m "为 Fabric、Forge、NeoForge 注册贡献请求、贡献分片、贡献完成和贡献结果 payload，使双向同步协议可跨加载器传输。"
```

## Task 9: Add Contribution Validator

**Files:**
- Create: `libs/common/src/main/java/com/mapsyncer/server/ContributionValidator.java`

- [ ] **Step 1: Add validator class**

```java
package com.mapsyncer.server;

import com.mapsyncer.network.payload.ContributionRegionMeta;
import com.mapsyncer.util.HashUtils;
import com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipInputStream;

public final class ContributionValidator {
    private ContributionValidator() {}

    public record Result(boolean accepted, String reason, long acceptedTimestampSeconds) {}

    public static Result validate(
            ContributionRegionMeta expected,
            byte[] fullData,
            long candidateTimestampSeconds,
            GenerationCache cache,
            Path cacheDir) {
        if (expected == null) {
            return new Result(false, "unexpected_region", 0);
        }
        if (fullData == null || fullData.length == 0) {
            return new Result(false, "empty_data", 0);
        }
        if (expected.relativePath() == null || expected.relativePath().isBlank()) {
            return new Result(false, "empty_path", 0);
        }
        if (expected.relativePath().contains("..") || expected.relativePath().startsWith("/") || expected.relativePath().startsWith("\\")) {
            return new Result(false, "unsafe_path", 0);
        }
        if (!expected.relativePath().endsWith(expected.regionX() + "_" + expected.regionZ())) {
            return new Result(false, "path_coord_mismatch", 0);
        }
        String actualHash = HashUtils.computeHash(fullData);
        if (!HashUtils.isValidHash(actualHash)) {
            return new Result(false, "invalid_hash", 0);
        }
        if (!isValidXaeroZip(fullData)) {
            return new Result(false, "invalid_zip", 0);
        }

        TimestampHashEntry current = cache.getMeta(expected.relativePath());
        if (!matchesObservedServerState(current, expected)) {
            return new Result(false, "server_changed", 0);
        }
        if (current != null && current.hash().equals(actualHash)) {
            return new Result(false, "same_hash", 0);
        }
        if (current != null && candidateTimestampSeconds <= current.timestampSeconds()) {
            return new Result(false, "not_newer", 0);
        }
        long serverAcceptedTimestampSeconds = Math.max(System.currentTimeMillis() / 1000,
                current == null ? 1 : current.timestampSeconds() + 1);
        return new Result(true, "accepted", serverAcceptedTimestampSeconds);
    }

    private static boolean matchesObservedServerState(TimestampHashEntry current, ContributionRegionMeta expected) {
        if (current == null) {
            return expected.serverTimestampSeconds() == 0 && HashUtils.DEFAULT_HASH.equals(expected.serverHash());
        }
        return current.timestampSeconds() == expected.serverTimestampSeconds()
                && current.hash().equals(expected.serverHash());
    }

    private static boolean isValidXaeroZip(byte[] data) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
            var entry = zis.getNextEntry();
            while (entry != null) {
                if ("region.xaero".equals(entry.getName())) {
                    return true;
                }
                entry = zis.getNextEntry();
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }
}
```

This validator only accepts fully assembled region bytes. Raw `ContributionDataPayload` fragments must be assembled by `ContributionUploadAssembler` before calling it.

The `candidateTimestampSeconds` may come from a client logical timestamp cache or, for client-only uncached regions, a local mtime hint. It is used only to decide whether the upload is worth considering. The server writes `acceptedTimestampSeconds`, not the client mtime, into `generation_cache.properties`.

- [ ] **Step 2: Compile a canonical shared target**

Run:

```powershell
.\gradlew :mc-1.21.1:fabric:compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```powershell
git add libs/common/src/main/java/com/mapsyncer/server/ContributionValidator.java
git commit -m "feat: 添加客户端贡献数据校验器" -m "新增服务端上传校验逻辑，覆盖路径安全、坐标匹配、Xaero zip 结构、哈希有效性和最新性判定。"
```

## Task 10: Add Server Contribution Coordinator

**Files:**
- Create: `libs/common/src/main/java/com/mapsyncer/server/ContributionCoordinator.java`
- Create: `libs/common/src/main/java/com/mapsyncer/server/ContributionSession.java`
- Create: `libs/common/src/main/java/com/mapsyncer/server/ContributionUploadAssembler.java`
- Modify: version shared `PlayerJoinHandlerLogic.java` files.

- [ ] **Step 1: Add session state**

```java
package com.mapsyncer.server;

import com.mapsyncer.network.payload.ContributionRegionMeta;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class ContributionSession {
    private final int requestId;
    private final UUID playerId;
    private final String playerName;
    private final Map<String, ContributionRegionMeta> expectedRegions;
    private int accepted;
    private int rejected;
    private boolean complete;

    public ContributionSession(int requestId, ServerPlayer player, java.util.List<ContributionRegionMeta> regions) {
        this.requestId = requestId;
        this.playerId = player.getUUID();
        this.playerName = player.getName().getString();
        this.expectedRegions = new LinkedHashMap<>();
        for (ContributionRegionMeta region : regions) {
            this.expectedRegions.put(region.relativePath(), region);
        }
    }

    public int requestId() { return requestId; }
    public UUID playerId() { return playerId; }
    public String playerName() { return playerName; }
    public Map<String, ContributionRegionMeta> expectedRegions() { return expectedRegions; }
    public boolean isComplete() { return complete; }
    public void markComplete() { complete = true; }
    public void markAccepted() { accepted++; }
    public void markRejected() { rejected++; }
    public int accepted() { return accepted; }
    public int rejected() { return rejected; }
}
```

- [ ] **Step 2: Add upload assembler**

Create an assembler that groups `ContributionDataPayload` fragments by `requestId + relativePath`, rejects duplicate or out-of-range `partIndex`, and returns full bytes only after all `totalParts` are present. It must clear assembled state after success or rejection.

- [ ] **Step 3: Add coordinator**

`ContributionCoordinator` must be synchronized around queue state. Do not use `ConcurrentLinkedQueue.size()` as a capacity gate. Use a private lock plus `ArrayDeque<ContributionSession>` so `maxContributionQueueSize` is checked atomically with enqueue.

Required behavior:

- `enqueueSession(ServerPlayer player, List<ContributionRegionMeta> candidates)` creates a `ContributionSession`, queues it if capacity allows, and starts the drain loop.
- The drain loop activates exactly one session at a time, sends `ContributionRequestPayload`, then waits for `ContributionCompletePayload`, player disconnect, timeout, or shutdown.
- `handleData(ServerPlayer player, ContributionDataPayload payload, Path cacheDir, GenerationCache cache)` accepts data only for the active session, only from the matching player UUID, and only for expected relative paths. It assembles chunks, rechecks permission, validates full bytes with `ContributionValidator`, then writes the accepted region atomically and updates `generation_cache.properties` using `Result.acceptedTimestampSeconds()`.
- `handleComplete(ServerPlayer player, ContributionCompletePayload payload)` marks the active session complete. If the client had no region to upload, this is a normal zero-accepted completion, not an error.
- Cooldown sleeps after the active session completes or times out, not after merely sending the request.
- Duplicate chunks, old request IDs, non-active sessions, and unexpected paths must receive `ContributionResultPayload` with a rejection status and must not write files.

- [ ] **Step 4: Wire cleanup**

In each shared `PlayerJoinHandlerLogic.onPlayerLeave`, add:

```java
ContributionCoordinator.cancelPlayer(playerId);
```

In each shared `PlayerJoinHandlerLogic.onServerStopped`, add:

```java
ContributionCoordinator.shutdown();
```

- [ ] **Step 5: Compile canonical target**

Run:

```powershell
.\gradlew :mc-1.21.1:fabric:compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```powershell
git add libs/common/src/main/java/com/mapsyncer/server/ContributionCoordinator.java libs/common/src/main/java/com/mapsyncer/server/ContributionSession.java libs/common/src/main/java/com/mapsyncer/server/ContributionUploadAssembler.java mc-*/shared/src/main/java/com/mapsyncer/server/PlayerJoinHandlerLogic.java
git commit -m "feat: 添加贡献队列协调器" -m "新增服务端贡献任务串行队列和冷却期，并在玩家离线和服务器停止时清理贡献任务。"
```

## Task 11: Add Server Contribution Phase

**Files:**
- Modify shared `ServerSyncHandlerLogic.java` files for all versions.
- Create: `libs/common/src/main/java/com/mapsyncer/server/ContributionWhitelistBridge.java`

- [ ] **Step 1: Register contribution data handler**

In `registerHandlers()`, add:

```java
NetworkManager.getHandler().registerContributionDataHandler(
    (payload, context) -> handleContributionData(payload, context)
);
NetworkManager.getHandler().registerContributionCompleteHandler(
    (payload, context) -> handleContributionComplete(payload, context)
);
```

- [ ] **Step 2: Collect contribution candidates after distribution**

When iterating server cache vs client metadata, build:

```java
List<ContributionRegionMeta> contributionCandidates = new ArrayList<>();
```

For each `RegionFreshnessDecision`:

```java
if (decision.shouldRequestContribution()) {
    contributionCandidates.add(new ContributionRegionMeta(
            normalizedPath,
            parsed.regionX(),
            parsed.regionZ(),
            parsed.dimension(),
            parsed.caveLayer(),
            serverMeta != null ? serverMeta.timestampSeconds() : 0,
            serverMeta != null ? serverMeta.hash() : HashUtils.DEFAULT_HASH));
}
```

After walking server cache entries, do a second pass over `clientMeta` keys that were not visited from the server side. Parse only valid region paths. For a client-only region, call `RegionFreshnessDecider.decide(null, clientMeta)` and add a candidate with `serverTimestampSeconds=0` and `serverHash=HashUtils.DEFAULT_HASH`. This is required for the first contribution of regions that exist only on a client.

- [ ] **Step 3: Send contribution request after final distribution response**

After the final `SyncResponsePayload` is sent, call:

```java
maybeQueueContributionSession(serverPlayer, contributionCandidates);
```

Add:

```java
private static void maybeQueueContributionSession(ServerPlayer player, List<ContributionRegionMeta> candidates) {
    if (candidates.isEmpty()) return;
    if (!isContributionAllowed(player)) return;
    boolean queued = ContributionCoordinator.enqueueSession(player, candidates);
    if (!queued) {
        NetworkManager.sendToPlayer(player, new ContributionResultPayload(0, 0, candidates.size(), "queue_full"));
    }
}
```

- [ ] **Step 4: Add permission check**

Create `ContributionWhitelistBridge`:

```java
package com.mapsyncer.server;

import com.mapsyncer.util.UuidWhitelistFile;
import com.mapsyncer.config.ContributionScope;
import com.mapsyncer.platform.PlatformManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.UUID;

public final class ContributionWhitelistBridge {
    private static final String FILE_NAME = "mapsyncer-contributors.json";

    private ContributionWhitelistBridge() {}

    public static boolean isWhitelisted(ServerPlayer player) {
        if (player == null || player.getServer() == null) {
            return false;
        }
        return isWhitelisted(player, player.getUUID());
    }

    public static boolean isWhitelisted(ServerPlayer player, UUID uuid) {
        if (player == null || player.getServer() == null || uuid == null) {
            return false;
        }
        Path worldPath = player.getServer().getWorldPath(LevelResource.LEVEL_DATA_FILE).getParent();
        Path whitelistPath = worldPath.resolve("serverconfig").resolve(FILE_NAME);
        return UuidWhitelistFile.loadOrCreate(whitelistPath).contains(uuid);
    }

    public static boolean isContributionAllowed(ServerPlayer player) {
        ContributionScope scope = PlatformManager.getPlatform().getContributionScope();
        if (scope == ContributionScope.DISABLED || player == null) return false;
        if (scope == ContributionScope.ALL) return true;
        boolean isOp = player.getServer() != null
                && player.getServer().getPlayerList().isOp(player.getGameProfile());
        boolean whitelisted = isWhitelisted(player);
        return switch (scope) {
            case OPS -> isOp;
            case WHITELIST -> whitelisted;
            case OPS_AND_WHITELIST -> isOp || whitelisted;
            default -> false;
        };
    }
}
```

The whitelist is a world-level authorization file at `<world>/serverconfig/mapsyncer-contributors.json`. It is intentionally separate from Fabric's global `config/mapsyncer-server.properties`, so different worlds can trust different UUIDs.

Then add:

```java
private static boolean isContributionAllowed(ServerPlayer player) {
    return ContributionWhitelistBridge.isContributionAllowed(player);
}
```

- [ ] **Step 5: Delegate contribution data and completion to the coordinator**

Add:

```java
private static void handleContributionData(ContributionDataPayload payload, PayloadContext context) {
    Player player = (Player) NetworkManager.getHandler().getPlayerFromContext(context);
    if (!(player instanceof ServerPlayer serverPlayer)) return;
    Path cacheDir = ConversionOrchestrator.getCacheDir();
    GenerationCache cache = GenerationCache.getInstance(cacheDir);
    ContributionCoordinator.handleData(serverPlayer, payload, cacheDir, cache);
}

private static void handleContributionComplete(ContributionCompletePayload payload, PayloadContext context) {
    Player player = (Player) NetworkManager.getHandler().getPlayerFromContext(context);
    if (player instanceof ServerPlayer serverPlayer) {
        ContributionCoordinator.handleComplete(serverPlayer, payload);
    }
}
```

`ContributionCoordinator.handleData` owns the atomic write implementation. It must write to a sibling `*.uploading` file, move with `REPLACE_EXISTING + ATOMIC_MOVE`, fall back to `REPLACE_EXISTING` when atomic move is unsupported, then update the generation cache with the validator's `acceptedTimestampSeconds()`.

- [ ] **Step 6: Compile canonical target**

Run:

```powershell
.\gradlew :mc-1.21.1:fabric:compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```powershell
git add mc-*/shared/src/main/java/com/mapsyncer/server/ServerSyncHandlerLogic.java libs/common/src/main/java/com/mapsyncer/server
git commit -m "feat: 添加服务端贡献阶段" -m "在服务端分发完成后排队请求客户端上传较新 region，并对贡献数据执行权限、路径、zip、哈希和时间戳校验。"
```

## Task 12: Add Client Contribution Collector and Handlers

**Files:**
- Create: `libs/common/src/main/java/com/mapsyncer/client/ClientContributionCollector.java`
- Modify shared `MapPacketHandler.java` files for all versions.

- [ ] **Step 1: Add collector**

```java
package com.mapsyncer.client;

import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.network.payload.ContributionCompletePayload;
import com.mapsyncer.network.payload.ContributionDataPayload;
import com.mapsyncer.network.payload.ContributionRegionMeta;
import com.mapsyncer.util.HashUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ClientContributionCollector {
    private ClientContributionCollector() {}

    public static List<ContributionDataPayload> collect(int requestId, ContributionRegionMeta meta, Path serverDir) {
        Path file = resolveClientRegion(serverDir, meta);
        if (!Files.exists(file)) return List.of();
        try {
            byte[] data = Files.readAllBytes(file);
            String hash = HashUtils.computeHash(data);
            if (!HashUtils.isValidHash(hash) || hash.equals(meta.serverHash())) {
                return List.of();
            }
            long timestampSeconds = resolveLogicalTimestamp(serverDir, meta.relativePath(), hash, file);
            if (timestampSeconds <= meta.serverTimestampSeconds()) {
                return List.of();
            }
            ChunkMapData chunk = new ChunkMapData(meta.regionX(), meta.regionZ(), meta.dimension(),
                    data, timestampSeconds, meta.caveLayer());
            List<ContributionDataPayload> payloads = new ArrayList<>();
            for (ChunkMapData part : ChunkMapData.split(chunk)) {
                payloads.add(new ContributionDataPayload(requestId, part, meta.relativePath(),
                        meta.serverTimestampSeconds(), meta.serverHash()));
            }
            return payloads;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Path resolveClientRegion(Path serverDir, ContributionRegionMeta meta) {
        String[] parts = meta.relativePath().split("/");
        Path dimDir = serverDir.resolve(parts[0]);
        Path mwDir = MapSyncerCommandLogic.findMwDir(dimDir);
        if (mwDir == null) {
            return dimDir.resolve("mw$0").resolve(parts[parts.length - 1] + ".zip");
        }
        if (meta.caveLayer() == Integer.MAX_VALUE) {
            return mwDir.resolve(meta.regionX() + "_" + meta.regionZ() + ".zip");
        }
        return mwDir.resolve("caves").resolve(String.valueOf(meta.caveLayer()))
                .resolve(meta.regionX() + "_" + meta.regionZ() + ".zip");
    }

    private static long resolveLogicalTimestamp(Path serverDir, String relativePath, String currentHash, Path file) throws Exception {
        ClientTimestampCache cache = ClientTimestampCache.getInstance(serverDir);
        var cached = cache.getAll().get(relativePath);
        if (cached != null && cached.hash().equals(currentHash)) {
            return cached.timestampSeconds();
        }
        return Files.getLastModifiedTime(file).toMillis() / 1000;
    }
}
```

`resolveLogicalTimestamp` must prefer `ClientTimestampCache` when the cached hash matches the current file hash. The mtime fallback is only a candidate freshness hint for uncached client-only files; the server must not write this value into its generation cache.

- [ ] **Step 2: Register client handlers**

In each `MapPacketHandler.registerHandlers()`, add:

```java
handler.registerContributionRequestHandler(MapPacketHandler::handleContributionRequest);
handler.registerContributionResultHandler(MapPacketHandler::handleContributionResult);
```

- [ ] **Step 3: Implement request handler**

```java
private static void handleContributionRequest(ContributionRequestPayload payload, PayloadContext context) {
    context.enqueueWork(() -> {
        if (PlatformManager.getPlatform().getClientSyncMode() != ClientSyncMode.BIDIRECTIONAL) {
            return;
        }
        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
        if (serverDir == null) return;
        int sent = 0;
        for (ContributionRegionMeta meta : payload.regions()) {
            var contributions = ClientContributionCollector.collect(payload.requestId(), meta, serverDir);
            for (ContributionDataPayload contribution : contributions) {
                NetworkManager.sendToServer(contribution);
                sent++;
            }
        }
        NetworkManager.sendToServer(new ContributionCompletePayload(payload.requestId(), sent, "done"));
    });
}
```

Do not send an empty `ContributionDataPayload` when there is nothing to upload. Normal no-contribution completion is represented by `ContributionCompletePayload(sentRegions=0, status="done")`.

- [ ] **Step 4: Implement result handler**

```java
private static void handleContributionResult(ContributionResultPayload payload, PayloadContext context) {
    context.enqueueWork(() -> LOGGER.debug("Contribution result request={}, accepted={}, rejected={}, status={}",
            payload.requestId(), payload.accepted(), payload.rejected(), payload.status()));
}
```

- [ ] **Step 5: Compile canonical target**

Run:

```powershell
.\gradlew :mc-1.21.1:fabric:compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```powershell
git add libs/common/src/main/java/com/mapsyncer/client/ClientContributionCollector.java mc-*/shared/src/main/java/com/mapsyncer/client/MapPacketHandler.java
git commit -m "feat: 添加客户端地图贡献上传处理" -m "客户端收到服务端贡献请求后按 region 二次校验本地文件，仅上传仍然较新的有效 Xaero region。"
```

## Task 13: Enforce Client Sync Mode and Background Checks

**Files:**
- Create: `libs/common/src/main/java/com/mapsyncer/client/BackgroundSyncManager.java`
- Modify shared `MapSyncerCommandLogic.java`, `MapPacketHandler.java`.

- [ ] **Step 1: Add command guard**

At the start of `MapSyncerCommandLogic.sendSyncRequest`, add:

```java
if (!PlatformManager.getPlatform().getClientSyncMode().allowsReceive()) {
    if (mc.player != null) {
        mc.player.displayClientMessage(ChatUtils.error("mapsyncer.sync.disabled"), false);
    }
    return;
}
```

- [ ] **Step 2: Add background manager**

```java
package com.mapsyncer.client;

import com.mapsyncer.platform.PlatformManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class BackgroundSyncManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackgroundSyncManager.class);
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "MapSyncer-BackgroundSync");
        t.setDaemon(true);
        return t;
    });
    private static volatile ScheduledFuture<?> task;

    private BackgroundSyncManager() {}

    public static void start(Runnable syncAction) {
        stop();
        int minutes = PlatformManager.getPlatform().getBackgroundSyncIntervalMinutes();
        if (minutes <= 0 || !PlatformManager.getPlatform().getClientSyncMode().allowsReceive()) {
            return;
        }
        task = EXECUTOR.scheduleWithFixedDelay(syncAction, minutes, minutes, TimeUnit.MINUTES);
        LOGGER.info("Background map sync scheduled every {} minutes", minutes);
    }

    public static void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }

    public static void shutdown() {
        stop();
        EXECUTOR.shutdownNow();
    }
}
```

- [ ] **Step 3: Start background checks after server installed**

In `MapPacketHandler` server-installed handler, after existing auto-sync scheduling:

```java
BackgroundSyncManager.start(() -> Minecraft.getInstance().execute(() -> {
    if (Minecraft.getInstance().player != null && !MapPacketHandler.isSyncInProgress()) {
        MapSyncerCommandLogic.executeSyncAll();
    }
}));
```

- [ ] **Step 4: Stop background checks on disconnect**

In `MapPacketHandler.onDisconnect()` add:

```java
BackgroundSyncManager.stop();
```

- [ ] **Step 5: Compile canonical target**

Run:

```powershell
.\gradlew :mc-1.21.1:fabric:compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```powershell
git add libs/common/src/main/java/com/mapsyncer/client/BackgroundSyncManager.java mc-*/shared/src/main/java/com/mapsyncer/client/MapSyncerCommandLogic.java mc-*/shared/src/main/java/com/mapsyncer/client/MapPacketHandler.java
git commit -m "feat: 接入客户端同步模式和后台巡检" -m "客户端 DISABLED 模式会阻止手动和后台同步，后台巡检按配置周期复用现有元数据协商链路。"
```

## Task 14: Add Translations and Documentation

**Files:**
- Modify: `libs/common/src/main/resources/assets/mapsyncer/lang/zh_cn.json`
- Modify: `libs/common/src/main/resources/assets/mapsyncer/lang/en_us.json`
- Modify: `docs/features.md`
- Modify: `docs/test-notes.md`

- [ ] **Step 1: Add translation keys**

Add keys:

```json
"mapsyncer.sync.disabled": "Map sync is disabled in client config.",
"option.mapsyncer.client_sync_mode": "Client Sync Mode",
"option.mapsyncer.client_sync_mode.tooltip": "Controls whether this client disables sync, receives only, or contributes newer local map regions.",
"option.mapsyncer.background_sync_interval": "Background Sync Interval",
"option.mapsyncer.background_sync_interval.tooltip": "Minutes between background metadata checks. Set to 0 to disable.",
"category.mapsyncer.contribution": "Contribution",
"option.mapsyncer.contribution_scope": "Contribution Scope",
"option.mapsyncer.contribution_scope.tooltip": "Controls which players may upload newer client map regions to the server.",
"option.mapsyncer.contribution_cooldown": "Contribution Queue Cooldown",
"option.mapsyncer.contribution_cooldown.tooltip": "Seconds to wait between accepted contribution jobs.",
"option.mapsyncer.contribution_queue_size": "Contribution Queue Size",
"option.mapsyncer.contribution_queue_size.tooltip": "Maximum queued contribution jobs before new uploads are rejected."
```

Use equivalent Chinese text in `zh_cn.json`.

- [ ] **Step 2: Update `docs/features.md`**

Add a “双向同步” subsection documenting:
- client modes;
- server contribution scope;
- UUID whitelist file path and the fact that it is world-specific at `<world>/serverconfig/mapsyncer-contributors.json`, separate from global loader config;
- queue cooldown;
- first distribute, then accept contributions;
- transfer time is not authoritative freshness time.

- [ ] **Step 3: Update `docs/test-notes.md`**

Add manual test cases:
- client `DISABLED` blocks manual sync;
- `RECEIVE_ONLY` downloads but never uploads;
- `BIDIRECTIONAL` uploads whitelisted newer region;
- two clients contributing same region are serialized by queue;
- downloaded server region does not become upload candidate because of file mtime.

- [ ] **Step 4: Commit**

```powershell
git add libs/common/src/main/resources/assets/mapsyncer/lang docs/features.md docs/test-notes.md
git commit -m "docs: 记录双向同步配置与测试项" -m "补充客户端模式、服务端贡献范围、白名单、队列冷却和逻辑时间戳判新的用户文档与手动测试项。"
```

## Task 15: Cross-Version Build Verification

**Files:**
- No source edits unless a compile failure identifies a missed platform copy.

- [ ] **Step 1: Build active root modules**

Run:

```powershell
.\gradlew build -x test --parallel
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Build every supported target through fastbuild**

Run:

```powershell
.\scripts\fastbuild\build-all.bat
```

Expected: every supported version/loader target finishes without compile errors, including targets not currently active in root `settings.gradle`.

If the batch script cannot run in the current shell, run the explicit supported target list instead:

```powershell
.\scripts\fastbuild\build-target.ps1 1.20.1-fabric -NoTest
.\scripts\fastbuild\build-target.ps1 1.20.1-forge -NoTest
.\scripts\fastbuild\build-target.ps1 1.21.1-fabric -NoTest
.\scripts\fastbuild\build-target.ps1 1.21.1-forge -NoTest
.\scripts\fastbuild\build-target.ps1 1.21.1-neoforge -NoTest
.\scripts\fastbuild\build-target.ps1 1.21.11-fabric -NoTest
.\scripts\fastbuild\build-target.ps1 1.21.11-forge -NoTest
.\scripts\fastbuild\build-target.ps1 1.21.11-neoforge -NoTest
.\scripts\fastbuild\build-target.ps1 26.1-fabric -NoTest
.\scripts\fastbuild\build-target.ps1 26.1-neoforge -NoTest
```

- [ ] **Step 3: Run pure Java tests**

Run:

```powershell
.\gradlew :libs:core:test :libs:platform-api:test
```

Expected: all JUnit tests pass.

- [ ] **Step 4: Resolve build failures through the owning task**

If Step 1, Step 2, or Step 3 fails, identify which earlier task owns the failing file and return to that task's implementation and commit step. Use these ownership rules:

- Config compile failure: return to Task 6 and amend the Task 6 commit.
- Payload registration or serialization failure: return to Task 8 and amend the Task 8 commit.
- Shared server contribution failure: return to Task 10 or Task 11 and amend the owning commit.
- Shared client contribution or background sync failure: return to Task 12 or Task 13 and amend the owning commit.

After the owning fix is amended, rerun all commands in this task from Step 1.

## Self-Review Checklist

- Spec coverage: client `DISABLED` / `RECEIVE_ONLY` / `BIDIRECTIONAL` is covered by Tasks 2, 6, 12, 13, and 14.
- Spec coverage: server contribution scope and whitelist JSON are covered by Tasks 4, 6, 10, 11, and 14.
- Spec coverage: first distribute then contribute is covered by Task 11.
- Spec coverage: logical timestamp rules and no transfer-time authority are covered by Tasks 3, 11, 12, and 14.
- Spec coverage: queue and cooldown are covered by Tasks 6, 10, and 11.
- Spec coverage: region-level incremental behavior is covered by Tasks 3, 11, and 12.
- Placeholder scan: this plan intentionally avoids undefined task names and uses exact file paths, commands, and expected results.
- Type consistency: payload names are `ContributionRegionMeta`, `ContributionRequestPayload`, `ContributionDataPayload`, and `ContributionResultPayload` throughout the plan.
