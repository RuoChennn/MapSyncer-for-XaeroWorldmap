package com.mapsyncer.client;

import com.mapsyncer.network.ChunkMapData;
import com.mapsyncer.network.PacketHandler;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.DimensionPathMapping;
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

/**
 * 地图数据包接收器。
 * 处理从服务端接收的地图同步数据包，并负责写入到 Xaero 地图目录。
 *
 * <p>主要功能：</p>
 * <ul>
 *   <li>注册数据包处理器，处理同步请求、响应和进度更新</li>
 *   <li>管理同步过程中的区块更新暂停和恢复</li>
 *   <li>清除 Xaero 缓存文件并触发地图重新加载</li>
 *   <li>检测超时和陈旧的同步请求，防止内存泄漏</li>
 *   <li>同步当前维度时，预先卸载视野范围内的region以便重新加载服务端数据</li>
 * </ul>
 *
 * <p>注意：此类在主模类中通过 modBus.addListener() 手动注册，
 * 因为 RegisterPayloadHandlersEvent 是 MOD bus 事件。不要在此添加 @EventBusSubscriber。</p>
 */
// Note: This class is manually registered in the main mod class via modBus.addListener()
// because RegisterPayloadHandlersEvent is a MOD bus event. Do not add @EventBusSubscriber here.
public class MapPacketReceiver {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapPacketReceiver.class);

    /** 同步是否正在进行中，用于协调区块更新的禁用 */
    private static volatile boolean syncInProgress = false;

    /** 最后写入的 mw 目录，用于缓存清除 */
    private static volatile Path lastMwDir = null;

    /** 同步开始时间，用于检测陈旧的同步（防止内存泄漏） */
    private static volatile long syncStartTime = 0;

    /** 陈旧同步超时时间（10分钟） */
    private static final long STALE_SYNC_TIMEOUT_MS = 10 * 60 * 1000;

    /** 同步期间接收的所有区块数据，用于选择性重置。
     * 重要：在同步开始、完成和陈旧检测时清空，防止内存泄漏。
     * 每个区块约 10-50KB，必须确保清理。
     */
    private static volatile List<ChunkMapData> allReceivedChunks = new ArrayList<>();

    /**
     * 检查当前同步是否陈旧（运行时间过长）。
     * 陈旧的同步可能表示连接中断，需要清除数据。
     *
     * @return 如果同步陈旧返回 true；否则返回 false
     */
    public static boolean isSyncStale() {
        if (!syncInProgress || syncStartTime == 0) {
            return false;
        }
        return System.currentTimeMillis() - syncStartTime > STALE_SYNC_TIMEOUT_MS;
    }

    /**
     * 清除所有累积的同步数据，防止内存泄漏。
     * 在同步中断或变得陈旧时调用。
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
     * 获取累积区块的估计内存使用量。
     * 用于监控潜在的内存问题。
     *
     * @return 估计的内存使用量（字节）
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

    /**
     * 注册数据包处理器。
     * 处理同步请求（客户端发送）、同步响应（服务端返回）和进度更新。
     *
     * @param event 注册数据包处理器事件
     */
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

    /**
     * 处理服务端返回的同步响应数据包。
     * 将接收到的区块数据写入地图目录，并在同步完成时触发重新加载。
     *
     * <p>流程：同步前已卸载视野范围内region并禁用chunk更新，
     * 同步中写入所有服务端数据，同步后触发重载并恢复更新。</p>
     *
     * @param payload 同步响应数据包
     * @param context 数据包上下文
     */
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

            // Chunk updates already disabled before sync started
            syncInProgress = true;

            // Accumulate all chunks for tracking (no filtering - write all server data)
            allReceivedChunks.addAll(chunks);

            // Log memory usage warning if accumulating too much data
            long memoryUsage = getEstimatedMemoryUsage();
            if (memoryUsage > 50_000_000) { // 50MB threshold
                LOGGER.warn("High memory usage during sync: {}MB accumulated", memoryUsage / 1_000_000);
            }

            // Write all map data from server (覆盖客户端数据)
            if (!chunks.isEmpty()) {
                lastMwDir = XaeroMapIntegrator.writeMapDataAndReturnDir(chunks, serverWorldId);
            }

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

    /**
     * 处理服务端发送的进度更新数据包。
     * 更新同步进度追踪器的状态。
     *
     * @param payload 进度更新数据包
     * @param context 数据包上下文
     */
    private static void handleProgressUpdate(PacketHandler.SyncProgressPayload payload, IPayloadContext context) {
        SyncProgressTracker.update(payload.processed(), payload.total(), payload.status());
    }

    /**
     * 触发 Xaero World Map 重新加载所有同步的区域。
     * 清除缓存并对每个同步的区域调用 requestLoad，确保服务端数据正确显示。
     * 使用 requestLoad 而非 startFullMapReload 以获得更好的精确度。
     */
    private static void triggerXaeroReloadAndResume() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            // Clear cache for synced regions and get all regions to reload
            // All synced regions (including view distance regions) need reload
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
            // Use requestLoad with prioritize parameter for view distance regions
            Method requestLoad = mapSaveLoadClass.getMethod("requestLoad", Class.forName("xaero.map.region.MapRegion"), String.class, boolean.class);

            // 获取视距范围内的 region（优先加载）
            java.util.Set<XaeroMapIntegrator.RegionCoord> viewRegions = XaeroMapIntegrator.getViewDistanceRegions();

            // 获取预卸载的 region（原本已加载的）
            java.util.Set<XaeroMapIntegrator.RegionCoord> preUnloadedRegions = XaeroMapIntegrator.getPreUnloadedRegions();

            // 用于追踪视距内已暂停的 region，以便稍后恢复
            java.util.List<Object> pausedViewRegions = new java.util.ArrayList<>();

            int priorityCount = 0;    // 视距范围内优先加载的 region 数量
            int normalCount = 0;      // 其他 region 数量
            int reloadedCount = 0;    // 原本已加载的（使用 loadState=4）
            int newCount = 0;         // 新增的（使用 loadState=0）

            // Step 1: 优先处理视距范围内的 region（prioritize=true）
            // 并对这些 region 使用 region 级别的 pauseWriting 保护，防止实时生成覆盖
            for (XaeroMapIntegrator.RegionCoord coord : regionsToReload) {
                if (!viewRegions.contains(coord)) continue;

                Object mapRegion = getLeafMapRegion.invoke(mapProcessor, Integer.MAX_VALUE, coord.x(), coord.z(), true);
                if (mapRegion == null) {
                    LOGGER.debug("Could not get/create MapRegion for ({}, {})", coord.x(), coord.z());
                    continue;
                }

                // Set loadState
                Class<?> mapRegionClass = Class.forName("xaero.map.region.MapRegion");
                java.lang.reflect.Field loadStateField = mapRegionClass.getDeclaredField("loadState");
                loadStateField.setAccessible(true);
                byte currentLoadState = loadStateField.getByte(mapRegion);

                boolean wasPreLoaded = preUnloadedRegions.contains(coord);
                if (wasPreLoaded) {
                    loadStateField.setByte(mapRegion, (byte) 4);
                    reloadedCount++;
                } else {
                    if (currentLoadState == 2 || currentLoadState == 4) {
                        loadStateField.setByte(mapRegion, (byte) 0);
                        try {
                            java.lang.reflect.Field hasHadTerrainField = mapRegionClass.getDeclaredField("hasHadTerrain");
                            hasHadTerrainField.setAccessible(true);
                            hasHadTerrainField.setBoolean(mapRegion, false);
                        } catch (NoSuchFieldException ignored) {}
                    }
                    newCount++;
                }

                // 对视距内的 region 使用 region 级别的 pushWriterPause 保护
                // 这样即使全局恢复后，这些 region 也不会被实时生成覆盖
                try {
                    Method pushWriterPauseRegion = mapRegionClass.getMethod("pushWriterPause");
                    pushWriterPauseRegion.invoke(mapRegion);
                    pausedViewRegions.add(mapRegion);
                    LOGGER.debug("Paused region-level writing for view region ({}, {})", coord.x(), coord.z());
                } catch (Exception e) {
                    LOGGER.warn("Could not pause region-level writing: {}", e.getMessage());
                }

                // Request load with prioritize=true (adds to front of queue)
                requestLoad.invoke(mapSaveLoad, mapRegion, "sync priority", true);
                priorityCount++;
                LOGGER.debug("Priority load requested for view region ({}, {})", coord.x(), coord.z());
            }

            // Step 2: 处理其他 region（prioritize=false）
            for (XaeroMapIntegrator.RegionCoord coord : regionsToReload) {
                if (viewRegions.contains(coord)) continue; // 已在 Step 1 处理

                Object mapRegion = getLeafMapRegion.invoke(mapProcessor, Integer.MAX_VALUE, coord.x(), coord.z(), true);
                if (mapRegion == null) {
                    LOGGER.debug("Could not get/create MapRegion for ({}, {})", coord.x(), coord.z());
                    continue;
                }

                // Set loadState
                Class<?> mapRegionClass = Class.forName("xaero.map.region.MapRegion");
                java.lang.reflect.Field loadStateField = mapRegionClass.getDeclaredField("loadState");
                loadStateField.setAccessible(true);
                byte currentLoadState = loadStateField.getByte(mapRegion);

                boolean wasPreLoaded = preUnloadedRegions.contains(coord);
                if (wasPreLoaded) {
                    loadStateField.setByte(mapRegion, (byte) 4);
                    reloadedCount++;
                } else {
                    if (currentLoadState == 2 || currentLoadState == 4) {
                        loadStateField.setByte(mapRegion, (byte) 0);
                        try {
                            java.lang.reflect.Field hasHadTerrainField = mapRegionClass.getDeclaredField("hasHadTerrain");
                            hasHadTerrainField.setAccessible(true);
                            hasHadTerrainField.setBoolean(mapRegion, false);
                        } catch (NoSuchFieldException ignored) {}
                    }
                    newCount++;
                }

                // Request load with prioritize=false (normal queue order)
                requestLoad.invoke(mapSaveLoad, mapRegion, "sync reload", false);
                normalCount++;
            }

            int totalRequested = priorityCount + normalCount;
            LOGGER.info("Reload requested: {} pre-loaded (loadState=4), {} new (loadState=0), total {} regions",
                    reloadedCount, newCount, totalRequested);

            // 清除预卸载记录
            XaeroMapIntegrator.clearPreUnloadedRegions();

            mc.player.displayClientMessage(ChatUtils.success("mapsyncer.cache.direct_reload", totalRequested), false);

            // 使用 addToRefresh 强制立即刷新玩家周围的 region（优先加载）
            // 注意：viewRegions 已在前面定义，这里直接使用
            try {
                Method addToRefresh = mapProcessorClass.getMethod("addToRefresh", int.class, int.class, int.class, String.class);
                int priorityRefreshCount = 0;
                for (XaeroMapIntegrator.RegionCoord viewRegion : viewRegions) {
                    if (regionsToReload.contains(viewRegion)) {
                        addToRefresh.invoke(mapProcessor, Integer.MAX_VALUE, viewRegion.x(), viewRegion.z(), "sync priority");
                        priorityRefreshCount++;
                        LOGGER.debug("Priority refresh for view region ({}, {})", viewRegion.x(), viewRegion.z());
                    }
                }
                LOGGER.info("Priority refresh requested for {} view distance regions", priorityRefreshCount);
            } catch (Exception e) {
                LOGGER.warn("Could not call addToRefresh for priority regions: {}", e.getMessage());
            }

            // 延迟恢复 chunk updates，让 region 加载优先执行
            // Step 1: 先恢复全局写入（不影响被保护的 region）
            // Step 2: 等待一段时间让 region 加载完成
            // Step 3: 恢复 region 级别写入并刷新纹理
            final java.util.List<Object> finalPausedRegions = pausedViewRegions;
            final Object finalMapProcessor = mapProcessor;
            new Thread(() -> {
                try {
                    // 等待 5s 让 region 加载
                    Thread.sleep(5000);
                    Minecraft.getInstance().execute(() -> {
                        // 恢复全局写入
                        resumeChunkUpdates();
                        LOGGER.info("Global chunk updates resumed");

                        // 再等待 5s，确保 region 加载完成后恢复 region 级别写入
                        new Thread(() -> {
                            try {
                                Thread.sleep(5000);
                                Minecraft.getInstance().execute(() -> {
                                    // 恢复 region 级别写入
                                    resumeRegionWriting(finalPausedRegions, finalMapProcessor);
                                    LOGGER.info("Region-level writing resumed for {} view regions", finalPausedRegions.size());
                                });
                            } catch (InterruptedException ignored) {}
                        }, "MapSyncer-ResumeRegions").start();
                    });
                } catch (InterruptedException ignored) {}
            }, "MapSyncer-ResumeDelay").start();

        } catch (Exception e) {
            LOGGER.error("Failed to trigger Xaero map reload", e);
            Minecraft.getInstance().player.displayClientMessage(ChatUtils.error("mapsyncer.cache.reload_failed"), false);
            // 错误情况下立即恢复 chunk updates
            resumeChunkUpdates();
        }
    }

    /**
     * 同步完成后恢复区块更新。
     * 重新启用 Xaero 的区块处理，允许地图继续更新。
     */
    private static void resumeChunkUpdates() {
        syncInProgress = false;
        XaeroMapIntegrator.enableChunkUpdates();
        LOGGER.info("Sync complete, chunk updates resumed");
    }

    /**
     * 恢复 region 级别的写入保护。
     * 对之前使用 pushWriterPause 暂停的 region 调用 popWriterPause 恢复写入，
     * 并刷新纹理以显示加载的数据。
     *
     * @param pausedRegions 已暂停的 region 列表
     * @param mapProcessor MapProcessor 实例
     */
    private static void resumeRegionWriting(java.util.List<Object> pausedRegions, Object mapProcessor) {
        try {
            Class<?> mapRegionClass = Class.forName("xaero.map.region.MapRegion");
            Method popWriterPause = mapRegionClass.getMethod("popWriterPause");
            Method addToRefresh = Class.forName("xaero.map.MapProcessor").getMethod("addToRefresh", int.class, int.class, int.class, String.class);

            for (Object region : pausedRegions) {
                try {
                    // 恢复 region 写入
                    popWriterPause.invoke(region);

                    // 刷新 region 纹理以显示加载的数据
                    java.lang.reflect.Field regionXField = mapRegionClass.getDeclaredField("regionX");
                    java.lang.reflect.Field regionZField = mapRegionClass.getDeclaredField("regionZ");
                    regionXField.setAccessible(true);
                    regionZField.setAccessible(true);
                    int rx = regionXField.getInt(region);
                    int rz = regionZField.getInt(region);

                    addToRefresh.invoke(mapProcessor, Integer.MAX_VALUE, rx, rz, "sync resume");
                    LOGGER.debug("Resumed writing and refreshed region ({}, {})", rx, rz);
                } catch (Exception e) {
                    LOGGER.warn("Failed to resume region writing: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to resume region-level writing", e);
        }
    }

    /**
     * 选择性清除 Xaero 缓存文件，并返回所有同步的区域。
     * 所有同步的区域都需要重新加载，以确保服务端数据正确显示。
     *
     * @return 需要重新加载的区域集合（所有同步的区域）
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

            // All synced regions need to be reloaded (including view distance regions)
            regionsToReload.addAll(syncedRegions);
            LOGGER.info("Checking cache for {} synced regions, all will be reloaded", syncedRegions.size());

            // Find all cache directories (cache, cache_1, cache_<version>)
            java.util.List<Path> cacheDirs = findCacheDirectories(mwDir);
            if (cacheDirs.isEmpty()) {
                LOGGER.info("No cache directories found");
                return regionsToReload;
            }

            int cacheClearedCount = 0;

            // For each synced region, delete cache if exists
            for (XaeroMapIntegrator.RegionCoord region : syncedRegions) {
                String cacheFileName = region.x() + "_" + region.z() + ".xwmc";

                // Check all cache directories for this region's cache
                for (Path cacheDir : cacheDirs) {
                    Path cacheFile = cacheDir.resolve(cacheFileName);
                    if (cacheFile.toFile().exists()) {
                        try {
                            java.nio.file.Files.deleteIfExists(cacheFile);
                            cacheClearedCount++;
                            LOGGER.debug("Deleted cache file: {}", cacheFile);
                        } catch (Exception e) {
                            LOGGER.warn("Failed to delete cache file: {}", cacheFile);
                        }
                        break; // Found and deleted, no need to check other directories
                    }
                }
            }

            LOGGER.info("Cache cleared: {} files, {} regions will be reloaded", cacheClearedCount, regionsToReload.size());

            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(ChatUtils.desc("mapsyncer.cache.status", cacheClearedCount, regionsToReload.size()), false);
            }

        } catch (Exception e) {
            LOGGER.warn("Failed to clear cache: {}", e.getMessage());
        }

        return regionsToReload;
    }

    /**
     * 在 mw 目录下查找所有缓存目录。
     * 缓存目录命名格式：cache、cache_1、cache_<version>。
     *
     * @param mwDir mw 目录路径
     * @return 缓存目录列表
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
     * 删除缓存目录及其中的所有 .xwmc 文件。
     * 递归遍历目录，删除所有缓存文件。
     *
     * @param cacheDir 缓存目录路径
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

    /**
     * 卸载视野范围内的region以便同步当前维度时重新加载服务端数据。
     * 在同步当前维度时调用。
     *
     * @param targetDimension 目标维度（Xaero格式）
     */
    public static void prepareSyncForDimension(String targetDimension) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }

        // 检查是否同步当前维度
        String currentXaeroDim = DimensionPathMapping.getInstance().toXaeroDimension(
                mc.level.dimension().location().toString());

        if (targetDimension.equals(currentXaeroDim)) {
            // 同步当前维度，卸载视野范围内的region
            LOGGER.info("Syncing current dimension {}, unloading view distance regions", targetDimension);
            int unloaded = XaeroMapIntegrator.unloadViewDistanceRegions();
            if (unloaded > 0 && mc.player != null) {
                mc.player.displayClientMessage(
                        ChatUtils.desc("mapsyncer.sync.unloading_regions", unloaded), false);
            }
        }
    }
}
