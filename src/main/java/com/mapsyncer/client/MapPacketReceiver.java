package com.mapsyncer.client;

import com.mapsyncer.network.ChunkMapData;
import com.mapsyncer.network.PacketHandler;
import com.mapsyncer.util.ChatUtils;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// Note: This class is manually registered in the main mod class via modBus.addListener()
// because RegisterPayloadHandlersEvent is a MOD bus event. Do not add @EventBusSubscriber here.
public class MapPacketReceiver {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapPacketReceiver.class);

    // Track if sync is in progress to coordinate chunk update disabling
    private static volatile boolean syncInProgress = false;

    // Store the last written mw directory for cache clearing
    private static volatile Path lastMwDir = null;

    // Track sync start time to detect stale syncs (prevent memory leak)
    private static volatile long syncStartTime = 0;
    private static final long STALE_SYNC_TIMEOUT_MS = 10 * 60 * 1000; // 10 minutes

    // Accumulate all chunks received during sync for selective reset
    // IMPORTANT: This is cleared on sync start, completion, and stale detection
    // to prevent memory leaks. Each chunk is ~10-50KB, so we must ensure cleanup.
    private static volatile List<ChunkMapData> allReceivedChunks = new ArrayList<>();

    /**
     * Check if current sync is stale (running too long).
     * Stale syncs may indicate interrupted connection, so we clear data.
     */
    public static boolean isSyncStale() {
        if (!syncInProgress || syncStartTime == 0) {
            return false;
        }
        return System.currentTimeMillis() - syncStartTime > STALE_SYNC_TIMEOUT_MS;
    }

    /**
     * Clear all accumulated sync data to prevent memory leaks.
     * Called when sync is interrupted or becomes stale.
     */
    public static void clearSyncData() {
        syncInProgress = false;
        lastMwDir = null;
        syncStartTime = 0;
        if (allReceivedChunks != null) {
            allReceivedChunks.clear();
        }
        LOGGER.info("Cleared sync data to prevent memory leak");
    }

    /**
     * Get estimated memory usage of accumulated chunks.
     * Used for monitoring potential memory issues.
     */
    public static long getEstimatedMemoryUsage() {
        if (allReceivedChunks == null || allReceivedChunks.isEmpty()) {
            return 0;
        }
        long total = 0;
        for (ChunkMapData chunk : allReceivedChunks) {
            if (chunk.data != null) {
                total += chunk.data.length;
            }
        }
        return total;
    }

    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("1").optional();

        // Client can send sync request to server
        registrar.playToServer(
                PacketHandler.SyncRequestPayload.TYPE,
                PacketHandler.SyncRequestPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    // Clear any stale sync data before starting new sync
                    if (isSyncStale()) {
                        clearSyncData();
                        LOGGER.warn("Cleared stale sync data before starting new sync");
                    }
                    // Disable chunk updates when sync request is sent
                    syncInProgress = true;
                    syncStartTime = System.currentTimeMillis(); // Track start time
                    // Clear accumulated chunks for new sync session
                    allReceivedChunks.clear();
                    XaeroMapIntegrator.disableChunkUpdates();
                }
        );

        // Client receives sync response from server
        registrar.playToClient(
                PacketHandler.SyncResponsePayload.TYPE,
                PacketHandler.SyncResponsePayload.STREAM_CODEC,
                MapPacketReceiver::handleSyncResponse
        );

        // Client receives progress updates from server
        registrar.playToClient(
                PacketHandler.SyncProgressPayload.TYPE,
                PacketHandler.SyncProgressPayload.STREAM_CODEC,
                MapPacketReceiver::handleProgressUpdate
        );
    }

    private static void handleSyncResponse(PacketHandler.SyncResponsePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            // Check for stale sync (running too long) and clear if needed
            if (isSyncStale()) {
                clearSyncData();
                LOGGER.warn("Sync was stale, cleared accumulated data");
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.displayClientMessage(ChatUtils.error("mapsyncer.sync.timeout"), false);
                }
                return;
            }

            List<ChunkMapData> chunks = payload.chunks();
            int serverWorldId = payload.worldId();

            // Disable chunk updates before writing map data
            XaeroMapIntegrator.disableChunkUpdates();
            syncInProgress = true;

            // Accumulate chunks for selective reset tracking
            allReceivedChunks.addAll(chunks);

            // Log memory usage warning if accumulating too much data
            long memoryUsage = getEstimatedMemoryUsage();
            if (memoryUsage > 50_000_000) { // 50MB threshold
                LOGGER.warn("High memory usage during sync: {}MB accumulated", memoryUsage / 1_000_000);
            }

            // Write map data directly from server (no completeness check)
            lastMwDir = XaeroMapIntegrator.writeMapDataAndReturnDir(chunks, serverWorldId);

            if (payload.isComplete()) {
                // Record all updated regions for selective reset
                XaeroMapIntegrator.recordUpdatedRegions(allReceivedChunks);

                SyncProgressTracker.complete();

                // Trigger reload, then re-enable chunk updates after reload completes
                triggerXaeroReloadAndResume();

                // Clear accumulated chunks after sync complete
                allReceivedChunks.clear();
                syncStartTime = 0; // Reset start time
            }
        });
    }

    private static void handleProgressUpdate(PacketHandler.SyncProgressPayload payload, IPayloadContext context) {
        SyncProgressTracker.update(payload.processed(), payload.total(), payload.status());
    }

    /**
     * Trigger Xaero World Map reload for regions that need it.
     * Only reloads regions where cache was not found (new regions from server).
     * Uses direct requestLoad instead of startFullMapReload for better precision.
     */
    private static void triggerXaeroReloadAndResume() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            // Clear cache for synced regions and get regions that need reload
            // (regions without existing cache need to be loaded from disk)
            java.util.Set<XaeroMapIntegrator.RegionCoord> regionsToReload = clearXaeroCacheSelective();

            if (regionsToReload.isEmpty()) {
                LOGGER.info("No regions need reload, all caches were cleared");
                mc.player.displayClientMessage(ChatUtils.success("mapsyncer.cache.all_cleared"), false);
                resumeChunkUpdates();
                return;
            }

            // Get WorldMapSession using reflection
            Class<?> worldMapSessionClass = Class.forName("xaero.map.WorldMapSession");
            Method getCurrentSession = worldMapSessionClass.getMethod("getCurrentSession");
            Object session = getCurrentSession.invoke(null);

            if (session == null) {
                LOGGER.warn("Could not get Xaero WorldMapSession");
                resumeChunkUpdates();
                return;
            }

            // Get MapProcessor from session
            Method getMapProcessor = worldMapSessionClass.getMethod("getMapProcessor");
            Object mapProcessor = getMapProcessor.invoke(session);

            if (mapProcessor == null) {
                LOGGER.warn("Could not get Xaero MapProcessor");
                resumeChunkUpdates();
                return;
            }

            // Get MapSaveLoad from MapProcessor
            Class<?> mapProcessorClass = Class.forName("xaero.map.MapProcessor");
            Method getMapSaveLoad = mapProcessorClass.getMethod("getMapSaveLoad");
            Object mapSaveLoad = getMapSaveLoad.invoke(mapProcessor);

            if (mapSaveLoad == null) {
                LOGGER.warn("Could not get Xaero MapSaveLoad");
                resumeChunkUpdates();
                return;
            }

            // First: call detectRegions to scan newly written files
            Class<?> mapSaveLoadClass = Class.forName("xaero.map.file.MapSaveLoad");
            Method detectRegions = mapSaveLoadClass.getMethod("detectRegions", int.class);
            detectRegions.invoke(mapSaveLoad, 20);
            LOGGER.info("Triggered region detection for {} synced regions", regionsToReload.size());

            // Directly request load for each region that needs reload
            // This is more efficient than startFullMapReload which iterates all regions
            Method getLeafMapRegion = mapProcessorClass.getMethod("getLeafMapRegion", int.class, int.class, int.class, boolean.class);
            Method requestLoad = mapSaveLoadClass.getMethod("requestLoad", Class.forName("xaero.map.region.MapRegion"), String.class);

            int loadedCount = 0;
            int createdCount = 0;

            for (XaeroMapIntegrator.RegionCoord coord : regionsToReload) {
                // Get or create MapRegion for this coordinate
                Object mapRegion = getLeafMapRegion.invoke(mapProcessor, Integer.MAX_VALUE, coord.x(), coord.z(), true);

                if (mapRegion == null) {
                    LOGGER.debug("Could not get/create MapRegion for ({}, {})", coord.x(), coord.z());
                    continue;
                }

                // Check current loadState
                Class<?> mapRegionClass = Class.forName("xaero.map.region.MapRegion");
                java.lang.reflect.Field loadStateField = mapRegionClass.getDeclaredField("loadState");
                loadStateField.setAccessible(true);
                byte currentLoadState = loadStateField.getByte(mapRegion);

                // Reset loadState to 0 if currently loaded (state 2 or 4)
                if (currentLoadState == 2 || currentLoadState == 4) {
                    loadStateField.setByte(mapRegion, (byte) 0);
                    loadedCount++;

                    // Also reset hasHadTerrain to force fresh load
                    try {
                        java.lang.reflect.Field hasHadTerrainField = mapRegionClass.getDeclaredField("hasHadTerrain");
                        hasHadTerrainField.setAccessible(true);
                        hasHadTerrainField.setBoolean(mapRegion, false);
                    } catch (NoSuchFieldException ignored) {}

                    LOGGER.debug("Reset loadState for region ({}, {})", coord.x(), coord.z());
                } else if (currentLoadState == 0 || currentLoadState == 1) {
                    // Region was just created or already pending load
                    createdCount++;
                }

                // Request load for this region
                requestLoad.invoke(mapSaveLoad, mapRegion, "sync reload");
            }

            int totalRequested = loadedCount + createdCount;
            LOGGER.info("Direct reload requested: {} reset, {} new, total {} regions",
                    loadedCount, createdCount, totalRequested);

            mc.player.displayClientMessage(ChatUtils.success("mapsyncer.cache.direct_reload", totalRequested), false);

            // Re-enable chunk updates after reload requests
            resumeChunkUpdates();

        } catch (Exception e) {
            LOGGER.error("Failed to trigger Xaero map reload", e);
            Minecraft.getInstance().player.displayClientMessage(ChatUtils.error("mapsyncer.cache.reload_failed"), false);
            // Always re-enable chunk updates, even on error
            resumeChunkUpdates();
        }
    }

    /**
     * Resume chunk updates after sync completes.
     */
    private static void resumeChunkUpdates() {
        syncInProgress = false;
        XaeroMapIntegrator.enableChunkUpdates();
        LOGGER.info("Sync complete, chunk updates resumed");
    }

    /**
     * Clear Xaero cache files selectively for updated regions.
     * Only clears cache for regions that were synced from server.
     * If cache doesn't exist for a region, mark it for reload.
     * @return Set of regions that need reload (no cache found)
     */
    private static java.util.Set<XaeroMapIntegrator.RegionCoord> clearXaeroCacheSelective() {
        java.util.Set<XaeroMapIntegrator.RegionCoord> regionsToReload = new java.util.HashSet<>();

        try {
            Path mwDir = lastMwDir;
            if (mwDir == null || !mwDir.toFile().exists()) {
                LOGGER.info("No mw directory found, skipping cache clear");
                return regionsToReload;
            }

            // Get regions that were actually synced from server
            java.util.Set<XaeroMapIntegrator.RegionCoord> syncedRegions = XaeroMapIntegrator.getUpdatedRegions();
            if (syncedRegions.isEmpty()) {
                LOGGER.info("No synced regions to clear cache for");
                return regionsToReload;
            }

            LOGGER.info("Checking cache for {} synced regions", syncedRegions.size());

            // Find all cache directories (cache, cache_1, cache_<version>)
            java.util.List<Path> cacheDirs = findCacheDirectories(mwDir);
            if (cacheDirs.isEmpty()) {
                LOGGER.info("No cache directories found, all {} regions need reload", syncedRegions.size());
                regionsToReload.addAll(syncedRegions);
                return regionsToReload;
            }

            int cacheClearedCount = 0;
            int reloadNeededCount = 0;

            // For each synced region, check if cache exists
            for (XaeroMapIntegrator.RegionCoord region : syncedRegions) {
                String cacheFileName = region.x() + "_" + region.z() + ".xwmc";
                boolean cacheFound = false;

                // Check all cache directories for this region's cache
                for (Path cacheDir : cacheDirs) {
                    Path cacheFile = cacheDir.resolve(cacheFileName);
                    if (cacheFile.toFile().exists()) {
                        try {
                            java.nio.file.Files.deleteIfExists(cacheFile);
                            cacheFound = true;
                            cacheClearedCount++;
                            LOGGER.debug("Deleted cache file: {}", cacheFile);
                        } catch (Exception e) {
                            LOGGER.warn("Failed to delete cache file: {}", cacheFile);
                        }
                        break; // Found and deleted, no need to check other directories
                    }
                }

                // If no cache found for this region, it needs reload
                if (!cacheFound) {
                    regionsToReload.add(region);
                    reloadNeededCount++;
                    LOGGER.debug("No cache for region ({}, {}), will trigger reload", region.x(), region.z());
                }
            }

            LOGGER.info("Cache cleared: {} files, reload needed: {} regions", cacheClearedCount, reloadNeededCount);

            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(ChatUtils.desc("mapsyncer.cache.status", cacheClearedCount, reloadNeededCount), false);
            }

        } catch (Exception e) {
            LOGGER.warn("Failed to clear cache: {}", e.getMessage());
        }

        return regionsToReload;
    }

    /**
     * Find all cache directories in mw directory.
     * Cache directories are named: cache, cache_1, cache_<version>
     */
    private static java.util.List<Path> findCacheDirectories(Path mwDir) {
        java.util.List<Path> cacheDirs = new java.util.ArrayList<>();

        try {
            // Standard cache directories
            Path cache = mwDir.resolve("cache");
            Path cache1 = mwDir.resolve("cache_1");

            if (cache.toFile().exists() && cache.toFile().isDirectory()) {
                cacheDirs.add(cache);
            }
            if (cache1.toFile().exists() && cache1.toFile().isDirectory()) {
                cacheDirs.add(cache1);
            }

            // Also check for versioned cache directories (cache_<number>)
            try (java.nio.file.DirectoryStream<Path> stream = java.nio.file.Files.newDirectoryStream(mwDir, "cache_*")) {
                for (Path dir : stream) {
                    if (dir.toFile().isDirectory() && !cacheDirs.contains(dir)) {
                        cacheDirs.add(dir);
                    }
                }
            }

            LOGGER.debug("Found {} cache directories", cacheDirs.size());
        } catch (Exception e) {
            LOGGER.warn("Failed to find cache directories: {}", e.getMessage());
        }

        return cacheDirs;
    }

    /**
     * Delete a cache directory and all .xwmc files inside.
     */
    private static void deleteCacheDirectory(Path cacheDir) {
        if (cacheDir == null || !cacheDir.toFile().exists()) {
            return;
        }

        try {
            // Recursively delete all .xwmc files
            java.nio.file.Files.walk(cacheDir)
                    .filter(p -> p.toString().endsWith(".xwmc") || p.toString().endsWith(".xwmc.temp"))
                    .forEach(p -> {
                        try {
                            java.nio.file.Files.deleteIfExists(p);
                            LOGGER.debug("Deleted cache file: {}", p);
                        } catch (Exception e) {
                            LOGGER.warn("Failed to delete cache file: {}", p);
                        }
                    });

            LOGGER.info("Deleted cache files in: {}", cacheDir);
        } catch (Exception e) {
            LOGGER.warn("Failed to clear cache directory: {}", cacheDir, e);
        }
    }
}
