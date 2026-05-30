package com.mapsyncer.client;

import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.PayloadContext;
import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import com.mapsyncer.platform.XaeroReflectionHelper;
import com.mapsyncer.platform.XaeroReflectionHelper;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.DimensionPathMapping;
import com.mapsyncer.util.HashUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 客户端断开连接时的统一清理入口。
 * 由各平台的断开连接事件处理器调用。
 */
public class MapPacketHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapPacketHandler.class);

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
    private static volatile Set<XaeroMapDataHandler.RegionCoord> updatedRegionCoords = new HashSet<>();

    /** 已加载的区域集合（避免重复加载） */
    private static volatile Set<XaeroMapDataHandler.RegionCoord> loadedRegions = new HashSet<>();

    /**
     * 检查当前同步是否陈旧（运行时间过长）。
     */
    public static boolean isSyncStale() {
        if (!syncInProgress || syncStartTime == 0) {
            return false;
        }
        return System.currentTimeMillis() - syncStartTime > STALE_SYNC_TIMEOUT_MS;
    }

    /**
     * 清除所有累积的同步数据，防止内存泄漏。
     */
    public static void clearSyncData() {
        syncInProgress = false;
        lastMwDir = null;
        syncStartTime = 0;
        clearReceivedChunks();
        loadedRegions.clear();
        LOGGER.info("Cleared sync data to prevent memory leak");
    }

    /**
     * 清除累积的区域坐标集合，释放内存。
     */
    public static void clearReceivedChunks() {
        if (updatedRegionCoords != null) {
            updatedRegionCoords.clear();
        }
    }

    /**
     * 客户端断开连接时的统一清理入口。
     * 清理所有同步状态、反射缓存、哈希计算线程池和时间戳缓存。
     */
    public static void onDisconnect() {
        resetServerStatus();
        clearSyncData();
        XaeroReflectionHelper.clearCache();
        XaeroMapDataHandler.clearRegionTracking();
        ClientHashManager.shutdown();
        ClientTimestampCache.resetInstance();
        LOGGER.info("Client disconnected, all resources cleaned up");
    }

    /**
     * 注册数据包处理器（公共逻辑）。
     * 由平台特定的 register() 方法调用。
     */
    public static void registerHandlers() {
        var handler = NetworkManager.getHandler();

        // 注册同步响应处理器
        handler.registerSyncResponseHandler(MapPacketHandler::handleSyncResponse);

        // 注册进度更新处理器
        handler.registerSyncProgressHandler(MapPacketHandler::handleProgressUpdate);

        // 注册服务端已安装通知处理器
        handler.registerServerInstalledHandler((payload, ctx) -> {
            ctx.enqueueWork(() -> {
                serverInstalled = true;
                serverVersion = payload.version();
                LOGGER.info("Server has MapSyncer installed, version: {}", serverVersion);
            });
        });

        // 注册同步请求处理器（清除陈旧同步数据）
        handler.registerSyncRequestHandler((payload, ctx) -> {
            ctx.enqueueWork(() -> {
                if (isSyncStale()) {
                    clearSyncData();
                    LOGGER.warn("Cleared stale sync data before starting new sync");
                }
                syncStartTime = System.currentTimeMillis();
                updatedRegionCoords.clear();
            });
        });
    }

    /**
     * 检查服务端是否已安装 MapSyncer
     */
    public static boolean isServerInstalled() {
        return serverInstalled;
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
     */
    private static void handleSyncResponse(SyncResponsePayload payload, PayloadContext context) {
        context.enqueueWork(() -> {
            String status = payload.status();
            List<ChunkMapData> chunks = payload.chunks();
            int serverWorldId = payload.worldId();

            LOGGER.debug("Received sync response: status={}, chunks={}, isComplete={}", status, chunks.size(), payload.isComplete());

            // 获取时间戳缓存用于同步状态管理
            Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
            ClientTimestampCache tsCache = serverDir != null && serverDir.toFile().exists()
                    ? ClientTimestampCache.getInstance(serverDir) : null;

            // 根据状态决定处理方式
            if ("no_cache".equals(status) || "dim_not_available".equals(status)) {
                LOGGER.info("Server returned error status: {}, no sync needed", status);
                clearSyncData();
                clearReflectionCache();
                SyncProgressTracker.cancelTracking();
                if (tsCache != null) {
                    tsCache.clearSyncState();
                }
                return;
            }

            if ("uptodate".equals(status)) {
                LOGGER.info("Map is up-to-date, no sync needed");
                clearSyncData();
                clearReflectionCache();
                SyncProgressTracker.cancelTracking();
                if (tsCache != null) {
                    tsCache.markSyncComplete();
                }
                return;
            }

            // status == "ok"，有数据需要同步
            if (isSyncStale()) {
                clearSyncData();
                clearReflectionCache();
                LOGGER.warn("Sync was stale, cleared accumulated data");
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.sendSystemMessage(ChatUtils.error("mapsyncer.sync.timeout"));
                }
                return;
            }

            // 首次收到数据时初始化反射缓存
            if (!syncInProgress) {
                syncInProgress = true;
                LOGGER.info("Starting sync (streaming mode)");
                initializeReflectionCache();
            }

            // 获取当前视距范围
            Minecraft mc = Minecraft.getInstance();
            boolean isCaveDimension = mc.level != null && mc.level.dimension() == Level.NETHER;

            // 流式处理：写入后立即处理
            for (ChunkMapData chunk : chunks) {
                XaeroMapDataHandler.RegionCoord coord = new XaeroMapDataHandler.RegionCoord(
                    chunk.regionX, chunk.regionZ, chunk.caveLayer);
                updatedRegionCoords.add(coord);

                // 写入文件
                Path mwDir = XaeroMapIntegrator.writeChunkDataAndGetMwDir(chunk, serverWorldId);
                if (mwDir != null) {
                    lastMwDir = mwDir;
                }

                // 根据维度类型决定处理哪个层
                boolean shouldProcess = isCaveDimension
                    ? (chunk.caveLayer != Integer.MAX_VALUE)  // 地狱：洞穴层
                    : (chunk.caveLayer == Integer.MAX_VALUE); // 主世界/末地：地表层

                // 判断是否在视距内
                Set<XaeroMapDataHandler.RegionCoord> viewRegionsForLayer =
                    XaeroMapIntegrator.getViewDistanceRegions(chunk.caveLayer);
                boolean inViewDistance = viewRegionsForLayer.contains(coord);

                if (shouldProcess) {
                    // 清除缓存文件并立即触发加载
                    clearSingleRegionCache(coord);
                    triggerSingleRegionLoad(coord, chunk.caveLayer, inViewDistance);
                    LOGGER.debug("区域 ({}, {}) layer={} inView={} 已清除缓存并触发加载",
                        coord.x(), coord.z(), chunk.caveLayer, inViewDistance);
                }

                // 更新时间戳缓存
                if (tsCache != null) {
                    String relativePath = buildRelativePathForCache(chunk);
                    String hash = HashUtils.computeHash(chunk.data);
                    tsCache.update(relativePath, chunk.timestampSeconds, hash);
                }
            }

            // 保存时间戳缓存
            if (tsCache != null && !chunks.isEmpty()) {
                tsCache.save();
            }

            // 同步完成时处理
            if (payload.isComplete()) {
                int totalReceived = updatedRegionCoords.size();
                LOGGER.info("同步完成: 总计 {} 个区域已处理", totalReceived);

                if (!updatedRegionCoords.isEmpty()) {
                    XaeroMapDataHandler.recordUpdatedRegionCoords(updatedRegionCoords);
                    SyncProgressTracker.completeWithCount(totalReceived);

                    resumeChunkUpdates();
                    if (tsCache != null) {
                        tsCache.markSyncComplete();
                    }
                } else {
                    resumeChunkUpdates();
                    LOGGER.info("Sync complete with no data received");
                    if (tsCache != null) {
                        tsCache.markSyncComplete();
                    }
                }

                clearSyncState();
                clearReflectionCache();
            }
        });
    }

    /**
     * 处理服务端发送的进度更新数据包。
     */
    private static void handleProgressUpdate(SyncProgressPayload payload, PayloadContext context) {
        context.enqueueWork(() -> {
            SyncProgressTracker.update(payload.processed(), payload.total(), payload.status());
        });
    }

    /**
     * 同步完成后恢复区块更新状态。
     */
    private static void resumeChunkUpdates() {
        syncInProgress = false;
        LOGGER.info("Sync complete");
    }

    /**
     * 清理同步状态（非反射缓存）。
     */
    private static void clearSyncState() {
        updatedRegionCoords.clear();
        loadedRegions.clear();
        lastMwDir = null;
        syncStartTime = 0;
    }

    /**
     * 清理反射 API 缓存。
     */
    private static void clearReflectionCache() {
        XaeroReflectionHelper.clearCache();
    }

    // ========== 边接收边加载优化方法 ==========

    /**
     * 初始化反射 API 缓存。
     */
    private static void initializeReflectionCache() {
        if (XaeroReflectionHelper.isInitialized()) {
            LOGGER.debug("反射缓存已初始化，跳过重复初始化");
            return;
        }

        LOGGER.info("开始初始化反射 API 缓存...");
        boolean initSuccess = XaeroReflectionHelper.initialize();

        if (initSuccess) {
            LOGGER.info("XaeroReflectionHelper 初始化成功");
            boolean regionDetectSuccess = XaeroReflectionHelper.setRegionDetectionComplete(true);
            if (regionDetectSuccess) {
                LOGGER.info("regionDetectionComplete 设置为 true，反射功能就绪");
            } else {
                LOGGER.error("regionDetectionComplete 设置失败，getLeafMapRegion 可能会返回 null");
            }
        } else {
            LOGGER.error("XaeroReflectionHelper 初始化失败！反射功能完全不可用");
            LOGGER.error("可能原因：");
            LOGGER.error("  1. Xaero's World Map 模组未安装");
            LOGGER.error("  2. Xaero 版本与 MapSyncer 不兼容");
            LOGGER.error("  3. 类加载器问题");
            LOGGER.error("地图同步功能将无法正常工作，数据会写入文件但不会触发重新加载");
        }
    }

    /**
     * 立即加载单个区域。
     */
    private static void triggerSingleRegionLoad(XaeroMapDataHandler.RegionCoord coord, int caveLayer, boolean inViewDistance) {
        if (!XaeroReflectionHelper.isInitialized()) {
            LOGGER.warn("反射缓存未初始化，无法加载区域 ({}, {}) layer={}", coord.x(), coord.z(), caveLayer);
            return;
        }

        // 避免重复加载
        if (loadedRegions.contains(coord)) {
            LOGGER.debug("区域 ({}, {}) layer={} 已加载，跳过", coord.x(), coord.z(), caveLayer);
            return;
        }

        try {
            // 获取或创建 MapRegion
            Object mapRegion = XaeroReflectionHelper.getLeafMapRegion(caveLayer, coord.x(), coord.z(), true);
            if (mapRegion == null) {
                LOGGER.warn("无法创建 MapRegion ({}, {}) layer={}", coord.x(), coord.z(), caveLayer);
                return;
            }

            // 调试：检查 region 的属性是否正确
            String regionWorldId = XaeroReflectionHelper.getWorldId(mapRegion);
            String regionDimId = XaeroReflectionHelper.getDimId(mapRegion);
            String regionMwId = XaeroReflectionHelper.getMwId(mapRegion);
            LOGGER.info("Region ({}, {}) 属性: worldId={}, dimId={}, mwId={}, lastMwDir={}",
                coord.x(), coord.z(), regionWorldId, regionDimId, regionMwId, lastMwDir);

            // 准备区域加载（关键步骤）
            boolean prepareSuccess = XaeroReflectionHelper.prepareRegionLoad(mapRegion);
            if (!prepareSuccess) {
                LOGGER.error("区域 ({}, {}) layer={} 准备加载失败，跳过此区域", coord.x(), coord.z(), caveLayer);
                return;
            }

            // 设置 loadState = LOAD_STATE_CLEARED（需要加载）
            boolean setStateSuccess = XaeroReflectionHelper.setLoadState(mapRegion, XaeroReflectionHelper.LOAD_STATE_CLEARED);
            if (!setStateSuccess) {
                LOGGER.error("区域 ({}, {}) layer={} 设置 loadState 失败，跳过此区域", coord.x(), coord.z(), caveLayer);
                return;
            }

            // 请求加载
            String reason = inViewDistance ? "sync view" : "sync outside";
            boolean loadSuccess = XaeroReflectionHelper.requestLoad(mapRegion, reason, true);
            if (!loadSuccess) {
                LOGGER.error("区域 ({}, {}) layer={} 请求加载失败", coord.x(), coord.z(), caveLayer);
                return;
            }

            if (inViewDistance) {
                LOGGER.info("区域 ({}, {}) layer={} 视距内，插入队头优先加载", coord.x(), coord.z(), caveLayer);
            } else {
                LOGGER.info("区域 ({}, {}) layer={} 视距外，添加到加载队列", coord.x(), coord.z(), caveLayer);
            }

            loadedRegions.add(coord);

        } catch (Exception e) {
            LOGGER.error("立即加载区域 ({}, {}) layer={} 失败: {}", coord.x(), coord.z(), caveLayer, e.getMessage(), e);
        }
    }

    /**
     * 清除单个区域的缓存文件。
     */
    private static void clearSingleRegionCache(XaeroMapDataHandler.RegionCoord coord) {
        if (lastMwDir == null) return;

        String cacheFileName = coord.x() + "_" + coord.z() + ".xwmc";
        List<Path> cacheDirs = findCacheDirectories(lastMwDir);

        for (Path cacheDir : cacheDirs) {
            Path cacheFile = cacheDir.resolve(cacheFileName);
            if (cacheFile.toFile().exists()) {
                try {
                    java.nio.file.Files.deleteIfExists(cacheFile);
                    LOGGER.debug("已清除缓存: {}", cacheFile);
                } catch (Exception e) {
                    LOGGER.warn("清除缓存失败: {}", cacheFile);
                }
                return;
            }
        }
    }

    /**
     * 构建时间戳缓存的服务器格式相对路径。
     */
    private static String buildRelativePathForCache(ChunkMapData chunk) {
        String xaeroDim = chunk.dimension;
        if (chunk.caveLayer == Integer.MAX_VALUE) {
            return xaeroDim + "/" + chunk.regionX + "_" + chunk.regionZ;
        } else {
            return xaeroDim + "/caves/" + chunk.caveLayer + "/" + chunk.regionX + "_" + chunk.regionZ;
        }
    }

    /**
     * 在 mw 目录下查找所有缓存目录。
     */
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

            LOGGER.debug("Found {} cache directories", cacheDirs.size());
        } catch (Exception e) {
            LOGGER.warn("Failed to find cache directories: {}", e.getMessage());
        }

        return cacheDirs;
    }

    /**
     * 卸载视野范围内的region以便同步当前维度时重新加载服务端数据。
     */
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
                mc.player.sendSystemMessage(
                        ChatUtils.desc("mapsyncer.sync.unloading_regions", unloaded));
            }
        }
    }
}
