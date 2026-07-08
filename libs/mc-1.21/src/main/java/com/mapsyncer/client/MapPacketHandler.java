package com.mapsyncer.client;

import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.PayloadContext;
import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import com.mapsyncer.client.ClientSyncSession;
import com.mapsyncer.sync.SyncOutcome;
import com.mapsyncer.sync.SyncPhase;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.platform.XaeroReflectionHelper;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.DimensionPathMapping;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户端断开连接时的统一清理入口。
 * 由各平台的断开连接事件处理器调用。
 */
public class MapPacketHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(MapPacketHandler.class);

    private static final ClientSyncSession session = ClientSyncSession.get();

    /**
     * 是否正在接收/写入同步数据（阻塞新 sync 请求）。
     * 视距外重载排空（{@link SyncPhase#DRAINING_RELOAD}）不计入，数据完成后可立即再次同步。
     */
    public static boolean isSyncInProgress() {
        return session.phase() == SyncPhase.RECEIVING
                || ClientSyncWriteQueue.hasPendingWrites()
                || pendingWriteApplyCallbacks.get() > 0;
    }

    /** 是否有视距外 region 重载仍在后台排队/排空 */
    public static boolean isBackgroundReloadPending() {
        return session.phase() == SyncPhase.DRAINING_RELOAD || !pendingRegionLoads.isEmpty();
    }

    /** 服务端是否已安装 MapSyncer（加入服务器时检测） */
    private static volatile boolean serverInstalled = false;

    /** 服务端版本号 */
    private static volatile String serverVersion = "";

    /** 最后写入的 mw 目录，用于缓存清除 */
    private static volatile Path lastMwDir = null;

    /** 同步完成防抖时长（Forge 网络层可能重复递送相同数据包） */
    private static final long SYNC_COMPLETE_DEBOUNCE_MS = 500;

    /** 上次同步完成时间戳，用于防抖 */
    private static volatile long lastSyncCompleteTs = 0;

    /** 进度更新去重：上次已处理的 processed 值 */
    private static volatile int lastProgressProcessed = -1;
    /** 进度更新去重：上次已处理的 total 值 */
    private static volatile int lastProgressTotal = -1;
    /** 进度更新去重：上次更新时间（毫秒） */
    private static volatile long lastProgressTime = 0;
    /** 进度更新去重阈值（相同值在此时间内重复到达则忽略） */
    private static final long PROGRESS_DEDUP_MS = 100;

    /** 同步期间更新的区域坐标集合（仅存储坐标，不存储数据，节省内存） */
    private static final Set<XaeroMapDataHandler.RegionCoord> updatedRegionCoords = ConcurrentHashMap.newKeySet();

    /** 已加载的区域集合（避免重复加载） */
    private static final Set<XaeroMapDataHandler.RegionCoord> loadedRegions = ConcurrentHashMap.newKeySet();

    /** 视距外 region 加载队列 — 限速排放，防止 Xaero MapProcessor 队列溢出 OOM */
    private static final ConcurrentLinkedQueue<PendingRegionLoad> pendingRegionLoads = new ConcurrentLinkedQueue<>();

    private record PendingRegionLoad(int regionX, int regionZ, int caveLayer) {}

    /** 分片超时时间（毫秒），超过此时间未到齐的分片视为丢失 */
    private static final long PART_STALE_TIMEOUT_MS = 2 * 60 * 1000;

    /** 分片重组缓冲区：key = "regionX,regionZ,dimension,caveLayer"，value = 分片数组+首片到达时间 */
    private record PartEntry(ChunkMapData[] parts, long firstArrivedMs) {}
    private static final ConcurrentHashMap<String, PartEntry> partBuffer = new ConcurrentHashMap<>();

    /** 已收到 isComplete 但仍有异步写盘未完成 */
    private static volatile boolean syncFinishRequested = false;
    private static volatile int syncFinishGeneration = -1;
    private static volatile SyncOutcome syncFinishOutcome = SyncOutcome.NONE;
    private static volatile ClientTimestampCache syncFinishTsCache = null;

    /** 写盘 IO 完成但主线程 apply 回调尚未全部执行 */
    private static final AtomicInteger pendingWriteApplyCallbacks = new AtomicInteger(0);

    private static String partKey(ChunkMapData chunk) {
        return chunk.regionX + "," + chunk.regionZ + "," + chunk.dimension + "," + chunk.caveLayer;
    }

    /**
     * 将收到的分片存入缓冲区，全部到达后组装完整 ChunkMapData 返回。
     * 未分片的数据直接返回。
     *
     * @return 组装完成的 ChunkMapData，如果分片尚未到齐则返回 null
     */
    private static ChunkMapData assemblePart(ChunkMapData chunk) {
        if (chunk.totalParts <= 1) {
            return chunk;
        }

        if (chunk.totalParts <= 0 || chunk.partIndex < 0 || chunk.partIndex >= chunk.totalParts) {
            LOGGER.warn("Invalid chunk part metadata: index={} total={}", chunk.partIndex, chunk.totalParts);
            return null;
        }

        String key = partKey(chunk);
        long now = System.currentTimeMillis();
        PartEntry entry = partBuffer.compute(key, (k, existing) -> {
            if (existing == null) {
                ChunkMapData[] arr = new ChunkMapData[chunk.totalParts];
                arr[chunk.partIndex] = chunk;
                return new PartEntry(arr, now);
            }
            existing.parts()[chunk.partIndex] = chunk;
            return existing;
        });
        ChunkMapData[] parts = entry.parts();

        // 检查分片是否已超时
        if (now - entry.firstArrivedMs() > PART_STALE_TIMEOUT_MS) {
            partBuffer.remove(key);
            LOGGER.warn("Chunk part assembly timed out for {} ({}ms), discarding {} received parts",
                key, now - entry.firstArrivedMs(), countNonNull(parts));
            return null;
        }

        for (ChunkMapData p : parts) {
            if (p == null) return null;
        }

        partBuffer.remove(key);

        int totalLen = 0;
        for (ChunkMapData p : parts) {
            totalLen += p.data.length;
        }
        byte[] assembled = new byte[totalLen];
        int offset = 0;
        for (ChunkMapData p : parts) {
            System.arraycopy(p.data, 0, assembled, offset, p.data.length);
            offset += p.data.length;
        }

        ChunkMapData first = parts[0];
        return new ChunkMapData(first.regionX, first.regionZ, first.dimension,
                assembled, first.timestampSeconds, first.caveLayer);
    }

    private static int countNonNull(ChunkMapData[] parts) {
        int n = 0;
        for (ChunkMapData p : parts) {
            if (p != null) n++;
        }
        return n;
    }

    /**
     * 清理所有超时的分片缓冲条目。
     */
    private static void cleanStaleParts() {
        long now = System.currentTimeMillis();
        for (var it = partBuffer.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (now - e.getValue().firstArrivedMs() > PART_STALE_TIMEOUT_MS) {
                it.remove();
                LOGGER.warn("Cleaned stale part buffer for {} ({}ms overdue)",
                    e.getKey(), now - e.getValue().firstArrivedMs());
            }
        }
    }

    /**
     * 检查当前同步是否陈旧（运行时间过长）。
     */
    public static boolean isSyncStale() {
        return session.isStale();
    }

    /**
     * 清除所有累积的同步数据，防止内存泄漏。
     */
    public static void clearSyncData() {
        session.invalidate();
        SyncProgressTracker.cancelTracking();
        clearReceivedChunks();
        loadedRegions.clear();
        partBuffer.clear();
        pendingRegionLoads.clear();
        lastMwDir = null;
        syncFinishRequested = false;
        syncFinishGeneration = -1;
        syncFinishOutcome = SyncOutcome.NONE;
        syncFinishTsCache = null;
        pendingWriteApplyCallbacks.set(0);
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
        ClientLifecycleBridge.onClientDisconnect();
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
                try {
                serverInstalled = true;
                serverVersion = payload.version();
                int intervalMinutes = payload.autoSyncIntervalMinutes();
                AutoSyncManager.configureFromServer(
                        payload.updateMode(), intervalMinutes, payload.incrementalUpdateIntervalTicks());
                LOGGER.info("Server has MapSyncer installed, version: {}, mode={}, intervalMinutes={}, joinAutoSync={}",
                        serverVersion, payload.updateMode(), intervalMinutes, intervalMinutes > 0);

                // 显示自动同步状态
                Object[] statusKey = AutoSyncManager.getStatusKey(intervalMinutes);
                String key = (String) statusKey[0];
                if (statusKey.length > 1) {
                    Minecraft.getInstance().player.displayClientMessage(
                        ChatUtils.prefix().append(ChatUtils.desc(key, statusKey[1])), false);
                } else {
                    Minecraft.getInstance().player.displayClientMessage(
                        ChatUtils.prefix().append(ChatUtils.desc(key)), false);
                }

                boolean shouldJoinSync = AutoSyncManager.shouldAutoSyncOnJoin(
                        payload.lastGenerationTimestamp(), intervalMinutes);
                LOGGER.info("shouldAutoSyncOnJoin result: {} (serverGenTime={}, intervalMinutes={})",
                        shouldJoinSync, payload.lastGenerationTimestamp(), intervalMinutes);
                if (shouldJoinSync) {
                    AutoSyncManager.schedule(() -> {
                        Minecraft.getInstance().execute(() -> {
                            if (Minecraft.getInstance().player != null
                                    && !MapPacketHandler.isSyncInProgress()) {
                                Minecraft.getInstance().player.displayClientMessage(
                                    ChatUtils.prefix().append(ChatUtils.desc("mapsyncer.autosync.start")), false);
                                AutoSyncManager.markStarted();
                                MapSyncerCommandLogic.executeSyncAll(true);
                            }
                        });
                    }, 5);
                }

                AutoSyncManager.startTickPeriodicSync(() ->
                        Minecraft.getInstance().execute(() -> {
                            if (Minecraft.getInstance().player != null
                                    && !MapPacketHandler.isSyncInProgress()) {
                                LOGGER.debug("TICK periodic auto-sync: requesting sync");
                                AutoSyncManager.markPeriodicSync();
                                MapSyncerCommandLogic.executeSyncAll(true);
                            }
                        }));
                } catch (Exception e) {
                    LOGGER.error("Error processing ServerInstalledPayload", e);
                }
            });
        });

        // 注册同步请求处理器（清除陈旧同步数据）
        handler.registerSyncRequestHandler((payload, ctx) -> {
            ctx.enqueueWork(() -> {
                if (isSyncStale()) {
                    clearSyncData();
                    LOGGER.warn("Cleared stale sync data before starting new sync");
                }
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
        AutoSyncManager.resetServerPolicy();
    }

    /**
     * 处理服务端返回的同步响应数据包。
     */
    private static void handleSyncResponse(SyncResponsePayload payload, PayloadContext context) {
        final int generationAtEnqueue = session.generation();
        context.enqueueWork(() -> {
            if (!session.isCurrent(generationAtEnqueue)) {
                LOGGER.debug("Ignoring stale sync response after disconnect/clear");
                return;
            }

            String status = payload.status();
            List<ChunkMapData> chunks = payload.chunks();
            int serverWorldId = payload.worldId();
            SyncOutcome serverOutcome = SyncOutcome.fromServerStatus(status);

            LOGGER.debug("Received sync response: status={}, chunks={}, isComplete={}", status, chunks.size(), payload.isComplete());

            boolean receiving = session.phase() == SyncPhase.RECEIVING;
            // Forge 网络层可能重复递送数据包，完成同步后 500ms 内的新 "ok" 包直接忽略
            if (("ok".equals(status) || "partial".equals(status)) && !payload.isComplete() && !receiving) {
                long elapsed = System.currentTimeMillis() - lastSyncCompleteTs;
                if (elapsed < SYNC_COMPLETE_DEBOUNCE_MS) {
                    LOGGER.debug("Debouncing duplicate sync packet ({}ms after complete)", elapsed);
                    return;
                }
            }
            // 完成包去重：500ms 内的重复完成包忽略
            if (payload.isComplete() && session.phase() == SyncPhase.IDLE) {
                long elapsed = System.currentTimeMillis() - lastSyncCompleteTs;
                if (elapsed < SYNC_COMPLETE_DEBOUNCE_MS) {
                    LOGGER.debug("Debouncing duplicate completion packet ({}ms after complete)", elapsed);
                    return;
                }
            }

            // 获取时间戳缓存用于同步状态管理
            Path serverDir = XaeroMapIntegrator.getCurrentServerDirectory();
            ClientTimestampCache tsCache = serverDir != null && serverDir.toFile().exists()
                    ? ClientTimestampCache.getInstance(serverDir) : null;

            // 收到服务端任何响应即确认服务端已安装 MapSyncer
            if (!serverInstalled) {
                serverInstalled = true;
                LOGGER.info("Server confirmed (SyncResponse received), MapSyncer detected");
            }
            SyncProgressTracker.onServerResponded();

            // Hard fail — 中止同步
            if (serverOutcome == SyncOutcome.HARD_FAIL) {
                LOGGER.info("Server returned error status: {}, aborting sync", status);
                session.setOutcome(SyncOutcome.HARD_FAIL);
                clearSyncData();
                clearReflectionCache();
                SyncProgressTracker.cancelTracking();
                if (tsCache != null) {
                    tsCache.clearSyncState();
                }
                return;
            }

            // Silent skip — 地图已是最新
            if (serverOutcome == SyncOutcome.SILENT_SKIP) {
                LOGGER.info("Map is up-to-date, no sync needed");
                session.setOutcome(SyncOutcome.SILENT_SKIP);
                clearSyncData();
                clearReflectionCache();
                SyncProgressTracker.finishUptodate();
                finishJoinAutoSyncIfActive();
                if (tsCache != null) {
                    tsCache.markSyncComplete();
                }
                return;
            }

            // ok / partial — 有数据需要同步
            if (isSyncStale()) {
                session.setOutcome(SyncOutcome.HARD_FAIL);
                clearSyncData();
                clearReflectionCache();
                LOGGER.warn("Sync was stale, cleared accumulated data");
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.displayClientMessage(ChatUtils.error("mapsyncer.sync.timeout"), false);
                }
                return;
            }

            // 首次收到数据时初始化反射缓存（含 DRAINING 阶段收到新 sync 的情况）
            if (session.phase() == SyncPhase.IDLE || session.phase() == SyncPhase.DRAINING_RELOAD) {
                session.beginReceiving();
                RegionPipelineTracker.beginSession();
                LOGGER.info("Starting sync (streaming mode)");
                if (!initializeReflectionCache()) {
                    session.markReflectionFailed();
                    if (Minecraft.getInstance().player != null) {
                        Minecraft.getInstance().player.displayClientMessage(
                                ChatUtils.error("mapsyncer.sync.reflection_failed"), false);
                    }
                }
            }

            // 流式处理：异步写盘，主线程仅做 Xaero 反射重载
            Minecraft mc = Minecraft.getInstance();
            cleanStaleParts();
            AtomicInteger batchPending = new AtomicInteger();
            AtomicInteger submittedCount = new AtomicInteger();

            for (ChunkMapData chunk : chunks) {
                ChunkMapData assembled = assemblePart(chunk);
                if (assembled == null) {
                    continue;
                }

                if (serverDir == null) {
                    LOGGER.error("无法获取服务器目录，跳过 region ({}, {})",
                            assembled.regionX, assembled.regionZ);
                    continue;
                }

                XaeroMapDataHandler.RegionCoord coord = new XaeroMapDataHandler.RegionCoord(
                    assembled.regionX, assembled.regionZ, assembled.caveLayer);
                updatedRegionCoords.add(coord);

                boolean syncingCaveDimension = DimensionPathMapping.getInstance().isNether(assembled.dimension);
                boolean shouldProcess = syncingCaveDimension
                    ? !assembled.isSurfaceLayer()
                    : assembled.isSurfaceLayer();

                Set<XaeroMapDataHandler.RegionCoord> viewRegionsForLayer =
                    XaeroMapIntegrator.getViewDistanceRegions(assembled.caveLayer);
                boolean inViewDistance = viewRegionsForLayer.contains(coord);

                submittedCount.incrementAndGet();
                batchPending.incrementAndGet();
                final int gen = generationAtEnqueue;
                final ClientTimestampCache batchTsCache = tsCache;

                RegionPipelineTracker.onPacketReceived(
                        assembled.regionX, assembled.regionZ, assembled.caveLayer, assembled.data.length);
                RegionPipelineTracker.onWriteSubmitted(
                        assembled.regionX, assembled.regionZ, assembled.caveLayer);

                pendingWriteApplyCallbacks.incrementAndGet();
                ClientSyncWriteQueue.submit(assembled, serverDir, serverWorldId, tsCache, writeResult -> {
                    mc.execute(() -> {
                        try {
                            if (!session.isCurrent(gen)) {
                                return;
                            }

                            if (writeResult == null) {
                                LOGGER.error("Region ({}, {}) 写入失败，跳过加载（{} bytes）",
                                        assembled.regionX, assembled.regionZ, assembled.data.length);
                                RegionPipelineTracker.onWriteComplete(
                                        assembled.regionX, assembled.regionZ, assembled.caveLayer, false);
                                if (batchTsCache != null) {
                                    batchTsCache.remove(
                                            XaeroMapDataHandler.buildRelativePathForCache(assembled));
                                }
                            } else {
                                lastMwDir = writeResult.mwDir();

                                if (shouldProcess && !session.reflectionFailed()) {
                                    if (inViewDistance) {
                                        triggerSingleRegionLoad(coord, assembled.caveLayer, true);
                                    } else {
                                        RegionPipelineTracker.onDeferredLoadQueued(
                                                coord.x(), coord.z(), assembled.caveLayer);
                                        pendingRegionLoads.add(new PendingRegionLoad(
                                                coord.x(), coord.z(), assembled.caveLayer));
                                    }
                                    LOGGER.debug("区域 ({}, {}) layer={} inView={} 已写入并触发加载",
                                            coord.x(), coord.z(), assembled.caveLayer, inViewDistance);
                                } else if (shouldProcess) {
                                    RegionPipelineTracker.onWriteOnlyComplete(
                                            assembled.regionX, assembled.regionZ, assembled.caveLayer);
                                    LOGGER.debug("区域 ({}, {}) 已写入磁盘，反射不可用跳过运行时重载",
                                            coord.x(), coord.z());
                                } else {
                                    RegionPipelineTracker.onWriteOnlyComplete(
                                            assembled.regionX, assembled.regionZ, assembled.caveLayer);
                                }
                                RegionPipelineTracker.onWriteComplete(
                                        assembled.regionX, assembled.regionZ, assembled.caveLayer, true);
                            }

                            if (batchPending.decrementAndGet() == 0 && batchTsCache != null
                                    && submittedCount.get() > 0) {
                                ClientSyncWriteQueue.saveTimestampCacheAsync(batchTsCache);
                            }
                        } finally {
                            pendingWriteApplyCallbacks.decrementAndGet();
                            tryCompleteSync(gen);
                        }
                    });
                });
            }

            if (payload.isComplete()) {
                requestSyncFinish(generationAtEnqueue, serverOutcome, tsCache);
            }
            if (submittedCount.get() == 0) {
                tryCompleteSync(generationAtEnqueue);
            }
        });
    }

    private static void requestSyncFinish(int generation, SyncOutcome serverOutcome, ClientTimestampCache tsCache) {
        syncFinishRequested = true;
        syncFinishGeneration = generation;
        syncFinishOutcome = serverOutcome;
        syncFinishTsCache = tsCache;
        tryCompleteSync(generation);
    }

    private static void tryCompleteSync(int generation) {
        if (!syncFinishRequested || ClientSyncWriteQueue.hasPendingWrites()
                || pendingWriteApplyCallbacks.get() > 0) {
            return;
        }
        if (!session.isCurrent(generation)) {
            return;
        }

        syncFinishRequested = false;
        SyncOutcome serverOutcome = syncFinishOutcome;
        ClientTimestampCache tsCache = syncFinishTsCache;

        int totalReceived = updatedRegionCoords.size();
        LOGGER.info("同步完成: 总计 {} 个区域已处理", totalReceived);

        lastSyncCompleteTs = System.currentTimeMillis();

        SyncOutcome finalOutcome = serverOutcome == SyncOutcome.PARTIAL_SUCCESS || session.reflectionFailed()
                ? SyncOutcome.PARTIAL_SUCCESS
                : SyncOutcome.SUCCESS;
        session.setOutcome(finalOutcome);

        if (!updatedRegionCoords.isEmpty()) {
            XaeroMapDataHandler.recordUpdatedRegionCoords(updatedRegionCoords);
            SyncProgressTracker.completeWithCount(totalReceived);

            if (AutoSyncManager.isActive()) {
                AutoSyncManager.markComplete();
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.displayClientMessage(
                            ChatUtils.success("mapsyncer.autosync.complete"),
                            false);
                }
            }

            if (tsCache != null) {
                tsCache.markSyncComplete();
            }
            notifySyncOutcome(finalOutcome);
        } else {
            LOGGER.info("Sync complete with no data received");
            SyncProgressTracker.finishUptodate();
            finishJoinAutoSyncIfActive();
            if (tsCache != null) {
                tsCache.markSyncComplete();
            }
        }

        RegionPipelineTracker.markSyncPipelineComplete();
        clearSyncStateAfterComplete();
        scheduleDeferredReloadCleanup();
    }

    private static void notifySyncOutcome(SyncOutcome outcome) {
        if (Minecraft.getInstance().player == null) {
            return;
        }
        if (outcome == SyncOutcome.PARTIAL_SUCCESS) {
            if (session.reflectionFailed()) {
                Minecraft.getInstance().player.displayClientMessage(
                        ChatUtils.error("mapsyncer.sync.reflection_failed"), false);
            } else {
                Minecraft.getInstance().player.displayClientMessage(
                        ChatUtils.error("mapsyncer.sync.partial"), false);
            }
        }
    }

    /**
     * 处理服务端发送的进度更新数据包。
     */
    private static void handleProgressUpdate(SyncProgressPayload payload, PayloadContext context) {
        context.enqueueWork(() -> {
            String status = payload.status();
            if (status != null && status.startsWith("aborted")) {
                SyncProgressTracker.cancelTracking();
                MapPacketHandler.clearSyncData();
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    if (status.contains("timeout")) {
                        mc.player.displayClientMessage(
                                ChatUtils.error("mapsyncer.sync.server_timeout"), false);
                    } else {
                        mc.player.displayClientMessage(
                                ChatUtils.error("mapsyncer.sync.cancelled"), false);
                    }
                }
                return;
            }

            // 自动同步时静默，不显示进度
            if (AutoSyncManager.isActive()) return;

            // 进度去重：相同 (processed, total) 在 100ms 内到达视为重复
            int processed = payload.processed();
            int total = payload.total();
            long now = System.currentTimeMillis();
            if (processed == lastProgressProcessed && total == lastProgressTotal
                    && now - lastProgressTime < PROGRESS_DEDUP_MS) {
                return;
            }
            lastProgressProcessed = processed;
            lastProgressTotal = total;
            lastProgressTime = now;
            SyncProgressTracker.update(processed, total, payload.status());
        });
    }

    /**
     * 视距外重载队列排空后将 DRAINING_RELOAD 会话置为 IDLE。
     */
    private static void resumeChunkUpdatesIfIdle() {
        if (!pendingRegionLoads.isEmpty()) {
            return;
        }
        if (session.phase() == SyncPhase.DRAINING_RELOAD) {
            session.completeSession();
            LOGGER.info("Deferred reload queue drained, sync session idle");
        }
    }

    /**
     * 同步完成后的轻量清理（保留视距外重载队列，供后续 tick 继续排放）。
     */
    private static void clearSyncStateAfterComplete() {
        updatedRegionCoords.clear();
        loadedRegions.clear();
        partBuffer.clear();
        lastMwDir = null;
    }

    /**
     * 同步完成后延迟释放反射缓存：等 pendingRegionLoads 排空后再 clear。
     *
     * <p>最后一包通常 isComplete=true 且含最远 region，若在入队后立即 clearSyncState，
     * 会丢掉刚写入队列的视距外 region。</p>
     */
    private static void scheduleDeferredReloadCleanup() {
        int intervalTicks;
        try {
            intervalTicks = PlatformManager.getPlatform().getMapRegionLoadIntervalTicks();
        } catch (IllegalStateException e) {
            intervalTicks = 1;
        }
        if (RegionLoadThrottle.isViewOnly(intervalTicks) || pendingRegionLoads.isEmpty()) {
            pendingRegionLoads.clear();
            clearReflectionCache();
            resumeChunkUpdatesIfIdle();
            return;
        }
        session.beginDrainingReload();
        drainPendingLoadQueue();
        finishDeferredReloadCleanupIfDone();
    }

    private static void finishDeferredReloadCleanupIfDone() {
        if (session.phase() != SyncPhase.DRAINING_RELOAD || !pendingRegionLoads.isEmpty()) {
            return;
        }
        clearReflectionCache();
        resumeChunkUpdatesIfIdle();
        LOGGER.debug("视距外 region 重载队列已排空，反射缓存已释放");
    }

    /**
     * 清理同步缓冲（非反射缓存、非会话 generation）。
     */
    private static void clearSyncState() {
        updatedRegionCoords.clear();
        loadedRegions.clear();
        partBuffer.clear();
        pendingRegionLoads.clear();
        lastMwDir = null;
        RegionLoadThrottle.reset();
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
     *
     * @return true 表示反射可用
     */
    private static boolean initializeReflectionCache() {
        if (XaeroReflectionHelper.isInitialized()) {
            LOGGER.debug("反射缓存已初始化，跳过重复初始化");
            return true;
        }

        LOGGER.info("开始初始化反射 API 缓存...");
        boolean initSuccess = XaeroReflectionHelper.initialize();

        if (initSuccess) {
            LOGGER.info("XaeroReflectionHelper 初始化成功");
            boolean regionDetectSuccess = XaeroReflectionHelper.setRegionDetectionComplete(true);
            if (regionDetectSuccess) {
                LOGGER.info("regionDetectionComplete 设置为 true，反射功能就绪");
            } else {
                LOGGER.warn("regionDetectionComplete 设置失败，getLeafMapRegion 可能会返回 null");
            }
            return true;
        }

        LOGGER.error("XaeroReflectionHelper 初始化失败！反射功能完全不可用");
        LOGGER.error("可能原因：");
        LOGGER.error("  1. Xaero's World Map 模组未安装");
        LOGGER.error("  2. Xaero 版本与 MapSyncer 不兼容");
        LOGGER.error("  3. 类加载器问题");
        LOGGER.error("地图同步功能将无法正常工作，数据会写入文件但不会触发重新加载");
        return false;
    }

    /**
     * 立即加载单个区域。
     */
    private static void triggerSingleRegionLoad(XaeroMapDataHandler.RegionCoord coord, int caveLayer, boolean inViewDistance) {
        RegionPipelineTracker.onReflectionLoadStart(coord.x(), coord.z(), caveLayer);
        boolean success = false;
        try {
            if (!XaeroReflectionHelper.isInitialized()) {
                LOGGER.warn("反射缓存未初始化，无法加载区域 ({}, {}) layer={}", coord.x(), coord.z(), caveLayer);
                return;
            }

            if (loadedRegions.contains(coord)) {
                LOGGER.debug("区域 ({}, {}) layer={} 已加载，跳过", coord.x(), coord.z(), caveLayer);
                success = true;
                return;
            }

            Object mapRegion = XaeroReflectionHelper.getLeafMapRegion(caveLayer, coord.x(), coord.z(), true);
            if (mapRegion == null) {
                LOGGER.warn("无法创建 MapRegion ({}, {}) layer={}", coord.x(), coord.z(), caveLayer);
                return;
            }

            String regionWorldId = XaeroReflectionHelper.getWorldId(mapRegion);
            String regionDimId = XaeroReflectionHelper.getDimId(mapRegion);
            String regionMwId = XaeroReflectionHelper.getMwId(mapRegion);
            LOGGER.info("Region ({}, {}) 属性: worldId={}, dimId={}, mwId={}, lastMwDir={}",
                coord.x(), coord.z(), regionWorldId, regionDimId, regionMwId, lastMwDir);

            if (!XaeroReflectionHelper.prepareRegionLoad(mapRegion)) {
                LOGGER.warn("区域 ({}, {}) layer={} 准备加载失败，跳过此区域", coord.x(), coord.z(), caveLayer);
                return;
            }

            if (!XaeroReflectionHelper.setLoadState(mapRegion, XaeroReflectionHelper.LOAD_STATE_CLEARED)) {
                LOGGER.warn("区域 ({}, {}) layer={} 设置 loadState 失败，跳过此区域", coord.x(), coord.z(), caveLayer);
                return;
            }

            String reason = inViewDistance ? "sync view" : "sync outside";
            if (!XaeroReflectionHelper.requestLoad(mapRegion, reason, true)) {
                LOGGER.warn("区域 ({}, {}) layer={} 请求加载失败", coord.x(), coord.z(), caveLayer);
                return;
            }

            if (inViewDistance) {
                LOGGER.debug("区域 ({}, {}) layer={} 视距内，插入队头优先加载", coord.x(), coord.z(), caveLayer);
            } else {
                LOGGER.debug("区域 ({}, {}) layer={} 视距外，添加到加载队列", coord.x(), coord.z(), caveLayer);
            }

            loadedRegions.add(coord);
            success = true;
        } catch (Exception e) {
            LOGGER.error("立即加载区域 ({}, {}) layer={} 失败: {}", coord.x(), coord.z(), caveLayer, e.getMessage(), e);
        } finally {
            RegionPipelineTracker.onReflectionLoadDone(coord.x(), coord.z(), caveLayer, success);
        }
    }

    /**
     * 按配置的 tick 间隔向 Xaero MapProcessor 传入视距外 region（每 N tick 1 个）。
     * 由 ClientTick 事件每 tick 调用，防止一次性涌入过多 region 导致 OOM。
     */
    public static void drainPendingLoadQueue() {
        SyncProgressTracker.onClientTick();
        RegionPipelineTracker.onClientTick();
        int intervalTicks;
        try {
            intervalTicks = PlatformManager.getPlatform().getMapRegionLoadIntervalTicks();
        } catch (IllegalStateException e) {
            return;
        }
        if (RegionLoadThrottle.isViewOnly(intervalTicks)) {
            return;
        }
        if (pendingRegionLoads.isEmpty()) {
            return;
        }

        if (RegionLoadThrottle.isUnlimited(intervalTicks)) {
            PendingRegionLoad pending;
            while ((pending = pendingRegionLoads.poll()) != null) {
                XaeroMapDataHandler.RegionCoord coord = new XaeroMapDataHandler.RegionCoord(
                    pending.regionX(), pending.regionZ(), pending.caveLayer());
                triggerSingleRegionLoad(coord, pending.caveLayer(), false);
            }
            RegionLoadThrottle.reset();
            finishDeferredReloadCleanupIfDone();
            return;
        }

        if (!RegionLoadThrottle.shouldDrainOne(intervalTicks)) {
            return;
        }

        PendingRegionLoad pending = pendingRegionLoads.poll();
        if (pending != null) {
            XaeroMapDataHandler.RegionCoord coord = new XaeroMapDataHandler.RegionCoord(
                pending.regionX(), pending.regionZ(), pending.caveLayer());
            triggerSingleRegionLoad(coord, pending.caveLayer(), false);
        }
        finishDeferredReloadCleanupIfDone();
    }

    /**
     * 检查是否有待加载的视距外 region。
     * 供平台层决定是否注册 ClientTick 事件。
     */
    public static boolean hasPendingLoads() {
        return !pendingRegionLoads.isEmpty();
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
                mc.level.dimension().location().toString());

        if (targetDimension.equals(currentXaeroDim)) {
            LOGGER.info("Syncing current dimension {}, unloading view distance regions", targetDimension);
            int unloaded = XaeroMapIntegrator.unloadViewDistanceRegions();
            if (unloaded > 0 && mc.player != null) {
                mc.player.displayClientMessage(
                        ChatUtils.desc("mapsyncer.sync.unloading_regions", unloaded), false);
            }
        }
    }

    /** 进服自动同步结束（含地图已是最新）：显示唯一完成提示 */
    private static void finishJoinAutoSyncIfActive() {
        if (!AutoSyncManager.isActive()) {
            return;
        }
        AutoSyncManager.markComplete();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    ChatUtils.success("mapsyncer.autosync.complete"), false);
        }
    }
}
