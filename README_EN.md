# MapSyncer for Xaero's World Map

A multi-platform Minecraft mod that converts server-side explored / pre-generated MCA regions into Xaero's World Map format and syncs them to clients (network or offline pack).

> **Use case**: Players joining an established server, or servers using Chunky for pre-generation — sync the map and cut redundant exploration.

Chinese README: [`README.md`](README.md) · Full module list: [`docs/features.md`](docs/features.md)

---

## Dev Log

### v1.0.3 -> v1.0.4 (untested)

This release centers on **multi-layer cave rendering + auto-sync system + multi-version build architecture**:

1. **Multi-layer cave / Nether maps** — LayerPlan layered scanning (SURFACE / ALL / explicit Y), single MCA pass outputs multiple cave layers, aligned with Xaero's underair state machine
2. **Auto-sync enhancements** — client `autoSyncEnabled` toggle, TICK periodic sync (default 5 min), SCHEDULED timestamp comparison
3. **Performance** — parallel incremental scan conversion, streaming reads to cut memory, async client sync, multiple hot-spot eliminations
4. **Multi-version restructure** — G1-G4 anchor + glue layers, added mc-26.2 (protocol 776), restored Forge and 26.x Fabric builds
5. **Ecosystem & tooling** — Fabric / Forge / NeoForge permission adaptation, Fabric Mod Menu integration, enhanced MapPackager

> Full changelog: [`CHANGELOG.md`](CHANGELOG.md)

---

## Platform Support

> NeoForge before 1.20.4 and Forge on 26.x are not supported.

| MC Version | Forge | NeoForge | Fabric |
|------------|:-----:|:--------:|:------:|
| 1.20.1 | ✅ | — | ✅ |
| 1.21.1 | ✅ | ✅ | ✅ |
| 1.21.11 | ✅ | ✅ | ✅ |
| 26.1 | — | ✅ | ✅ |
| 26.2 | — | ✅ | ✅ |

### Client Dependencies

Supports dedicated and integrated servers (LAN). On integrated servers, the host's Xaero save directory is reused as the map cache (no second conversion).

| Dependency | Requirement |
|------------|-------------|
| Xaero's World Map | 1.40.11+ |

### Server Requirements

- Xaero is **not** required on the server
- Chunky (or similar) is recommended for pre-generation

---

## Features

| Feature | Description |
|---------|-------------|
| **Incremental sync** | CRC32 + timestamp; keeps newer client exploration |
| **Streaming load** | Write to Xaero as data arrives; reload per region |
| **Bandwidth control** | Configurable packet size & KB/s limit; auto fragment large payloads |
| **Resumable sync** | Client hash cache; resume after disconnect |
| **View-distance priority** | In-view first; out-of-view drain rate configurable |
| **Dimensions / caves** | Vanilla + mod dims; `dimension = layerPlan` (SURFACE / ALL / Y / combos) |
| **Incremental update** | Server DISABLED / TICK / SCHEDULED cache refresh |
| **Auto sync** | Join / online pull by server mode (toggleable); manual sync always works |
| **Concurrent conversion** | `maxConcurrentRegions`: **0 = auto** (`logical CPUs − 2`, capped at 16) |
| **Config reload** | `/mapsyncer reloadconfig` (Fabric: `/mapsyncerserver`) |
| **Integrated server** | Reuse host Xaero saves on LAN |
| **MapPackager** | Offline zip from `server_map_cache` |
| **Handshake guard** | No custom packets to clients without this mod |

---

## Commands

### Client (`/mapsyncer`)

| Command | Description |
|---------|-------------|
| `/mapsyncer` | Help |
| `/mapsyncer sync` | Sync current dimension |
| `/mapsyncer sync <dim>` | Sync one dimension |
| `/mapsyncer sync all` | Sync all dimensions |
| `/mapsyncer autosync` | Show auto-sync toggle |
| `/mapsyncer autosync on\|off` | Enable/disable auto-sync (saved to config) |

**Dimensions**: `overworld`, `the_nether`, `the_end`, or mod IDs such as `twilightforest:twilight_forest`

### Server (OP level 4)

> Forge/NeoForge: `/mapsyncer` · Fabric: `/mapsyncerserver`

| Command | Description |
|---------|-------------|
| `generate` / `generate <dim>` / `generate <dim> <x> <z>` | All / one dim / one region |
| `generate <dim> --force` | Clear cache and rebuild |
| `status` | Progress + cache stats |
| `incremental off` | Disable incremental updates |
| `incremental` | Show current incremental update mode |
| `incremental tick [interval]` | Periodic (2400–72000 ticks, default 6000 = 5 min) |
| `incremental scheduled [h] [m]` | Daily schedule (default 04:00, server local TZ) |
| `reloadconfig` | Reload server config from disk |
| `help` | Server help |

---

## Configuration

### Client

| Option | Default | Range | Description |
|--------|---------|-------|-------------|
| `hashThreads` | CPU/2 | 1–cores | Parallel CRC32 scan threads |
| `mapRegionLoadIntervalTicks` | 1 | -1–100 | Out-of-view drain into Xaero: -1=all at once, 0=view only, N=one every N ticks |
| `autoSyncEnabled` | true | — | Join auto-sync (TICK/SCHEDULED); TICK also online periodic; manual sync always OK |

Fabric: `config/mapsyncer-client.properties` (optional Cloth for **client** options only) · Forge/NeoForge: `[client]` in `config/mapsyncer-client.toml`

### Server

Server settings are **file-only** (plus `/mapsyncer reloadconfig`; Fabric: `/mapsyncerserver reloadconfig`). No Cloth UI for server config.

Forge: `world/serverconfig/mapsyncer-server.toml` (per world)  
NeoForge: `config/mapsyncer-server.toml` · Fabric: `config/mapsyncer-server.properties` (camelCase / snake_case keys)

**General**

| Option | Default | Range | Description |
|--------|---------|-------|-------------|
| `enableDebugLogging` | false | — | Generation debug logs |
| `maxConcurrentRegions` | **0 (auto)** | 0–16 | Concurrent conversions; 0 = `max(1, min(16, logical CPUs − 2))` |
| `maxSyncPacketSize` | 262144 (256KB) | 64KB–1MB | Max sync packet bytes |
| `syncSpeedLimitKBps` | 1024 (1MiB/s) | 0–10240 | Rate limit (0 = unlimited) |

**Incremental update**

| Option | Default | Description |
|--------|---------|-------------|
| `incrementalUpdateMode` | DISABLED | DISABLED / TICK / SCHEDULED |
| `incrementalUpdateIntervalTicks` | 6000 | TICK interval (min 2400 = 2 min) |
| `scheduledUpdateHour` / `Minute` | 4 / 0 | Daily schedule |

**Dimension scan**

| Option | Default | Description |
|--------|---------|-------------|
| `default_scan_mode` | SURFACE | Fallback for dims not in the list (SURFACE / CAVE) |
| `default_cave_start` | 63 | Cave start Y when fallback is CAVE |
| `dimension_configs` | Three vanilla presets | One string per dimension |

**Preferred entry format** (list style on all loaders):

```toml
dimension_configs = [
    "minecraft:overworld = SURFACE",
    "minecraft:the_nether = SURFACE,63",
    "minecraft:the_end = SURFACE"
]
```

| layerPlan | Description |
|-----------|-------------|
| `SURFACE` | Surface only; ceiling dims scan above logical top (Nether Y≥128) |
| `ALL` | All cave layers in height range |
| `63` / `63,127` | Explicit cave layers only |
| `SURFACE,63` / … | Combinations; layer index = `caveStart >> 4` → `caves/<n>/` |

Compatible: `dimension|layerPlan`, legacy multi-field pipes, Fabric legacy keys. Dimension type info comes from the server API at runtime (not stored in config).

---

## Incremental Update & Client Auto-Sync

Server mode controls **when MCA is rescanned** into the cache. With `autoSyncEnabled=true`, the client may **auto-sync** using the same hash/timestamp rules as manual sync.

| Mode | Server | Client (autosync on) |
|------|--------|----------------------|
| **DISABLED** | No incremental scan | No auto sync; resume prompt if needed |
| **TICK** | Scan every N ticks | Join: timestamp + cooldown; **online** periodic sync (Action Bar) |
| **SCHEDULED** | Once daily in a 1-minute window | Join: timestamp only (no cooldown); no online timer |

Shared: unfinished sync (`needsResume`) is preferred on join; skip auto join sync if server has no generation timestamp or client is already up to date.

---

## MapPackager

```bash
./gradlew buildPackager
java -jar mapsyncer-packager.jar -c <cache> -o <zip> [-s name] [-w worldId] [-d worldDir]
```

Packs all dimensions (including cave layers) into `Multiplayer_<name>/<dim>/mw$<worldId>/` and converts `generation_cache.properties` → `sync_timestamps.cache`. Pure Java; no Minecraft/Xaero runtime required.

---

## Project Structure

```
libs/common/          Shared business logic
libs/core/            Pure Java MCA/NBT + MapPackager
libs/platform-api/    Platform API + payloads
libs/mc-1.20/ … mc-26/   G1–G4 MC API anchors

mc-{version}/{fabric|forge|neoforge}/   Loader glue
```

### Pipeline

```
MCA (region/*.mca) → parse → convert → region.zip (Xaero 6.8)
  → GenerationCache → optional incremental scan
  → network (hash / view priority / batch / rate limit)
  → stream into mw$worldId/ → Xaero requestLoad
```

### Storage & dimension mapping

Same layout as the Chinese README (`server_map_cache/`, `xaero/world-map/` with `XaeroWorldMap` fallback; `null` / `DIM-1` / `DIM1` / `namespace$path`).

---

## Build

```bash
./gradlew build -x test --parallel
./gradlew :mc-1.21.1:forge:build -x test
./gradlew buildPackager
scripts/fastbuild/build-all.bat
scripts/fastbuild/build-target.ps1 all -NoTest
```

Mod JARs: each module `build/libs/` · collected under root `output/` for packager / `buildAll`.

---

## Known Issues

| Issue | Notes |
|-------|--------|
| Mod-dimension cave layers | Some mods may need manual layerPlan tuning for ALL / explicit Y |

> v1.0.4 fixed Nether `SURFACE,63` surface/cave generation and client display — see `CHANGELOG.md`.

---

**License**: GPL-3.0

**Acknowledgements**: Xaero's World Map & Minimap
