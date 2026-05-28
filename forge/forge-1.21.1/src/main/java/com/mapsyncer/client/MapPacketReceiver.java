package com.mapsyncer.client;

import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.PayloadContext;
import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.DimensionPathMapping;
import com.mapsyncer.util.HashUtils;
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
 * 地图数据包接收器。
 * 处理从服务端接收的地图同步数据包，并负责写入到 Xaero 地图目录。
 *
 * <p>主要功能：</p>
 * <ul>
 *   <li>注册数据包处理器，处理同步请求、响应和进度更新</li>
 *   <li>写入同步数据到 Xaero 目录，边接收边加载</li>
 *   <li>重置区域加载状态，触发地图重新加载</li>
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

    /** 已加载的区域集合（避免重复加载） */
    private static volatile Set<XaeroMapIntegrator.RegionCoord> loadedRegions = new HashSet<>();

    /** 反射 API 缓存（避免重复反射调用开销） */
    private static volatile Object cachedMapProcessor = null;
    private static volatile Object cachedMapSaveLoad = null;
    private static volatile Method cachedGetLeafMapRegion = null;
    private static volatile Method cachedRequestLoad = null;
    private static volatile java.lang.reflect.Field cachedLoadStateField = null;
    private static volatile Method cachedCancelRefresh = null;

    /** 反射 API 是否已初始化 */
    private static volatile boolean reflectionInitialized = false;

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
     * 注册数据包处理器。
     * 处理同步请求（客户端发送）、同步响应（服务端返回）和进度更新。
     *
     * @param event 注册数据包处理器事件
     */
    public static void register(final Object event) {
        NetworkManager.registerHandlers(event);

        // 注册同步响应处理器
        @SuppressWarnings("unchecked")
        var handler = (com.mapsyncer.network.NetworkHandler<Object, Object>) NetworkManager.getHandler();
        handler.registerSyncResponseHandler(
            (payload, context) -> handleSyncResponse(payload, context)
        );

        // 注册进度更新处理器
        handler.registerSyncProgressHandler(
            (payload, context) -> handleProgressUpdate(payload, context)
        );

        // 注册服务端已安装通知处理器
        handler.registerServerInstalledHandler(
            (payload, context) -> {
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
     * 重置服务端安装状态（离开服务器时调用）
     */
    public static void resetServerStatus() {
        serverInstalled = false;
        serverVersion = "";
    }

    /**
     * 处理服务端返回的同步响应数据包。
     * 实现边接收边加载优化，每个 region 写入后立即触发加载。
     *
     * <p>状态处理：</p>
     * <ul>
     *   <li>"ok" - 有数据同步，流式处理每个 region</li>
     *   <li>"uptodate" - 地图已是最新，直接返回</li>
     *   <li>"no_cache" - 服务端无缓存，直接返回</li>
     *   <li>"dim_not_available" - 维度不存在，直接返回</li>
     * </ul>
     *
     * @param payload 同步响应数据包
     * @param context 数据包上下文
     */
    private static void handleSyncResponse(SyncResponsePayload payload, PayloadContext context) {
        NetworkManager.getHandler().enqueueWork(context, () -> {
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

            // status == "ok"，有数据需要同步
            if (isSyncStale()) {
                clearSyncData();
                clearReflectionCache();
                LOGGER.warn("Sync was stale, cleared accumulated data");
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.displayClientMessage(ChatUtils.error("mapsyncer.sync.timeout"), false);
                }
                return;
            }

            // 首次收到数据时初始化反射缓存
            if (!syncInProgress) {
                syncInProgress = true;
                // 不再使用全局暂停，边接收边加载
                LOGGER.info("Starting sync (streaming mode)");
                initializeReflectionCache();
            }

            // 获取当前视距范围（用于判断视距内/外）
            // 注意：视距判断需要使用 chunk 的 caveLayer，而不是默认的地表层
            Minecraft mc = Minecraft.getInstance();
            boolean isCaveDimension = mc.level != null && mc.level.dimension() == Level.NETHER;

            // 流式处理：写入后立即处理
            for (ChunkMapData chunk : chunks) {
                XaeroMapIntegrator.RegionCoord coord = new XaeroMapIntegrator.RegionCoord(
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

                // 判断是否在视距内 - 使用 chunk 的 caveLayer 进行判断
                Set<XaeroMapIntegrator.RegionCoord> viewRegionsForLayer =
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
                    XaeroMapIntegrator.recordUpdatedRegionCoords(updatedRegionCoords);
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
     * 更新同步进度追踪器的状态。
     *
     * @param payload 进度更新数据包
     * @param context 数据包上下文
     */
    private static void handleProgressUpdate(SyncProgressPayload payload, PayloadContext context) {
        SyncProgressTracker.update(payload.processed(), payload.total(), payload.status());
    }

    /**
     * 同步完成后恢复区块更新状态。
     * 不再使用全局暂停机制。
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
        reflectionInitialized = false;
        cachedMapProcessor = null;
        cachedMapSaveLoad = null;
        cachedGetLeafMapRegion = null;
        cachedRequestLoad = null;
        cachedLoadStateField = null;
        cachedCancelRefresh = null;
    }

    // ========== 边接收边加载优化方法 ==========

    /**
     * 初始化反射 API 缓存（一次性，避免重复反射开销）。
     * 在首次收到同步数据时调用。
     */
    private static void initializeReflectionCache() {
        if (reflectionInitialized) return;

        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            // 获取 WorldMapSession
            Class<?> worldMapSessionClass = Class.forName("xaero.map.WorldMapSession");
            Object session = worldMapSessionClass.getMethod("getCurrentSession").invoke(null);
            if (session == null) {
                LOGGER.warn("无法初始化反射缓存: WorldMapSession 为空");
                return;
            }

            // 获取 MapProcessor
            Class<?> mapProcessorClass = Class.forName("xaero.map.MapProcessor");
            cachedMapProcessor = worldMapSessionClass.getMethod("getMapProcessor").invoke(session);
            if (cachedMapProcessor == null) {
                LOGGER.warn("无法初始化反射缓存: MapProcessor 为空");
                return;
            }

            // 获取 MapSaveLoad
            Class<?> mapSaveLoadClass = Class.forName("xaero.map.file.MapSaveLoad");
            cachedMapSaveLoad = mapProcessorClass.getMethod("getMapSaveLoad").invoke(cachedMapProcessor);
            if (cachedMapSaveLoad == null) {
                LOGGER.warn("无法初始化反射缓存: MapSaveLoad 为空");
                return;
            }

            // 缓存常用反射方法和字段
            cachedGetLeafMapRegion = mapProcessorClass.getMethod("getLeafMapRegion", int.class, int.class, int.class, boolean.class);
            cachedRequestLoad = mapSaveLoadClass.getMethod("requestLoad", Class.forName("xaero.map.region.MapRegion"), String.class, boolean.class);

            Class<?> mapRegionClass = Class.forName("xaero.map.region.MapRegion");
            cachedLoadStateField = mapRegionClass.getDeclaredField("loadState");
            cachedLoadStateField.setAccessible(true);
            cachedCancelRefresh = mapRegionClass.getMethod("cancelRefresh", mapProcessorClass);

            reflectionInitialized = true;

            // 关键：设置 regionDetectionComplete = true，否则 getLeafMapRegion 会返回 null
            Method setRegionDetectionComplete = mapSaveLoadClass.getMethod("setRegionDetectionComplete", boolean.class);
            setRegionDetectionComplete.invoke(cachedMapSaveLoad, true);

            LOGGER.info("反射 API 缓存已初始化，regionDetectionComplete=true");

        } catch (Exception e) {
            LOGGER.error("初始化反射缓存失败", e);
        }
    }

    /**
     * 立即加载单个区域。
     * 使用缓存的反射 API，设置 shouldCache=true 确保加载后生成缓存。
     *
     * 视距内 region：使用 requestLoad(prioritize=true) 插入队头，优先加载
     * 视距外 region：直接添加到 toLoad 队列队尾，绕过 loadingFiles 检查
     *
     * @param coord 区域坐标
     * @param caveLayer 洞穴层编号，地表层使用 Integer.MAX_VALUE
     * @param inViewDistance 是否在视距内（用于优先级判断）
     */
    private static void triggerSingleRegionLoad(XaeroMapIntegrator.RegionCoord coord, int caveLayer, boolean inViewDistance) {
        if (!reflectionInitialized || cachedMapProcessor == null) {
            LOGGER.warn("反射缓存未初始化，无法加载区域 ({}, {}) layer={}", coord.x(), coord.z(), caveLayer);
            return;
        }

        // 避免重复加载
        if (loadedRegions.contains(coord)) {
            LOGGER.debug("区域 ({}, {}) layer={} 已加载，跳过", coord.x(), coord.z(), caveLayer);
            return;
        }

        try {
            // 获取或创建 MapRegion - 使用正确的 caveLayer
            Object mapRegion = cachedGetLeafMapRegion.invoke(cachedMapProcessor,
                caveLayer, coord.x(), coord.z(), true);
            if (mapRegion == null) {
                LOGGER.warn("无法创建 MapRegion ({}, {}) layer={}", coord.x(), coord.z(), caveLayer);
                return;
            }

            // 调试：检查 region 的属性是否正确
            Class<?> leveledRegionClass = Class.forName("xaero.map.region.LeveledRegion");
            java.lang.reflect.Field worldIdField = leveledRegionClass.getDeclaredField("worldId");
            java.lang.reflect.Field dimIdField = leveledRegionClass.getDeclaredField("dimId");
            java.lang.reflect.Field mwIdField = leveledRegionClass.getDeclaredField("mwId");
            worldIdField.setAccessible(true);
            dimIdField.setAccessible(true);
            mwIdField.setAccessible(true);
            String regionWorldId = (String) worldIdField.get(mapRegion);
            String regionDimId = (String) dimIdField.get(mapRegion);
            String regionMwId = (String) mwIdField.get(mapRegion);
            LOGGER.info("Region ({}, {}) 属性: worldId={}, dimId={}, mwId={}, lastMwDir={}",
                coord.x(), coord.z(), regionWorldId, regionDimId, regionMwId, lastMwDir);

            // 清除 refresh 状态
            cachedCancelRefresh.invoke(mapRegion, cachedMapProcessor);

            // 获取 MapRegion 类（用于 setHasHadTerrain）
            Class<?> mapRegionClass = Class.forName("xaero.map.region.MapRegion");

            // 设置 shouldCache=true，确保完整加载条件满足
            java.lang.reflect.Field shouldCacheField = leveledRegionClass.getDeclaredField("shouldCache");
            shouldCacheField.setAccessible(true);
            shouldCacheField.setBoolean(mapRegion, true);

            // 关键：设置 hasHadTerrain=true，否则 loadCacheTextures 会直接返回元数据
            // 如果 hasHadTerrain=false，加载时会跳过完整数据加载
            java.lang.reflect.Method setHasHadTerrainMethod = mapRegionClass.getMethod("setHasHadTerrain");
            setHasHadTerrainMethod.invoke(mapRegion);

            if (inViewDistance) {
                // 视距内：使用 requestLoad(prioritize=true) 插入队头，优先加载
                cachedLoadStateField.setByte(mapRegion, (byte) 4);
                cachedRequestLoad.invoke(cachedMapSaveLoad, mapRegion, "sync view", true);
                LOGGER.info("区域 ({}, {}) layer={} 视距内，插入队头优先加载", coord.x(), coord.z(), caveLayer);
            } else {
                // 视距外：使用 requestLoad(prioritize=true) 强制添加到队列
                cachedLoadStateField.setByte(mapRegion, (byte) 4);
                cachedRequestLoad.invoke(cachedMapSaveLoad, mapRegion, "sync outside", true);
                LOGGER.info("区域 ({}, {}) layer={} 视距外，添加到加载队列", coord.x(), coord.z(), caveLayer);
            }

            loadedRegions.add(coord);

        } catch (Exception e) {
            LOGGER.warn("立即加载区域 ({}, {}) layer={} 失败: {}", coord.x(), coord.z(), caveLayer, e.getMessage());
        }
    }

    /**
     * 清除单个区域的缓存文件。
     * 在区域立即加载前调用，确保加载最新数据。
     *
     * @param coord 区域坐标
     */
    private static void clearSingleRegionCache(XaeroMapIntegrator.RegionCoord coord) {
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
     *
     * @param chunk 区块数据
     * @return 相对路径字符串
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
