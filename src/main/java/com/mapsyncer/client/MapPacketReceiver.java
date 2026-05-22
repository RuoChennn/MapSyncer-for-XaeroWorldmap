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

    /** 视距外区域累积集合（等待 isComplete 时批量注册） */
    private static volatile Set<XaeroMapIntegrator.RegionCoord> pendingOutsideViewRegions = new HashSet<>();

    /** 已加载的视距内区域集合（避免重复加载） */
    private static volatile Set<XaeroMapIntegrator.RegionCoord> loadedViewRegions = new HashSet<>();

    /** 反射 API 缓存（避免重复反射调用开销） */
    private static volatile Object cachedMapProcessor = null;
    private static volatile Object cachedMapSaveLoad = null;
    private static volatile Object cachedSurfaceMapLayer = null;
    private static volatile Object cachedSession = null;
    private static volatile String cachedCurrentWorldId = null;
    private static volatile String cachedCurrentDimId = null;
    private static volatile String cachedCurrentMWId = null;
    private static volatile Integer cachedGlobalVersion = null;
    private static volatile Method cachedGetLeafMapRegion = null;
    private static volatile Method cachedRequestLoad = null;
    private static volatile java.lang.reflect.Field cachedLoadStateField = null;
    private static volatile Method cachedCancelRefresh = null;
    private static volatile Method cachedAddRegionDetection = null;
    private static volatile java.lang.reflect.Constructor<?> cachedRegionDetectionConstructor = null;

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
     * 实现边接收边加载优化：
     * <ul>
     *   <li>视距内区域：写入后立即加载</li>
     *   <li>视距外区域：写入后累积，等待 isComplete 时批量注册</li>
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

            // 首次收到数据时初始化
            if (!syncInProgress) {
                syncInProgress = true;
                XaeroMapIntegrator.disableChunkUpdates();
                LOGGER.info("Starting sync, chunk updates disabled");

                // 初始化反射 API 缓存（一次性）
                initializeReflectionCache();
            }

            // 获取当前视距范围
            Set<XaeroMapIntegrator.RegionCoord> viewRegions = XaeroMapIntegrator.getViewDistanceRegions();

            // 流式处理：写入后立即判断视距并处理
            for (ChunkMapData chunk : chunks) {
                XaeroMapIntegrator.RegionCoord coord = new XaeroMapIntegrator.RegionCoord(
                    chunk.regionX, chunk.regionZ, chunk.caveLayer);
                updatedRegionCoords.add(coord);

                // 写入文件
                Path mwDir = XaeroMapIntegrator.writeChunkDataAndGetMwDir(chunk, serverWorldId);
                if (mwDir != null) {
                    lastMwDir = mwDir;
                }

                // 判断是否在视距内（只处理地表层）
                boolean isSurfaceLayer = chunk.caveLayer == Integer.MAX_VALUE;
                boolean inViewDistance = viewRegions.contains(coord);

                if (isSurfaceLayer && inViewDistance && !loadedViewRegions.contains(coord)) {
                    // 视距内：立即加载
                    triggerSingleRegionLoad(coord);
                    loadedViewRegions.add(coord);
                    LOGGER.debug("视距内区域 ({}, {}) 已立即加载", coord.x(), coord.z());
                } else if (isSurfaceLayer && !inViewDistance) {
                    // 视距外：累积待注册
                    pendingOutsideViewRegions.add(coord);
                    LOGGER.debug("视距外区域 ({}, {}) 已累积待注册", coord.x(), coord.z());
                }
                // 洞穴层暂不处理（累积到 updatedRegionCoords）

                // 更新时间戳缓存
                if (tsCache != null) {
                    String relativePath = buildRelativePathForCache(chunk);
                    String hash = computeHash(chunk.data);
                    tsCache.update(relativePath, chunk.timestampSeconds, hash);
                }
            }

            // 保存时间戳缓存
            if (tsCache != null && !chunks.isEmpty()) {
                tsCache.save();
            }

            // 同步完成时处理剩余区域
            if (payload.isComplete()) {
                int totalReceived = updatedRegionCoords.size();
                int viewLoaded = loadedViewRegions.size();
                int outsideRegistered = pendingOutsideViewRegions.size();

                LOGGER.info("同步完成: 总计 {} 个区域, 视距内已加载 {} 个, 视距外待注册 {} 个",
                    totalReceived, viewLoaded, outsideRegistered);

                if (!updatedRegionCoords.isEmpty()) {
                    XaeroMapIntegrator.recordUpdatedRegionCoords(updatedRegionCoords);
                    SyncProgressTracker.completeWithCount(totalReceived);

                    // 批量注册视距外区域
                    registerPendingRegionDetections();

                    // 清除缓存文件（视距内已加载，视距外已注册）
                    clearCacheForRegions(loadedViewRegions);

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

                // 清理状态
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
    private static void handleProgressUpdate(PacketHandler.SyncProgressPayload payload, IPayloadContext context) {
        SyncProgressTracker.update(payload.processed(), payload.total(), payload.status());
    }

    /**
     * 触发 Xaero World Map 重新加载所有同步的区域。
     * 使用优化方案：
     * <ul>
     *   <li>为所有区域注册 RegionDetection（替代完整 detectRegions）</li>
     *   <li>视距内 region：立即触发 requestLoad（优先加载）</li>
     *   <li>视距外 region：只注册 RegionDetection，等待玩家靠近时自动发现</li>
     *   <li>立即恢复全局 chunk updates</li>
     * </ul>
     *
     * <p>处理流程：</p>
     * <ol>
     *   <li>为每个同步区域创建 RegionDetection 并添加到 MapLayer</li>
     *   <li>视距内 region：cancelRefresh + loadState=4 + pushWriterPause + requestLoad(优先)</li>
     *   <li>视距外 region：只添加 RegionDetection（已在第1步完成）</li>
     *   <li>立即恢复全局 chunk updates</li>
     *   <li>启动 tick 监听器等待视距内 region 加载完成后解除 region 写保护</li>
     * </ol>
     */
    private static void triggerXaeroReloadAndResume() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            LOGGER.debug("=== 开始 triggerXaeroReloadAndResume (优化版) ===");

            java.util.Set<XaeroMapIntegrator.RegionCoord> regionsToReload = clearXaeroCacheSelective();
            LOGGER.info("需要处理的 region: {} 个", regionsToReload.size());

            if (regionsToReload.isEmpty()) {
                LOGGER.info("无需处理 region，缓存已清除");
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

            // 获取 MapWorld 和当前维度信息
            Object mapWorld = mapProcessorClass.getMethod("getMapWorld").invoke(mapProcessor);
            Class<?> mapWorldClass = Class.forName("xaero.map.world.MapWorld");
            Object mapDimension = mapWorldClass.getMethod("getCurrentDimension").invoke(mapWorld);
            Class<?> mapDimensionClass = Class.forName("xaero.map.world.MapDimension");
            Object layeredRegionManager = mapDimensionClass.getMethod("getLayeredMapRegions").invoke(mapDimension);
            Class<?> layeredRegionManagerClass = Class.forName("xaero.map.region.LayeredRegionManager");

            // 获取当前 worldId, dimId, mwId
            String currentWorldId = (String) mapProcessorClass.getMethod("getCurrentWorldId").invoke(mapProcessor);
            String currentDimId = (String) mapProcessorClass.getMethod("getCurrentDimId").invoke(mapProcessor);
            String currentMWId = (String) mapProcessorClass.getMethod("getCurrentMWId").invoke(mapProcessor);

            // 获取 globalVersion
            Class<?> worldMapClass = Class.forName("xaero.map.WorldMap");
            Object configs = worldMapClass.getMethod("getConfigs").invoke(null);
            Class<?> configsClass = Class.forName("xaero.map.WorldMap$Configs");
            Object clientConfigManager = configsClass.getMethod("getClientConfigManager").invoke(configs);
            Class<?> clientConfigManagerClass = Class.forName("xaero.lib.client.config.ClientConfigManager");
            Object primaryConfigManager = clientConfigManagerClass.getMethod("getPrimaryConfigManager").invoke(clientConfigManager);
            Class<?> singleConfigManagerClass = Class.forName("xaero.lib.common.config.single.SingleConfigManager");

            Class<?> worldMapPrimaryOptionsClass = Class.forName("xaero.map.config.primary.option.WorldMapPrimaryClientConfigOptions");
            java.lang.reflect.Field globalVersionField = worldMapPrimaryOptionsClass.getField("GLOBAL_VERSION");
            Object globalVersionOption = globalVersionField.get(null);
            Method getEffective = singleConfigManagerClass.getMethod("getEffective", Class.forName("xaero.lib.common.config.option.ConfigOption"));
            int globalVersion = (Integer) getEffective.invoke(primaryConfigManager, globalVersionOption);

            // 获取地表层 MapLayer
            Method getLayer = layeredRegionManagerClass.getMethod("getLayer", int.class);
            Object surfaceMapLayer = getLayer.invoke(layeredRegionManager, Integer.MAX_VALUE);
            Class<?> mapLayerClass = Class.forName("xaero.map.region.MapLayer");
            Method addRegionDetection = mapLayerClass.getMethod("addRegionDetection", Class.forName("xaero.map.file.RegionDetection"));

            // RegionDetection 构造函数
            Class<?> regionDetectionClass = Class.forName("xaero.map.file.RegionDetection");
            java.lang.reflect.Constructor<?> regionDetectionConstructor = regionDetectionClass.getConstructor(
                String.class, String.class, String.class, int.class, int.class,
                java.io.File.class, int.class, boolean.class
            );

            // 准备其他反射方法和字段
            Method getLeafMapRegion = mapProcessorClass.getMethod("getLeafMapRegion", int.class, int.class, int.class, boolean.class);
            Method requestLoad = mapSaveLoadClass.getMethod("requestLoad", Class.forName("xaero.map.region.MapRegion"), String.class, boolean.class);
            Class<?> mapRegionClass = Class.forName("xaero.map.region.MapRegion");
            java.lang.reflect.Field loadStateField = mapRegionClass.getDeclaredField("loadState");
            loadStateField.setAccessible(true);
            Method cancelRefresh = mapRegionClass.getMethod("cancelRefresh", mapProcessorClass);

            // 获取视距范围
            java.util.Set<XaeroMapIntegrator.RegionCoord> viewRegions = XaeroMapIntegrator.getViewDistanceRegions();
            LOGGER.debug("视距内 region: {} 个", viewRegions.size());

            java.util.List<Object> pausedViewRegions = new java.util.ArrayList<>();
            int registeredCount = 0;
            int viewDistanceCount = 0;
            int outsideViewCount = 0;

            // 获取 mw 目录路径用于创建 RegionDetection 的 File 对象
            Path mwDir = lastMwDir;
            if (mwDir == null) {
                mwDir = XaeroMapIntegrator.getCurrentMapDirectory();
            }

            // Step 1: 为所有区域添加 RegionDetection（替代 detectRegions）
            for (XaeroMapIntegrator.RegionCoord coord : regionsToReload) {
                if (!coord.isSurfaceLayer()) {
                    // 暂时只处理地表层，洞穴层需要额外处理 MapLayer
                    LOGGER.debug("跳过洞穴层 region ({}, {}) layer={}", coord.x(), coord.z(), coord.caveLayer());
                    continue;
                }

                try {
                    // 创建 RegionDetection
                    String regionFileName = coord.x() + "_" + coord.z() + ".zip";
                    java.io.File regionFile = mwDir != null
                        ? mwDir.resolve(regionFileName).toFile()
                        : new java.io.File(regionFileName);

                    Object detection = regionDetectionConstructor.newInstance(
                        currentWorldId, currentDimId, currentMWId,
                        coord.x(), coord.z(),
                        regionFile, globalVersion, true
                    );

                    // 添加到 MapLayer
                    addRegionDetection.invoke(surfaceMapLayer, detection);
                    registeredCount++;
                    LOGGER.debug("已注册 RegionDetection: ({}, {})", coord.x(), coord.z());
                } catch (Exception e) {
                    LOGGER.warn("注册 RegionDetection 失败: ({}, {}) - {}", coord.x(), coord.z(), e.getMessage());
                }
            }

            LOGGER.info("已注册 {} 个 RegionDetection", registeredCount);

            // Step 2: 视距内区域触发加载
            for (XaeroMapIntegrator.RegionCoord coord : regionsToReload) {
                if (!viewRegions.contains(coord)) continue;
                if (!coord.isSurfaceLayer()) continue;

                Object mapRegion = getLeafMapRegion.invoke(mapProcessor, Integer.MAX_VALUE, coord.x(), coord.z(), true);
                if (mapRegion == null) {
                    LOGGER.warn("视距内 region ({}, {}) 无法创建", coord.x(), coord.z());
                    continue;
                }

                // 先清除可能存在的 refresh 状态，避免状态不一致
                try {
                    cancelRefresh.invoke(mapRegion, mapProcessor);
                    LOGGER.debug("视距内 ({}, {}) 已 cancelRefresh", coord.x(), coord.z());
                } catch (Exception e) {
                    LOGGER.debug("视距内 ({}, {}) cancelRefresh 无需执行: {}", coord.x(), coord.z(), e.getMessage());
                }

                byte currentLoadState = loadStateField.getByte(mapRegion);
                LOGGER.debug("视距内 ({}, {}) loadState={} -> 4", coord.x(), coord.z(), currentLoadState);
                loadStateField.setByte(mapRegion, (byte) 4);

                try {
                    Method pushWriterPause = mapRegionClass.getMethod("pushWriterPause");
                    pushWriterPause.invoke(mapRegion);
                    pausedViewRegions.add(mapRegion);
                    LOGGER.debug("视距内 ({}, {}) 已 pushWriterPause", coord.x(), coord.z());
                } catch (Exception e) {
                    LOGGER.warn("视距内 ({}, {}) pushWriterPause 失败: {}", coord.x(), coord.z(), e.getMessage());
                }

                requestLoad.invoke(mapSaveLoad, mapRegion, "sync priority", true);
                viewDistanceCount++;
            }

            // Step 3: 视距外区域统计（已注册 RegionDetection，等待玩家靠近自动发现）
            for (XaeroMapIntegrator.RegionCoord coord : regionsToReload) {
                if (viewRegions.contains(coord)) continue;
                if (!coord.isSurfaceLayer()) continue;
                outsideViewCount++;
            }

            LOGGER.info("区域处理完成: 视距内 {} 个已触发加载, 视距外 {} 个已注册等待发现",
                viewDistanceCount, outsideViewCount);

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
            cachedSession = worldMapSessionClass.getMethod("getCurrentSession").invoke(null);
            if (cachedSession == null) {
                LOGGER.warn("无法初始化反射缓存: WorldMapSession 为空");
                return;
            }

            // 获取 MapProcessor
            Class<?> mapProcessorClass = Class.forName("xaero.map.MapProcessor");
            cachedMapProcessor = worldMapSessionClass.getMethod("getMapProcessor").invoke(cachedSession);
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

            // 获取 MapWorld 和当前维度
            Object mapWorld = mapProcessorClass.getMethod("getMapWorld").invoke(cachedMapProcessor);
            Class<?> mapWorldClass = Class.forName("xaero.map.world.MapWorld");
            Object mapDimension = mapWorldClass.getMethod("getCurrentDimension").invoke(mapWorld);
            Class<?> mapDimensionClass = Class.forName("xaero.map.world.MapDimension");
            Object layeredRegionManager = mapDimensionClass.getMethod("getLayeredMapRegions").invoke(mapDimension);
            Class<?> layeredRegionManagerClass = Class.forName("xaero.map.region.LayeredRegionManager");

            // 获取当前 worldId, dimId, mwId
            cachedCurrentWorldId = (String) mapProcessorClass.getMethod("getCurrentWorldId").invoke(cachedMapProcessor);
            cachedCurrentDimId = (String) mapProcessorClass.getMethod("getCurrentDimId").invoke(cachedMapProcessor);
            cachedCurrentMWId = (String) mapProcessorClass.getMethod("getCurrentMWId").invoke(cachedMapProcessor);

            // 获取 globalVersion
            Class<?> worldMapClass = Class.forName("xaero.map.WorldMap");
            Object configs = worldMapClass.getMethod("getConfigs").invoke(null);
            Class<?> configsClass = Class.forName("xaero.map.WorldMap$Configs");
            Object clientConfigManager = configsClass.getMethod("getClientConfigManager").invoke(configs);
            Class<?> clientConfigManagerClass = Class.forName("xaero.lib.client.config.ClientConfigManager");
            Object primaryConfigManager = clientConfigManagerClass.getMethod("getPrimaryConfigManager").invoke(clientConfigManager);
            Class<?> singleConfigManagerClass = Class.forName("xaero.lib.common.config.single.SingleConfigManager");

            Class<?> worldMapPrimaryOptionsClass = Class.forName("xaero.map.config.primary.option.WorldMapPrimaryClientConfigOptions");
            java.lang.reflect.Field globalVersionField = worldMapPrimaryOptionsClass.getField("GLOBAL_VERSION");
            Object globalVersionOption = globalVersionField.get(null);
            Method getEffective = singleConfigManagerClass.getMethod("getEffective", Class.forName("xaero.lib.common.config.option.ConfigOption"));
            cachedGlobalVersion = (Integer) getEffective.invoke(primaryConfigManager, globalVersionOption);

            // 获取地表层 MapLayer
            Method getLayer = layeredRegionManagerClass.getMethod("getLayer", int.class);
            cachedSurfaceMapLayer = getLayer.invoke(layeredRegionManager, Integer.MAX_VALUE);

            // 缓存常用反射方法和字段
            cachedGetLeafMapRegion = mapProcessorClass.getMethod("getLeafMapRegion", int.class, int.class, int.class, boolean.class);
            cachedRequestLoad = mapSaveLoadClass.getMethod("requestLoad", Class.forName("xaero.map.region.MapRegion"), String.class, boolean.class);

            Class<?> mapRegionClass = Class.forName("xaero.map.region.MapRegion");
            cachedLoadStateField = mapRegionClass.getDeclaredField("loadState");
            cachedLoadStateField.setAccessible(true);
            cachedCancelRefresh = mapRegionClass.getMethod("cancelRefresh", mapProcessorClass);

            Class<?> mapLayerClass = Class.forName("xaero.map.region.MapLayer");
            cachedAddRegionDetection = mapLayerClass.getMethod("addRegionDetection", Class.forName("xaero.map.file.RegionDetection"));

            // RegionDetection 构造函数
            Class<?> regionDetectionClass = Class.forName("xaero.map.file.RegionDetection");
            cachedRegionDetectionConstructor = regionDetectionClass.getConstructor(
                String.class, String.class, String.class, int.class, int.class,
                java.io.File.class, int.class, boolean.class
            );

            reflectionInitialized = true;
            LOGGER.info("反射 API 缓存已初始化: worldId={}, dimId={}, mwId={}, globalVersion={}",
                cachedCurrentWorldId, cachedCurrentDimId, cachedCurrentMWId, cachedGlobalVersion);

        } catch (Exception e) {
            LOGGER.error("初始化反射缓存失败", e);
        }
    }

    /**
     * 立即加载单个区域（视距内区域边接收边加载）。
     * 使用缓存的反射 API，避免重复查找开销。
     *
     * @param coord 区域坐标
     */
    private static void triggerSingleRegionLoad(XaeroMapIntegrator.RegionCoord coord) {
        if (!reflectionInitialized || cachedMapProcessor == null) {
            LOGGER.warn("反射缓存未初始化，无法加载区域 ({}, {})", coord.x(), coord.z());
            return;
        }

        try {
            // 获取或创建 MapRegion
            Object mapRegion = cachedGetLeafMapRegion.invoke(cachedMapProcessor,
                Integer.MAX_VALUE, coord.x(), coord.z(), true);
            if (mapRegion == null) {
                LOGGER.warn("无法创建 MapRegion ({}, {})", coord.x(), coord.z());
                return;
            }

            // 清除 refresh 状态
            cachedCancelRefresh.invoke(mapRegion, cachedMapProcessor);

            // 设置 loadState = 4（需要重载）
            cachedLoadStateField.setByte(mapRegion, (byte) 4);

            // 触发加载（优先）
            cachedRequestLoad.invoke(cachedMapSaveLoad, mapRegion, "sync streaming", true);

            LOGGER.debug("区域 ({}, {}) 已立即触发加载", coord.x(), coord.z());

        } catch (Exception e) {
            LOGGER.warn("立即加载区域 ({}, {}) 失败: {}", coord.x(), coord.z(), e.getMessage());
        }
    }

    /**
     * 批量注册视距外区域的 RegionDetection。
     * 在同步完成时调用，让这些区域可以被 GuiMap 自动发现。
     */
    private static void registerPendingRegionDetections() {
        if (!reflectionInitialized || cachedSurfaceMapLayer == null) {
            LOGGER.warn("反射缓存未初始化，无法批量注册 RegionDetection");
            return;
        }

        if (pendingOutsideViewRegions.isEmpty()) {
            LOGGER.info("无需注册视距外区域 RegionDetection");
            return;
        }

        Path mwDir = lastMwDir != null ? lastMwDir : XaeroMapIntegrator.getCurrentMapDirectory();
        int registeredCount = 0;

        for (XaeroMapIntegrator.RegionCoord coord : pendingOutsideViewRegions) {
            if (!coord.isSurfaceLayer()) continue;

            try {
                String regionFileName = coord.x() + "_" + coord.z() + ".zip";
                java.io.File regionFile = mwDir != null
                    ? mwDir.resolve(regionFileName).toFile()
                    : new java.io.File(regionFileName);

                Object detection = cachedRegionDetectionConstructor.newInstance(
                    cachedCurrentWorldId, cachedCurrentDimId, cachedCurrentMWId,
                    coord.x(), coord.z(),
                    regionFile, cachedGlobalVersion, true
                );

                cachedAddRegionDetection.invoke(cachedSurfaceMapLayer, detection);
                registeredCount++;

            } catch (Exception e) {
                LOGGER.warn("注册 RegionDetection 失败: ({}, {}) - {}", coord.x(), coord.z(), e.getMessage());
            }
        }

        LOGGER.info("批量注册 {} 个视距外区域 RegionDetection 完成", registeredCount);
    }

    /**
     * 清除指定区域的缓存文件。
     *
     * @param regions 需要清除缓存的区域集合
     */
    private static void clearCacheForRegions(Set<XaeroMapIntegrator.RegionCoord> regions) {
        if (regions.isEmpty() || lastMwDir == null) return;

        List<Path> cacheDirs = findCacheDirectories(lastMwDir);
        int cleared = 0;

        for (XaeroMapIntegrator.RegionCoord region : regions) {
            String cacheFileName = region.x() + "_" + region.z() + ".xwmc";
            for (Path cacheDir : cacheDirs) {
                Path cacheFile = cacheDir.resolve(cacheFileName);
                if (cacheFile.toFile().exists()) {
                    try {
                        java.nio.file.Files.deleteIfExists(cacheFile);
                        cleared++;
                    } catch (Exception e) {
                        LOGGER.debug("删除缓存失败: {}", cacheFile);
                    }
                    break;
                }
            }
        }

        LOGGER.debug("清除 {} 个缓存文件", cleared);
    }

    /**
     * 清理同步状态（非反射缓存）。
     */
    private static void clearSyncState() {
        updatedRegionCoords.clear();
        pendingOutsideViewRegions.clear();
        loadedViewRegions.clear();
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
        cachedSurfaceMapLayer = null;
        cachedSession = null;
        cachedCurrentWorldId = null;
        cachedCurrentDimId = null;
        cachedCurrentMWId = null;
        cachedGlobalVersion = null;
        cachedGetLeafMapRegion = null;
        cachedRequestLoad = null;
        cachedLoadStateField = null;
        cachedCancelRefresh = null;
        cachedAddRegionDetection = null;
        cachedRegionDetectionConstructor = null;
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
     * 计算数据的 CRC32 哈希值。
     *
     * @param data 数据字节数组
     * @return CRC32 哈希值（8位十六进制字符串）
     */
    private static String computeHash(byte[] data) {
        java.util.zip.CRC32 crc32 = new java.util.zip.CRC32();
        crc32.update(data);
        return String.format("%08x", crc32.getValue());
    }
}
