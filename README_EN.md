# MapSyncer for Xaero's World Map

A multi-platform Minecraft mod that syncs server-side explored areas to clients' Xaero's World Map. When bidirectional mode is enabled, clients can also contribute newer local Xaero regions back to the server, building a shared map baseline for the whole server.

> **Use case**: Players joining an established server, servers using Chunky for map pre-generation, groups that explored different areas separately and need to merge maps, or new servers that want the first player with local map data to bootstrap the initial cache.

---

## Platform Support

> Prioritizing modern versions. NeoForge didn't exist as an independent loader before 1.20.4. Forge provides no developer documentation after 26.1.

| MC Version | Forge | NeoForge | Fabric |
|------------|:-----:|:--------:|:------:|
| 1.20.1 | ✅ | — | ✅ |
| 1.21.1 | ✅ | ✅ | ✅ |
| 1.21.11 | ✅ | ✅ | ✅ |
| 26.1 | — | ✅ | ✅ |

> See [`docs/features.md`](docs/features.md) for detailed feature and platform status.

### Client Dependencies

Supports both dedicated servers and integrated servers (single-player LAN sharing). On integrated servers, the host's Xaero's World Map save directory is reused as the map cache, eliminating redundant conversion.

| Dependency | Requirement |
|------------|-------------|
| Xaero's World Map | 1.40.11+ |

### Server Requirements

- Xaero's World Map is NOT required on the server
- Chunky or similar pre-generation tools are recommended

---

## Features

| Feature | Description |
|---------|-------------|
| **Incremental Sync** | CRC32 hash + timestamp comparison — only transfers changed regions |
| **Streaming Load** | Writes to Xaero directory as data arrives, triggers immediate reload per region |
| **Bandwidth-Aware** | Dynamic send rate adjustment to avoid blocking game network |
| **Resumable Sync** | Auto-resumes from interruption after reconnect (hash-based) |
| **View-Distance Priority** | Regions within player's view distance are prioritized |
| **Dimension Support** | Overworld, Nether, End, and mod dimensions (e.g. Twilight Forest) |
| **Incremental Update** | Server-side periodic/scheduled map cache regeneration |
| **Cave Mode** | Scans downward from a configurable height, outputs to caves subdirectory |
| **Multi-Threaded Hash** | Configurable parallel CRC32 computation on the client |
| **Auto Sync** | Automatically checks for newer server maps on join, no manual command needed |
| **Bidirectional Sync** | Defaults to receive-only; `BIDIRECTIONAL` clients can contribute newer local Xaero regions |
| **Contribution Validation** | Client-side freshness checks plus server-side hash, timestamp, path, and baseline validation prevent stale map overwrites |
| **Contribution Queue** | Concurrent player contributions are serialized by the server, with cooldown and queue limits |
| **Pre-Disconnect Contribution** | On normal multiplayer disconnect, clients can briefly wait to upload local map changes |
| **Empty Cache Bootstrap** | If the server has no map cache yet, a bidirectional client can establish the initial server baseline |

---

## Sync Modes and Bidirectional Contribution

MapSyncer keeps the traditional server-to-client sync behavior by default. Contribution only runs when the client explicitly uses `BIDIRECTIONAL` and the server-side contribution scope allows that player.

| Mode | Behavior |
|------|----------|
| `DISABLED` | Client disables MapSyncer sync and does not request server maps |
| `RECEIVE_ONLY` | Default mode: receives server maps, never uploads local maps |
| `BIDIRECTIONAL` | Receives server updates first, then contributes newer local Xaero regions |

Bidirectional sync follows a "distribute first, contribute after, validate again" flow:

1. The client requests sync and sends local Xaero region metadata.
2. The server first sends authoritative regions that are missing or older on the client.
3. If the client is in `BIDIRECTIONAL`, the server builds a contribution candidate list based on contribution permissions.
4. The client only uploads regions whose hash differs and whose logical timestamp is newer than the server baseline.
5. The server validates uploaded data again, then writes accepted regions into `server_map_cache/` while preserving the contributor's original logical timestamp.

Pre-disconnect contribution only covers the normal "Disconnect" path on multiplayer servers. Crashes, forced process termination, network drops, and single-player exits are not protected by this flow.

---

## Commands

### Client Commands

| Command | Description |
|---------|-------------|
| `/mapsyncer` | Show help |
| `/mapsyncer sync` | Sync current dimension |
| `/mapsyncer sync <dim>` | Sync a specific dimension |
| `/mapsyncer sync all` | Sync all dimensions |
| `/mapsyncer clearstate` | Clear resumable sync state and ignore previous interruptions |

**Dimension arguments**: `overworld`, `the_nether`, `the_end`, or mod dimension IDs like `twilightforest:twilight_forest`

### Server Commands (OP required)

> Forge/NeoForge uses `/mapsyncer`; Fabric uses `/mapsyncerserver` to avoid conflicts with the client-side `/mapsyncer`.

| Command | Description |
|---------|-------------|
| `/mapsyncer generate` | Generate cache for all dimensions |
| `/mapsyncer generate <dim>` | Generate cache for a specific dimension |
| `/mapsyncer generate <dim> <x> <z>` | Generate a single region |
| `/mapsyncer generate <dim> --force` | Force rebuild (clears existing cache) |
| `/mapsyncer status` | View generation progress and cache statistics |
| `/mapsyncer incremental off` | Disable incremental updates |
| `/mapsyncer incremental tick [interval]` | Enable periodic updates (20–72000 ticks) |
| `/mapsyncer incremental scheduled [hour] [min]` | Enable scheduled updates (default 04:00) |

---

## Configuration

### Client Config

| Option | Default | Range | Description |
|--------|--------|-------|-------------|
| `hashThreads` | CPU cores/2 | 1–cores | Number of threads for CRC32 computation |
| `clientSyncMode` | RECEIVE_ONLY | DISABLED / RECEIVE_ONLY / BIDIRECTIONAL | Client sync mode |
| `backgroundSyncIntervalMinutes` | 60 | 0–1440 | Background check interval while online; 0 means only check on join or manual command |
| `syncBeforeDisconnect` | true | — | Try to contribute local map data before normal disconnect; only applies to `BIDIRECTIONAL` clients |
| `disconnectSyncTimeoutSeconds` | 15 | 0–60 | Maximum seconds to wait on pre-disconnect contribution; 0 disables the waiting flow |

### Server Config

Forge config: `world/serverconfig/mapsyncer-server.toml` (per-world)
NeoForge / Fabric config: `config/` directory (`.toml` for NeoForge, `.properties` for Fabric)

**General `[general]`**

| Option | Default | Range | Description |
|--------|--------|-------|-------------|
| `enableDebugLogging` | false | — | Enable debug logging |
| `maxConcurrentRegions` | 4 | 1–16 | Concurrent region conversion threads |
| `maxSyncPacketSize` | 262144 (256KB) | 64KB–1MB | Max packet size in bytes |
| `syncSpeedLimitKBps` | 1024 (1MiB/s) | 0–10240 | Sync rate limit (0 = unlimited) |
| `contributionScope` | WHITELIST | DISABLED / ALL / OPS / WHITELIST / OPS_AND_WHITELIST | Which clients may contribute newer map regions to the server |
| `contributionQueueCooldownSeconds` | 10 | 0–3600 | Cooldown after each contribution session |
| `maxContributionQueueSize` | 32 | 1–1024 | Server-side waiting queue capacity for contribution requests |

The contribution whitelist is stored at `world/serverconfig/mapsyncer-contributors.json` and records allowed player UUIDs per world. The default `WHITELIST` mode does not let every player write to the server map cache; public servers should prefer whitelist or OP-scoped contribution.

**Incremental Update `[incremental_update]`**

| Option | Default | Description |
|--------|---------|-------------|
| `incrementalUpdateMode` | DISABLED | DISABLED / TICK / SCHEDULED |
| `incrementalUpdateIntervalTicks` | 200 | TICK mode interval (20 ticks = 1 second) |
| `scheduledUpdateHour` | 4 | Scheduled update hour (0–23) |
| `scheduledUpdateMinute` | 0 | Scheduled update minute (0–59) |

**Dimension Scan `[dimension_scan]`**

| Option | Default | Description |
|--------|---------|-------------|
| `default_scan_mode` | SURFACE | Default scan mode for unconfigured dimensions |
| `default_cave_start` | 63 | Starting height for CAVE mode |

**Dimension config format**:
```toml
dimension_configs = [
    "minecraft:overworld|SURFACE|63|true|false|-64|384|384",
    "minecraft:the_nether|CAVE|63|false|true|0|256|256",
    "minecraft:the_end|SURFACE|63|false|false|0|256|256"
]
```

Format: `dimensionID|scanMode|caveStart|hasSkylight|hasCeiling|minY|height|logicalHeight`

- **SURFACE**: Scans downward from heightmap. For Overworld and End.
- **CAVE**: Scans downward from a fixed height. For Nether.

---

## Project Structure

```
libs/                   Abstract library layer (platform-agnostic, compiled as independent JARs)
├── core/               Pure Java core: MCA/NBT parsing, utilities
└── platform-api/       Platform abstraction interfaces, network payload definitions

mc-1.20.1/              1.20.1 version
├── shared/             Shared source (referenced by platform modules via sourceSet)
├── fabric/             Platform implementation (produces final mod JAR)
└── forge/

mc-1.21.1/              1.21.1 version
├── shared/
├── fabric/
├── forge/
└── neoforge/

mc-26.1/                26.1 version
├── shared/
├── fabric/
└── neoforge/
```

### Pipeline

```
Server MCA files (region/*.mca)
        │
        ▼
    MCA Parser (pure Java, no Xaero dependency)
   Decompress → NBT parse → Extract chunk data
        │
        ▼
   Region Conversion (RegionConverter)
        │
        ▼
Encode to Xaero format (region.zip)
        │
        ▼
  Timestamp + Hash Cache (GenerationCache)
        │
        ▼
  Incremental Update Processor (optional)
  TICK mode / SCHEDULED mode
        │
        ▼
    Network Sync Protocol
  Hash comparison → View-distance priority sort
  Batched transfer + rate limiting
        │
        ▼
    Streaming Receive
  Write to Xaero directory as data arrives
        │
        ▼
   Xaero Reload Trigger (reflection)
  requestLoad → Map re-renders
        │
        ▼
 Optional Bidirectional Contribution (BIDIRECTIONAL)
  Scan local Xaero metadata → upload newer regions
        │
        ▼
    Server Contribution Validation
  Hash/timestamp/path/baseline checks → write cache
```

### File Storage

```
Server:
  <server>/server_map_cache/
  ├── null/              # Overworld
  ├── DIM-1/             # Nether
  ├── DIM1/              # End
  ├── caves/<layer>/     # Cave mode output
  └── generation_cache.properties  # Timestamp + hash cache

  <world>/serverconfig/
  └── mapsyncer-contributors.json  # Contribution whitelist (UUIDs)

Client:
  <client>/xaero/world-map/Multiplayer_<IP>/     # Modern Xaero unified path (preferred)
  <client>/XaeroWorldMap/Multiplayer_<IP>/       # Legacy Xaero path (compatibility fallback)
  ├── null/mw$<worldId>/   # Overworld
  ├── DIM-1/mw$<worldId>/  # Nether
  ├── DIM1/mw$<worldId>/   # End
  └── sync_timestamps.cache # Logical sync timestamp cache
```

`sync_timestamps.cache` records logical sync timestamps so freshly downloaded files are not mistaken for newer contribution candidates just because their local file modification time changed.

### Dimension Mapping

| Dimension | Minecraft ID | Xaero Directory |
|-----------|--------------|-----------------|
| Overworld | `minecraft:overworld` | `null` |
| Nether | `minecraft:the_nether` | `DIM-1` |
| End | `minecraft:the_end` | `DIM1` |
| Mod dimensions | `namespace:path` | `namespace$path` |

---

## Known Issues

| Issue | Details | Impact |
|-------|---------|--------|
| Cave rendering anomalies | Some cave content is inaccurate | Mostly affects Nether; under investigation |
| Pre-disconnect sync boundary | Only covers normal multiplayer disconnect clicks | Crashes, forced exits, and network drops do not trigger it |
| Bidirectional contribution authorization | Contribution is whitelist-scoped by default | Server admins must configure who may contribute |

---

## Build

```bash
# Build all active platforms (parallel)
./gradlew build -x test --parallel

# Build a single platform
./gradlew :mc-1.21.1:forge:build -x test
./gradlew :mc-1.21.1:fabric:build -x test

# Quick build scripts
scripts/fastbuild/build-all.bat              # All active platforms
scripts/fastbuild/build-forge.bat            # All Forge modules (Gradle 8.9 + JDK 17/21)
scripts/fastbuild/build-fabric-26.1.bat      # Fabric 26.1 (isolated Gradle process)
scripts/fastbuild/build-26.1.bat             # All 26.1 modules
```

Build artifacts are placed in each platform module's `build/libs/` directory.

---

**License**: GPL-3.0

**Acknowledgements**: Xaero's World Map & Minimap
