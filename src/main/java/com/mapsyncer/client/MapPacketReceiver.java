package com.mapsyncer.client;

import com.mapsyncer.network.ChunkMapData;
import com.mapsyncer.network.PacketHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
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

    // Accumulate all chunks received during sync for selective reset
    private static volatile List<ChunkMapData> allReceivedChunks = new ArrayList<>();

    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("1").optional();

        // Client can send sync request to server
        registrar.playToServer(
                PacketHandler.SyncRequestPayload.TYPE,
                PacketHandler.SyncRequestPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    // Disable chunk updates when sync request is sent
                    syncInProgress = true;
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
            List<ChunkMapData> chunks = payload.chunks();
            int serverWorldId = payload.worldId();

            // Disable chunk updates before writing map data
            XaeroMapIntegrator.disableChunkUpdates();
            syncInProgress = true;

            // Accumulate chunks for selective reset tracking
            allReceivedChunks.addAll(chunks);

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
            }
        });
    }

    private static void handleProgressUpdate(PacketHandler.SyncProgressPayload payload, IPayloadContext context) {
        SyncProgressTracker.update(payload.processed(), payload.total(), payload.status());
    }

    /**
     * Trigger Xaero World Map reload for regions near player.
     * Uses selective reset instead of full reload for better performance.
     */
    private static void triggerXaeroReloadAndResume() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            // Clear cache before reload (only for affected regions)
            clearXaeroCacheSelective();

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
            LOGGER.info("Triggered region detection");

            // Selectively reset loadState for regions in view distance
            // This is much more efficient than resetting all regions
            int resetCount = XaeroMapIntegrator.selectiveResetRegionLoadStates();
            LOGGER.info("Selective reset: {} regions will reload from disk", resetCount);

            // Get MapWorld from MapProcessor
            Method getMapWorld = mapProcessorClass.getMethod("getMapWorld");
            Object mapWorld = getMapWorld.invoke(mapProcessor);

            if (mapWorld == null) {
                LOGGER.warn("Could not get Xaero MapWorld");
                resumeChunkUpdates();
                return;
            }

            // Get current dimension from MapWorld
            Class<?> mapWorldClass = Class.forName("xaero.map.world.MapWorld");
            Method getCurrentDimension = mapWorldClass.getMethod("getCurrentDimension");
            Object mapDimension = getCurrentDimension.invoke(mapWorld);

            if (mapDimension == null) {
                LOGGER.warn("Could not get Xaero MapDimension");
                resumeChunkUpdates();
                return;
            }

            // Trigger reload - Xaero will reload regions based on their loadState
            // Only regions with loadState=0 will be reloaded from disk
            Class<?> mapDimensionClass = Class.forName("xaero.map.world.MapDimension");
            Method startFullMapReload = mapDimensionClass.getMethod("startFullMapReload", int.class, boolean.class, mapProcessorClass);
            startFullMapReload.invoke(mapDimension, Integer.MAX_VALUE, false, mapProcessor);

            LOGGER.info("Successfully triggered selective Xaero map reload");

            mc.player.displayClientMessage(
                    Component.literal("§e[MapSyncer] §aSelective reload: " + resetCount + " regions"), false);

            // Re-enable chunk updates after reload is triggered
            resumeChunkUpdates();

        } catch (Exception e) {
            LOGGER.error("Failed to trigger Xaero map reload", e);
            Minecraft.getInstance().player.displayClientMessage(
                    Component.literal("§e[MapSyncer] §cFailed to trigger map reload"), false);
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
     * Only clears cache for regions that were updated and are in view distance.
     */
    private static void clearXaeroCacheSelective() {
        try {
            Path mwDir = lastMwDir;
            if (mwDir == null || !mwDir.toFile().exists()) {
                LOGGER.info("No mw directory found, skipping cache clear");
                return;
            }

            // Get regions that need cache clear: updated + view distance
            java.util.Set<XaeroMapIntegrator.RegionCoord> updatedRegions = XaeroMapIntegrator.getViewDistanceRegions();

            LOGGER.info("Clearing cache for {} regions in view distance", updatedRegions.size());

            // Clear cache directories - we clear entire cache for simplicity
            // (selective cache clearing would require parsing .xwmc file names)
            deleteCacheDirectory(mwDir.resolve("cache"));
            deleteCacheDirectory(mwDir.resolve("cache_1"));

            LOGGER.info("Cache cleared for updated regions");
            Minecraft.getInstance().player.displayClientMessage(
                    Component.literal("§e[MapSyncer] §7Cache cleared..."), false);

        } catch (Exception e) {
            LOGGER.warn("Failed to clear cache: {}", e.getMessage());
        }
    }

    /**
     * Clear Xaero's cache files (.xwmc) before reload.
     * This ensures fresh data is loaded from the new .zip files.
     * Only clears surface layer cache (not caves).
     * @deprecated Use clearXaeroCacheSelective() for better performance
     */
    @Deprecated
    private static void clearXaeroCache() {
        try {
            // Use the mw directory from the last sync instead of recalculating
            Path mwDir = lastMwDir;
            if (mwDir == null || !mwDir.toFile().exists()) {
                LOGGER.info("No mw directory found from sync, skipping cache clear");
                return;
            }

            LOGGER.info("Clearing cache in: {}", mwDir);

            // Delete surface cache directories only (cache/ and cache_1/)
            deleteCacheDirectory(mwDir.resolve("cache"));
            deleteCacheDirectory(mwDir.resolve("cache_1"));

            LOGGER.info("Xaero surface cache cleared successfully");
            Minecraft.getInstance().player.displayClientMessage(
                    Component.literal("§e[MapSyncer] §7Cache cleared..."), false);

        } catch (Exception e) {
            LOGGER.warn("Failed to clear Xaero cache: {}", e.getMessage());
        }
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
