# MapSyncer for Xaero's World Map

A multi-platform Minecraft mod that syncs server-side explored areas to clients' Xaero's World Map.

> **Use case**: Players joining an established server, or servers using Chunky for map pre-generation — sync the map to players and eliminate redundant exploration time.

---

## Platform Support

> Prioritizing modern versions. NeoForge didn't exist as an independent loader before 1.20.4. Forge provides no developer documentation after 26.1.

| MC Version | Forge | NeoForge | Fabric |
|------------|:-----:|:--------:|:------:|
| 1.20.1 | ✅ | — | ✅ |
| 1.21.1 | ✅ | ✅ | ✅ |
| 1.21.11 | ✅ | ✅ | ✅ |
| 26.1 | — | ✅ | ✅ |

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

---

## Commands

### Client Commands

| Command | Description |
|---------|-------------|
| `/mapsyncer` | Show help |
| `/mapsyncer sync` | Sync current dimension |
| `/mapsyncer sync <dim>` | Sync a specific dimension |
| `/mapsyncer sync all` | Sync all dimensions |
| `/mapsyncer autosync` | Show client auto-sync toggle status |
| `/mapsyncer autosync on\|off` | Enable/disable client auto-sync (saved to config) |

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
| `/mapsyncer incremental tick [interval]` | Enable periodic updates (2400–72000 ticks, default 6000 = 5 min) |
| `/mapsyncer incremental scheduled [hour] [min]` | Enable scheduled updates (default 04:00) |

---

## Configuration

### Client Config

| Option | Default | Range | Description |
|--------|--------|-------|-------------|
| `hashThreads` | CPU cores/2 | 1–cores | Number of threads for CRC32 computation |
| `autoSyncEnabled` | true | - | Client auto-sync (join + TICK periodic); manual `/mapsyncer sync` always works |

Fabric: `config/mapsyncer-client.properties`; Forge/NeoForge: `[client]` section in `config/mapsyncer-client.toml`.

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

**Incremental Update `[incremental_update]`**

| Option | Default | Description |
|--------|---------|-------------|
| `incrementalUpdateMode` | DISABLED | DISABLED / TICK / SCHEDULED |
| `incrementalUpdateIntervalTicks` | 6000 | TICK mode interval (20 ticks = 1 s; default 5 min, min 2 min) |
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

## Incremental Update Modes & Client Auto-Sync

The server `incrementalUpdateMode` controls when the **map cache** is rescanned from MCA files. On join, the client receives `ServerInstalledPayload` and may **auto-sync** when **`autoSyncEnabled` is true**, using the same hash/timestamp rules as manual `/mapsyncer sync`. Use `/mapsyncer autosync off` or the config file to disable client auto-sync.

### DISABLED

- **Server**: No incremental scan handler.
- **Client**: No auto-sync on join; manual sync only. Shows resume prompt if a previous sync was interrupted.

### TICK

- **Server**: Scans changed regions every `incrementalUpdateIntervalTicks` (default **6000 = 5 min**, min **2400 = 2 min**).
- **Client on join**: Sync if local timestamps are older than server **and** join cooldown (minutes = tick interval) has elapsed. Chat messages for start/complete.
- **Client while online**: Fixed-rate timer matching server tick interval; progress on **Action Bar** only (periodic sync strings).
- **Manual sync**: No cooldown.

### SCHEDULED

- **Server**: Once daily at `scheduledUpdateHour:Minute` (default **04:00**, server local timezone), within a 1-minute window.
- **Client on join**: Sync **only if** `clientMaxTimestamp < serverLastGenerationTimestamp`; **no cooldown**.
- **Client while online**: No periodic timer; rejoin or manual sync after server updates.
- **Manual sync**: No cooldown.

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

Client:
  <client>/xaero/world-map/Multiplayer_<IP>/     # Modern Xaero unified path (preferred)
  <client>/XaeroWorldMap/Multiplayer_<IP>/       # Legacy Xaero path (compatibility fallback)
  ├── null/mw$<worldId>/   # Overworld
  ├── DIM-1/mw$<worldId>/  # Nether
  └── DIM1/mw$<worldId>/   # End
```

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
