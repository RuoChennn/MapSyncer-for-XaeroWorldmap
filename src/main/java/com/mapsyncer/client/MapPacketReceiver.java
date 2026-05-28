package com.mapsyncer.client;

import com.mapsyncer.network.ChunkMapData;
import com.mapsyncer.network.PacketHandler;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.DimensionPathMapping;
import com.mapsyncer.util.HashUtils;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 地图数据包接收器 - Fabric 版本
 *
 * 处理从服务端接收的地图同步数据包，并负责写入到 Xaero 地图目录。
 */
public class MapPacketReceiver {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapPacketReceiver.class);

    private static volatile boolean syncInProgress = false;
    private static volatile boolean serverInstalled = false;
    private static volatile String serverVersion = "";
    private static volatile Path lastMwDir = null;
    private static volatile long syncStartTime = 0;
    private static final long STALE_SYNC_TIMEOUT_MS = 10 * 60 * 1000;
    private static volatile Set<XaeroMapIntegrator.RegionCoord> updatedRegionCoords = new HashSet<>();
    private static volatile Set<XaeroMapIntegrator.RegionCoord> loadedRegions = new HashSet<>();

    private static volatile Object cachedMapProcessor = null;
    private static volatile Object cachedMapSaveLoad = null;
    private static volatile Method cachedGetLeafMapRegion = null;
    private static volatile Method cachedRequestLoad = null;
    private static volatile java.lang.reflect.Field cachedLoadStateField = null;
    private static volatile Method cachedCancelRefresh = null;
    private static volatile boolean reflectionInitialized = false;

    public static boolean isSyncInProgress() {
        return syncInProgress;
    }

    public static boolean isSyncStale() {
        if (!syncInProgress || syncStartTime == 0) {
            return false;
        }
        return System.currentTimeMillis() - syncStartTime > STALE_SYNC_TIMEOUT_MS;
    }

    public static void clearSyncData() {
        syncInProgress = false;
        lastMwDir = null;
        syncStartTime = 0;
        clearReceivedChunks();
        LOGGER.info("Cleared sync data to prevent memory leak");
    }

    public static void clearReceivedChunks() {
        if (updatedRegionCoords != null) {
            updatedRegionCoords.clear();
        }
    }

    /**
     * 发送同步请求到服务端
     */
    public static void sendSyncRequest(PacketHandler.SyncRequestPayload payload) {
        if (isSyncStale()) {
            clearSyncData();
            LOGGER.warn("Cleared stale sync data before starting new sync");
        }
        syncStartTime = System.currentTimeMillis();
        updatedRegionCoords.clear();
        ClientPlayNetworking.send(payload);
    }

    /**
     * 处理服务端已安装通知
     */
    public static void handleServerInstalled(PacketHandler.ServerInstalledPayload payload) {
        serverInstalled = true;
        serverVersion = payload.version();
        LOGGER.info("Server has MapSyncer installed, version: {}", serverVersion);
    }

    /**
     * 处理进度更新
     */
    public static void handleProgressUpdate(PacketHandler.SyncProgressPayload payload) {
        SyncProgressTracker.update(payload.processed(), payload.total(), payload.status());
    }

    /**
     * 处理同步响应
     */
    public static void handleSyncResponse(PacketHandler.SyncResponsePayload payload) {
        String status = payload.status();
        List<ChunkMapData> chunks = payload.chunks();
        int serverWorldId = payload.worldId();

        LOGGER.debug("Received sync response: status={}, chunks={}, isComplete={}", status, chunks.size(), payload.isComplete());

        Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
        ClientTimestampCache tsCache = serverDir != null && serverDir.toFile().exists()
                ? ClientTimestampCache.getInstance(serverDir) : null;

        if ("no_cache".equals(status) || "dim_not_available".equals(status)) {
            LOGGER.info("Server returned error status: {}, no sync needed", status);
            clearSyncData();
            clearReflectionCache();
            if (tsCache != null) {
                tsCache.clearSyncState();
            }
            return;
        }

        if ("uptodate".equals(status)) {
            LOGGER.info("Map is up-to-date, no sync needed");
            clearSyncData();
            clearReflectionCache();
            if (tsCache != null) {
                tsCache.markSyncComplete();
            }
            return;
        }

        if (isSyncStale()) {
            clearSyncData();
            clearReflectionCache();
            LOGGER.warn("Sync was stale, cleared accumulated data");
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(ChatUtils.error("mapsyncer.sync.timeout"), false);
            }
            return;
        }

        if (!syncInProgress) {
            syncInProgress = true;
            LOGGER.info("Starting sync (streaming mode)");
            initializeReflectionCache();
        }

        Minecraft mc = Minecraft.getInstance();
        boolean isCaveDimension = mc.level != null && mc.level.dimension() == Level.NETHER;

        for (ChunkMapData chunk : chunks) {
            XaeroMapIntegrator.RegionCoord coord = new XaeroMapIntegrator.RegionCoord(
                chunk.regionX, chunk.regionZ, chunk.caveLayer);
            updatedRegionCoords.add(coord);

            Path mwDir = XaeroMapIntegrator.writeChunkDataAndGetMwDir(chunk, serverWorldId);
            if (mwDir != null) {
                lastMwDir = mwDir;
            }

            boolean shouldProcess = isCaveDimension
                ? (chunk.caveLayer != Integer.MAX_VALUE)
                : (chunk.caveLayer == Integer.MAX_VALUE);

            Set<XaeroMapIntegrator.RegionCoord> viewRegionsForLayer =
                XaeroMapIntegrator.getViewDistanceRegions(chunk.caveLayer);
            boolean inViewDistance = viewRegionsForLayer.contains(coord);

            if (shouldProcess) {
                clearSingleRegionCache(coord);
                triggerSingleRegionLoad(coord, chunk.caveLayer, inViewDistance);
            }

            if (tsCache != null) {
                String relativePath = buildRelativePathForCache(chunk);
                String hash = HashUtils.computeHash(chunk.data);
                tsCache.update(relativePath, chunk.timestampSeconds, hash);
            }
        }

        if (tsCache != null && !chunks.isEmpty()) {
            tsCache.save();
        }

        if (payload.isComplete()) {
            int totalReceived = updatedRegionCoords.size();
            LOGGER.info("同步完成: 总计 {} 个区域已处理", totalReceived);

            if (!updatedRegionCoords.isEmpty()) {
                XaeroMapIntegrator.recordUpdatedRegionCoords(updatedRegionCoords);
                SyncProgressTracker.completeWithCount(totalReceived);
                syncInProgress = false;
                if (tsCache != null) {
                    tsCache.markSyncComplete();
                }
            } else {
                syncInProgress = false;
                LOGGER.info("Sync complete with no data received");
                if (tsCache != null) {
                    tsCache.markSyncComplete();
                }
            }

            clearSyncState();
            clearReflectionCache();
        }
    }

    public static boolean isServerInstalled() {
        return serverInstalled;
    }

    public static void resetServerStatus() {
        serverInstalled = false;
        serverVersion = "";
    }

    private static void clearSyncState() {
        updatedRegionCoords.clear();
        loadedRegions.clear();
        lastMwDir = null;
        syncStartTime = 0;
    }

    private static void clearReflectionCache() {
        reflectionInitialized = false;
        cachedMapProcessor = null;
        cachedMapSaveLoad = null;
        cachedGetLeafMapRegion = null;
        cachedRequestLoad = null;
        cachedLoadStateField = null;
        cachedCancelRefresh = null;
    }

    private static void initializeReflectionCache() {
        if (reflectionInitialized) return;

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            Class<?> worldMapSessionClass = Class.forName("xaero.map.WorldMapSession");
            Object session = worldMapSessionClass.getMethod("getCurrentSession").invoke(null);
            if (session == null) {
                LOGGER.warn("无法初始化反射缓存: WorldMapSession 为空");
                return;
            }

            Class<?> mapProcessorClass = Class.forName("xaero.map.MapProcessor");
            cachedMapProcessor = worldMapSessionClass.getMethod("getMapProcessor").invoke(session);
            if (cachedMapProcessor == null) {
                LOGGER.warn("无法初始化反射缓存: MapProcessor 为空");
                return;
            }

            Class<?> mapSaveLoadClass = Class.forName("xaero.map.file.MapSaveLoad");
            cachedMapSaveLoad = mapProcessorClass.getMethod("getMapSaveLoad").invoke(cachedMapProcessor);
            if (cachedMapSaveLoad == null) {
                LOGGER.warn("无法初始化反射缓存: MapSaveLoad 为空");
                return;
            }

            cachedGetLeafMapRegion = mapProcessorClass.getMethod("getLeafMapRegion", int.class, int.class, int.class, boolean.class);
            cachedRequestLoad = mapSaveLoadClass.getMethod("requestLoad", Class.forName("xaero.map.region.MapRegion"), String.class, boolean.class);

            Class<?> mapRegionClass = Class.forName("xaero.map.region.MapRegion");
            cachedLoadStateField = mapRegionClass.getDeclaredField("loadState");
            cachedLoadStateField.setAccessible(true);
            cachedCancelRefresh = mapRegionClass.getMethod("cancelRefresh", mapProcessorClass);

            reflectionInitialized = true;

            Method setRegionDetectionComplete = mapSaveLoadClass.getMethod("setRegionDetectionComplete", boolean.class);
            setRegionDetectionComplete.invoke(cachedMapSaveLoad, true);

            LOGGER.info("反射 API 缓存已初始化，regionDetectionComplete=true");

        } catch (Exception e) {
            LOGGER.error("初始化反射缓存失败", e);
        }
    }

    private static void triggerSingleRegionLoad(XaeroMapIntegrator.RegionCoord coord, int caveLayer, boolean inViewDistance) {
        if (!reflectionInitialized || cachedMapProcessor == null) {
            LOGGER.warn("反射缓存未初始化，无法加载区域 ({}, {}) layer={}", coord.x(), coord.z(), caveLayer);
            return;
        }

        if (loadedRegions.contains(coord)) {
            return;
        }

        try {
            Object mapRegion = cachedGetLeafMapRegion.invoke(cachedMapProcessor,
                caveLayer, coord.x(), coord.z(), true);
            if (mapRegion == null) {
                LOGGER.warn("无法创建 MapRegion ({}, {}) layer={}", coord.x(), coord.z(), caveLayer);
                return;
            }

            cachedCancelRefresh.invoke(mapRegion, cachedMapProcessor);

            Class<?> mapRegionClass = Class.forName("xaero.map.region.MapRegion");
            Class<?> leveledRegionClass = Class.forName("xaero.map.region.LeveledRegion");

            java.lang.reflect.Field shouldCacheField = leveledRegionClass.getDeclaredField("shouldCache");
            shouldCacheField.setAccessible(true);
            shouldCacheField.setBoolean(mapRegion, true);

            java.lang.reflect.Method setHasHadTerrainMethod = mapRegionClass.getMethod("setHasHadTerrain");
            setHasHadTerrainMethod.invoke(mapRegion);

            cachedLoadStateField.setByte(mapRegion, (byte) 4);
            cachedRequestLoad.invoke(cachedMapSaveLoad, mapRegion, "sync", true);

            loadedRegions.add(coord);

        } catch (Exception e) {
            LOGGER.warn("立即加载区域 ({}, {}) layer={} 失败: {}", coord.x(), coord.z(), caveLayer, e.getMessage());
        }
    }

    private static void clearSingleRegionCache(XaeroMapIntegrator.RegionCoord coord) {
        if (lastMwDir == null) return;

        String cacheFileName = coord.x() + "_" + coord.z() + ".xwmc";
        List<Path> cacheDirs = findCacheDirectories(lastMwDir);

        for (Path cacheDir : cacheDirs) {
            Path cacheFile = cacheDir.resolve(cacheFileName);
            if (cacheFile.toFile().exists()) {
                try {
                    java.nio.file.Files.deleteIfExists(cacheFile);
                } catch (Exception e) {
                    LOGGER.warn("清除缓存失败: {}", cacheFile);
                }
                return;
            }
        }
    }

    private static String buildRelativePathForCache(ChunkMapData chunk) {
        String xaeroDim = chunk.dimension;
        if (chunk.caveLayer == Integer.MAX_VALUE) {
            return xaeroDim + "/" + chunk.regionX + "_" + chunk.regionZ;
        } else {
            return xaeroDim + "/caves/" + chunk.caveLayer + "/" + chunk.regionX + "_" + chunk.regionZ;
        }
    }

    private static java.util.List<Path> findCacheDirectories(Path mwDir) {
        java.util.List<Path> cacheDirs = new java.util.ArrayList<>();

        try {
            Path cache = mwDir.resolve("cache");
            Path cache1 = mwDir.resolve("cache_1");

            if (cache.toFile().exists() && cache.toFile().isDirectory()) {
                cacheDirs.add(cache);
            }
            if (cache1.toFile().exists() && cache1.toFile().isDirectory()) {
                cacheDirs.add(cache1);
            }

            try (java.nio.file.DirectoryStream<Path> stream = java.nio.file.Files.newDirectoryStream(mwDir, "cache_*")) {
                for (Path dir : stream) {
                    if (dir.toFile().isDirectory() && !cacheDirs.contains(dir)) {
                        cacheDirs.add(dir);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to find cache directories: {}", e.getMessage());
        }

        return cacheDirs;
    }

    public static void prepareSyncForDimension(String targetDimension) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        String currentXaeroDim = DimensionPathMapping.getInstance().toXaeroDimension(
                mc.level.dimension().identifier().toString());

        if (targetDimension.equals(currentXaeroDim)) {
            LOGGER.info("Syncing current dimension {}, unloading view distance regions", targetDimension);
            int unloaded = XaeroMapIntegrator.unloadViewDistanceRegions();
            if (unloaded > 0 && mc.player != null) {
                mc.player.displayClientMessage(
                        ChatUtils.desc("mapsyncer.sync.unloading_regions", unloaded), false);
            }
        }
    }
}
