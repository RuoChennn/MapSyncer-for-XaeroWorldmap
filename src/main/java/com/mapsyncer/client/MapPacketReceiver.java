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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    /**
     * 检查同步是否正在进行中。
     *
     * @return true 表示同步正在进行
     */
    public static boolean isSyncInProgress() {
        return syncInProgress;
    }

    /** 服务端是否已安装 MapSyncer（加入服务器时检测） */
    private static volatile boolean serverInstalled = false;

    /** 服务端版本号 */
    private static volatile String serverVersion = "";

    /** 最后写入的 mw 目录，用于缓存清除 */
    private static volatile Path lastMwDir = null;

    /** 同步开始时间，用于检测陈旧的同步（防止内存泄漏） */
    private static volatile long syncStartTime = 0;

    /** 陈旧同步超时时间（10分钟） */
    private static final long STALE_SYNC_TIMEOUT_MS = 10 * 60 * 1000;

    /** 同步期间更新的区域坐标集合（仅存储坐标，不存储数据，节省内存） */
    private static volatile Set<XaeroMapIntegrator.RegionCoord> updatedRegionCoords = new HashSet<>();

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
        clearReceivedChunks();
        LOGGER.info("Cleared sync data to prevent memory leak");
    }

    /**
     * 清除累积的区域坐标集合，释放内存。
     * 在同步完成、中断或服务器停止时调用。
     */
    public static void clearReceivedChunks() {
        if (updatedRegionCoords != null) {
            updatedRegionCoords.clear();
        }
    }

    /**
     * 获取已接收区域数量。
     * 用于监控同步进度。
     *
     * @return 已接收的区域数量
     */
    public static int getReceivedRegionCount() {
        return updatedRegionCoords != null ? updatedRegionCoords.size() : 0;
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
                    // 发送请求时暂不暂停区块更新，等收到服务端确认有数据后再暂停
                    syncStartTime = System.currentTimeMillis(); // Track start time
                    // Clear accumulated region coords for new sync session
                    updatedRegionCoords.clear();
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

        // Client receives server installed notification when joining server
        registrar.playToClient(
                PacketHandler.ServerInstalledPayload.TYPE,
                PacketHandler.ServerInstalledPayload.STREAM_CODEC,
                (payload, ctx) -> {
                    serverInstalled = true;
                    serverVersion = payload.version();
                    LOGGER.info("Server has MapSyncer installed, version: {}", serverVersion);
                }
        );
    }

    /**
     * 检查服务端是否已安装 MapSyncer
     *
     * @return true 表示服务端已安装
     */
    public static boolean isServerInstalled() {
        return serverInstalled;
    }

    /**
     * 获取服务端版本号
     *
     * @return 服务端版本号字符串
     */
    public static String getServerVersion() {
        return serverVersion;
    }

    /**
     * 重置服务端安装状态（离开服务器时调用）
     */
    public static void resetServerStatus() {
        serverInstalled = false;
        serverVersion = "";
    }

    /**
     * 处理服务端返回的同步响应数据包。
     * 根据服务端状态决定是否暂停区块更新和处理数据。
     *
     * <p>状态处理：</p>
     * <ul>
     *   <li>"ok" - 有数据同步，暂停区块更新，处理数据</li>
     *   <li>"uptodate" - 地图已是最新，不暂停区块更新，显示消息</li>
     *   <li>"no_cache" - 服务端无缓存，不暂停区块更新</li>
     *   <li>"dim_not_available" - 维度不存在，不暂停区块更新</li>
     * </ul>
     *
     * @param payload 同步响应数据包
     * @param context 数据包上下文
     */
    private static void handleSyncResponse(PacketHandler.SyncResponsePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            String status = payload.status();
            List<ChunkMapData> chunks = payload.chunks();
            int serverWorldId = payload.worldId();

            LOGGER.info("Received sync response: status={}, chunks={}, isComplete={}", status, chunks.size(), payload.isComplete());

            // 获取时间戳缓存用于同步状态管理
            Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
            ClientTimestampCache tsCache = serverDir != null && serverDir.toFile().exists()
                    ? ClientTimestampCache.getInstance(serverDir) : null;

            // 根据状态决定处理方式
            if ("no_cache".equals(status) || "dim_not_available".equals(status)) {
                // 服务端无缓存或维度不存在，不暂停区块更新，直接返回
                LOGGER.info("Server returned error status: {}, no sync needed", status);
                clearSyncData();
                if (tsCache != null) {
                    tsCache.clearSyncState();
                }
                return;
            }

            if ("uptodate".equals(status)) {
                // 地图已是最新，不暂停区块更新
                LOGGER.info("Map is up-to-date, no sync needed");
                clearSyncData();
                if (tsCache != null) {
                    tsCache.markSyncComplete();
                }
                return;
            }

            // status == "ok"，有数据需要同步
            // Check for stale sync (running too long) and clear if needed
            if (isSyncStale()) {
                clearSyncData();
                LOGGER.warn("Sync was stale, cleared accumulated data");
                if (tsCache != null) {
                    tsCache.markSyncInterrupted();
                }
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.displayClientMessage(ChatUtils.error("mapsyncer.sync.timeout"), false);
                }
                return;
            }

            // 首次收到数据时才暂停区块更新
            if (!syncInProgress) {
                syncInProgress = true;
                XaeroMapIntegrator.disableChunkUpdates();
                LOGGER.info("Starting sync, chunk updates disabled");
            }

            // 只存储区域坐标，不存储完整数据（节省内存）
            for (ChunkMapData chunk : chunks) {
                updatedRegionCoords.add(new XaeroMapIntegrator.RegionCoord(chunk.regionX, chunk.regionZ, chunk.caveLayer));
            }

            // Log region count warning if accumulating too many
            int regionCount = updatedRegionCoords.size();
            if (regionCount > 100) {
                LOGGER.debug("Received {} regions during sync", regionCount);
            }

            // Write all map data from server (覆盖客户端数据)
            if (!chunks.isEmpty()) {
                lastMwDir = XaeroMapIntegrator.writeMapDataAndReturnDir(chunks, serverWorldId);
            }

            if (payload.isComplete()) {
                // 只有实际收到数据时才记录和显示缓存清除消息
                if (!updatedRegionCoords.isEmpty()) {
                    XaeroMapIntegrator.recordUpdatedRegionCoords(updatedRegionCoords);
                    // 使用实际接收的区域数量显示完成消息，而非依赖进度追踪器的 total
                    int receivedCount = updatedRegionCoords.size();
                    SyncProgressTracker.completeWithCount(receivedCount);
                    triggerXaeroReloadAndResume();
                    // 标记同步完成
                    if (tsCache != null) {
                        tsCache.markSyncComplete();
                    }
                } else {
                    // 未收到数据（维度不存在或已是最新），直接恢复区块更新
                    resumeChunkUpdates();
                    LOGGER.info("Sync complete with no data received");
                    if (tsCache != null) {
                        tsCache.markSyncComplete();
                    }
                }

                // Clear accumulated region coords after sync complete
                updatedRegionCoords.clear();
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
     *
     * <p>处理流程：</p>
     * <ol>
     *   <li>视距内 region：loadState=4 + pushWriterPause + requestLoad(优先)</li>
     *   <li>视距外 region：loadState=4 + requestLoad(普通)</li>
     *   <li>立即恢复全局 chunk updates</li>
     *   <li>启动 tick 监听器等待视距内 region 加载完成后解除 region 写保护</li>
     * </ol>
     */
    private static void triggerXaeroReloadAndResume() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            LOGGER.debug("=== 开始 triggerXaeroReloadAndResume ===");

            java.util.Set<XaeroMapIntegrator.RegionCoord> regionsToReload = clearXaeroCacheSelective();
            LOGGER.info("需要重载的 region: {} 个", regionsToReload.size());

            if (regionsToReload.isEmpty()) {
                LOGGER.info("无需重载 region，缓存已清除");
                resumeChunkUpdates();
                return;
            }

            // 获取 Xaero 核心 API 对象
            Class<?> worldMapSessionClass = Class.forName("xaero.map.WorldMapSession");
            Object session = worldMapSessionClass.getMethod("getCurrentSession").invoke(null);
            if (session == null) {
                LOGGER.warn("无法获取 WorldMapSession");
                resumeChunkUpdates();
                return;
            }

            Class<?> mapProcessorClass = Class.forName("xaero.map.MapProcessor");
            Object mapProcessor = worldMapSessionClass.getMethod("getMapProcessor").invoke(session);
            if (mapProcessor == null) {
                LOGGER.warn("无法获取 MapProcessor");
                resumeChunkUpdates();
                return;
            }

            Class<?> mapSaveLoadClass = Class.forName("xaero.map.file.MapSaveLoad");
            Object mapSaveLoad = mapProcessorClass.getMethod("getMapSaveLoad").invoke(mapProcessor);
            if (mapSaveLoad == null) {
                LOGGER.warn("无法获取 MapSaveLoad");
                resumeChunkUpdates();
                return;
            }

            // 扫描新写入的文件
            mapSaveLoadClass.getMethod("detectRegions", int.class).invoke(mapSaveLoad, 20);
            LOGGER.debug("detectRegions 已调用");

            // 准备反射方法和字段
            Method getLeafMapRegion = mapProcessorClass.getMethod("getLeafMapRegion", int.class, int.class, int.class, boolean.class);
            Method requestLoad = mapSaveLoadClass.getMethod("requestLoad", Class.forName("xaero.map.region.MapRegion"), String.class, boolean.class);
            Class<?> mapRegionClass = Class.forName("xaero.map.region.MapRegion");
            java.lang.reflect.Field loadStateField = mapRegionClass.getDeclaredField("loadState");
            loadStateField.setAccessible(true);

            java.util.Set<XaeroMapIntegrator.RegionCoord> viewRegions = XaeroMapIntegrator.getViewDistanceRegions();
            LOGGER.debug("视距内 region: {} 个", viewRegions.size());

            java.util.List<Object> pausedViewRegions = new java.util.ArrayList<>();
            int viewDistanceCount = 0;
            int outsideViewCount = 0;

            // Step 1: 处理视距内 region（优先加载 + 写保护）
            for (XaeroMapIntegrator.RegionCoord coord : regionsToReload) {
                if (!viewRegions.contains(coord)) continue;

                Object mapRegion = getLeafMapRegion.invoke(mapProcessor, Integer.MAX_VALUE, coord.x(), coord.z(), true);
                if (mapRegion == null) {
                    LOGGER.warn("视距内 region ({}, {}) 无法创建", coord.x(), coord.z());
                    continue;
                }

                byte currentLoadState = loadStateField.getByte(mapRegion);
                LOGGER.debug("视距内 ({}, {}) loadState={} -> 4", coord.x(), coord.z(), currentLoadState);
                loadStateField.setByte(mapRegion, (byte) 4);

                try {
                    Method pushWriterPause = mapRegionClass.getMethod("pushWriterPause");
                    pushWriterPause.invoke(mapRegion);
                    pausedViewRegions.add(mapRegion);
                    LOGGER.debug("视距内 ({}, {}) 已 pauseWriterPause", coord.x(), coord.z());
                } catch (Exception e) {
                    LOGGER.warn("视距内 ({}, {}) pauseWriterPause 失败: {}", coord.x(), coord.z(), e.getMessage());
                }

                requestLoad.invoke(mapSaveLoad, mapRegion, "sync priority", true);
                viewDistanceCount++;
            }

            // Step 2: 处理视距外 region（普通加载）
            for (XaeroMapIntegrator.RegionCoord coord : regionsToReload) {
                if (viewRegions.contains(coord)) continue;

                Object mapRegion = getLeafMapRegion.invoke(mapProcessor, Integer.MAX_VALUE, coord.x(), coord.z(), true);
                if (mapRegion == null) {
                    LOGGER.warn("视距外 region ({}, {}) 无法创建", coord.x(), coord.z());
                    continue;
                }

                byte currentLoadState = loadStateField.getByte(mapRegion);
                LOGGER.debug("视距外 ({}, {}) loadState={} -> 4", coord.x(), coord.z(), currentLoadState);
                loadStateField.setByte(mapRegion, (byte) 4);

                requestLoad.invoke(mapSaveLoad, mapRegion, "sync reload", false);
                outsideViewCount++;
            }

            LOGGER.info("区域处理完成: 视距内 {} 个, 视距外 {} 个", viewDistanceCount, outsideViewCount);

            XaeroMapIntegrator.clearPreUnloadedRegions();

            resumeChunkUpdates();

            // 启动 tick 监听器，等待视距内 region 加载完成后解除写保护
            if (!pausedViewRegions.isEmpty()) {
                RegionLoadListener.startListening(pausedViewRegions, mapProcessor);
                LOGGER.info("已启动 region 加载监听器，等待 {} 个视距内 region", pausedViewRegions.size());
            }

        } catch (Exception e) {
            LOGGER.error("触发 Xaero 地图重载失败", e);
            Minecraft.getInstance().player.displayClientMessage(ChatUtils.error("mapsyncer.cache.reload_failed"), false);
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
