package com.mapsyncer.server;

import com.mapsyncer.network.NetworkManager;
import com.mapsyncer.network.PayloadContext;
import com.mapsyncer.network.payload.ChunkMapData;
import com.mapsyncer.network.payload.ClientMeta;
import com.mapsyncer.network.payload.SyncProgressPayload;
import com.mapsyncer.network.payload.SyncRequestPayload;
import com.mapsyncer.network.payload.SyncResponsePayload;
import com.mapsyncer.platform.PlatformManager;
import com.mapsyncer.util.PropertiesCacheIO.TimestampHashEntry;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.DimensionApiHelper;
import com.mapsyncer.util.DimensionPathMapping;
import com.mapsyncer.util.HashUtils;
import com.mapsyncer.util.PlayerLevelApiHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 服务端同步处理器 - 处理客户端请求的地图数据同步
 *
 * 功能：
 * - 接收客户端同步请求，包含客户端缓存的元数据（时间戳+哈希）
 * - 比对服务端缓存与客户端元数据，确定需要同步的区域
 * - 分批发送差异区域数据到客户端
 * - 支持速度限制，避免网络拥塞
 *
 * 同步逻辑（基于哈希比对，自动断点续传）：
 * 1. 哈希值一致 → 不同步（文件内容相同）
 * 2. 哈希值不一致 + 客户端时间戳旧于服务端 → 同步
 * 3. 哈希值不一致 + 客户端时间戳新于服务端 → 不同步（客户端有新数据）
 * 4. 客户端无该区域的元数据 → 同步（新区域）
 *
 * 断点续传机制：
 * - 完全依赖哈希比对，客户端时间戳缓存（sync_timestamps.cache）记录已接收区域
 * - 断线重连后，客户端发送已接收区域的哈希，服务端比对后只同步差异
 * - 无需服务端保留进度索引，简化实现并避免内存泄漏
 *
 * 注意：此类包含所有平台共享的业务逻辑，平台特定的注册由各平台薄包装器处理。
 */
public class ServerSyncHandlerLogic {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerSyncHandlerLogic.class);

    /** 最大数据包大小上限（1MB），避免超过网络限制 */
    private static final int MAX_PACKET_SIZE_LIMIT = 1_000_000;

    /**
     * 获取最大单包大小（用于拆包）。
     * 这是每个网络包的大小限制。
     *
     * @return 最大单包大小（字节）
     */
    private static int getMaxPacketSize() {
        int configValue = PlatformManager.getPlatform().getMaxSyncPacketSize();
        return Math.min(configValue, MAX_PACKET_SIZE_LIMIT);
    }

    /**
     * 获取批次累积阈值（目标每秒发送量）。
     * 当有限速时，将限速值向下取整到整包大小，确保每秒发送整数个完整包。
     * 无限速时，阈值 = 最大包大小。
     *
     * @return 批次累积阈值（字节）
     */
    private static int getBatchThreshold() {
        int limitKBps = PlatformManager.getPlatform().getSyncSpeedLimitKBps();
        if (limitKBps <= 0) {
            // 无限速：使用最大包大小
            return getMaxPacketSize();
        }

        // 有限速：向下取整到整包大小
        int maxPacketSize = getMaxPacketSize();
        int limitBytesPerSec = limitKBps * 1024;

        // 计算每秒可发送的完整包数（向下取整）
        int packetsPerSecond = limitBytesPerSec / maxPacketSize;

        // 至少允许发送一个包，否则无法发送任何数据
        if (packetsPerSecond < 1) {
            packetsPerSecond = 1;
        }

        // 实际限速 = 整包数 × 包大小
        int actualThreshold = packetsPerSecond * maxPacketSize;

        LOGGER.debug("Speed limit adjusted: {} KB/s → {} packets/s × {} KB = {} KB/s",
                limitKBps, packetsPerSecond, maxPacketSize / 1024, actualThreshold / 1024);

        return actualThreshold;
    }

    /**
     * 将批次数据按包大小限制拆分发送。
     * 当批次数据超过单包大小限制时，拆成多个包发送。
     *
     * @param batch 待发送的数据列表
     * @param batchBytes 批次总字节数
     * @param serverPlayer 玩家实例
     * @param worldId 世界ID
     * @param processed 已处理数量
     * @param total 总数量
     * @return 发送的包数量
     */
    private static int sendBatchInChunks(List<ChunkMapData> batch, int batchBytes,
            MinecraftServer server, int worldId, int processed, int total, UUID playerId, int syncVersion) {
        int maxPacketSize = getMaxPacketSize();

        if (batchBytes <= maxPacketSize) {
            final List<ChunkMapData> batchToSend = new ArrayList<>(batch);
            enqueueIfCurrent(server, playerId, syncVersion, player -> {
                NetworkManager.sendToPlayer(player,
                        new SyncResponsePayload(batchToSend, false, worldId, "ok"));
                NetworkManager.sendToPlayer(player,
                        new SyncProgressPayload(processed, total,
                                String.format("Sending regions %d/%d", processed, total)));
            });
            return 1;
        }

        List<ChunkMapData> currentChunk = new ArrayList<>();
        int currentSize = 0;
        int packetCount = 0;

        for (ChunkMapData chunk : batch) {
            if (currentSize + chunk.data.length > maxPacketSize && !currentChunk.isEmpty()) {
                final List<ChunkMapData> chunkToSend = new ArrayList<>(currentChunk);
                final int sentProgress = processed + packetCount;
                enqueueIfCurrent(server, playerId, syncVersion, player -> {
                    NetworkManager.sendToPlayer(player,
                            new SyncResponsePayload(chunkToSend, false, worldId, "ok"));
                    NetworkManager.sendToPlayer(player,
                            new SyncProgressPayload(sentProgress, total,
                                    String.format("Sending regions %d/%d", sentProgress, total)));
                });
                packetCount++;

                currentChunk.clear();
                currentSize = 0;
            }

            currentChunk.add(chunk);
            currentSize += chunk.data.length;
        }

        if (!currentChunk.isEmpty()) {
            final List<ChunkMapData> chunkToSend = new ArrayList<>(currentChunk);
            final int sentProgress = processed + packetCount;
            enqueueIfCurrent(server, playerId, syncVersion, player -> {
                NetworkManager.sendToPlayer(player,
                        new SyncResponsePayload(chunkToSend, false, worldId, "ok"));
                NetworkManager.sendToPlayer(player,
                        new SyncProgressPayload(sentProgress, total,
                                String.format("Sending regions %d/%d", sentProgress, total)));
            });
            packetCount++;
        }

        return packetCount;
    }

    /** 正在同步的玩家集合（用于断线或维度切换时中断同步） */
    private static final Set<UUID> syncingPlayers = ConcurrentHashMap.newKeySet();

    /** 玩家同步开始时的维度（用于维度切换时中断同步） */
    private static final Map<UUID, ResourceKey<Level>> playerSyncDimensions = new ConcurrentHashMap<>();

    /** 玩家同步线程引用（用于断线时立即中断线程） */
    private static final Map<UUID, Thread> syncThreads = new ConcurrentHashMap<>();

    /** 限速统计：累计发送字节数 */
    private static final Map<UUID, Long> speedLimitBytesSent = new ConcurrentHashMap<>();

    /** 限速统计：周期开始时间 */
    private static final Map<UUID, Long> speedLimitCycleStart = new ConcurrentHashMap<>();

    /** 限速周期最大时长（1秒），防止周期过长导致累计量过大 */
    private static final long MAX_SPEED_LIMIT_CYCLE_MS = 1000;

    /** 全局递增版本号，用于标记每次同步请求 */
    private static final AtomicInteger globalSyncVersion = new AtomicInteger(0);

    /** 每个玩家当前的同步版本号由 {@link ServerSyncSession} 管理 */

    /** SyncRequestPayload 分片组装缓冲区：playerId → { partIndex → payload } */
    private static final ConcurrentHashMap<UUID, Map<Integer, SyncRequestPayload>> requestPartBuffer = new ConcurrentHashMap<>();
    /** 记录每个玩家当前组装请求的总分片数（用于判断是否到齐） */
    private static final Map<UUID, Integer> requestTotalParts = new ConcurrentHashMap<>();

    /** 分包最后活动时间：playerId → 最后一次收到分包的时刻（用于穿透丢包兜底） */
    private static final Map<UUID, Long> requestPartLastActivity = new ConcurrentHashMap<>();

    /** 分包组装超时（毫秒）：超过此时长未到齐，通知客户端重发整个请求 */
    private static final long PART_ASSEMBLY_TIMEOUT_MS = 20_000;

    /** 定时扫描间隔（毫秒） */
    private static final long PART_ASSEMBLY_CHECK_INTERVAL_MS = 5_000;

    /** 服务器引用（定时线程向玩家发通知用），由 handleSyncRequest 更新 */
    private static volatile MinecraftServer currentServer = null;

    /** 分包超时检查定时器（懒启动） */
    private static volatile ScheduledExecutorService partAssemblyTimer = null;

    /**
     * 轻量级的 region 同步信息。
     * 只存储路径和元数据，不包含实际数据，节省内存。
     * 用于流式处理：先收集路径，排序后逐个读取发送。
     *
     * @param zipPath zip文件路径
     * @param normalizedPath 规范化的相对路径
     * @param timestampSeconds 时间戳（秒）
     */
    private record RegionSyncInfo(Path zipPath, String normalizedPath, long timestampSeconds,
                                   int regionX, int regionZ, String dimension, int caveLayer) {
        /**
         * 判断是否为地表层。
         */
        boolean isSurfaceLayer() {
            return caveLayer == Integer.MAX_VALUE;
        }
    }

    /**
     * 注册网络数据包处理器。
     * 由各平台薄包装器在适当的时机调用。
     */
    public static void registerHandlers() {
        NetworkManager.getHandler().registerSyncRequestHandler(
            (payload, context) -> context.enqueueWork(() -> handleSyncRequest(payload, context))
        );
    }

    /**
     * 玩家断线事件处理
     *
     * 哈希比对机制会自动处理断点续传：
     * - 客户端重连后发送已接收区域的哈希（从 sync_timestamps.cache 读取）
     * - 服务端比对后只同步差异区域
     *
     * @param playerId 玩家UUID
     */
    public static void onPlayerDisconnect(UUID playerId) {
        syncingPlayers.remove(playerId);
        playerSyncDimensions.remove(playerId);

        // 清理限速状态
        clearSpeedLimitState(playerId);

        // 清理分片组装缓冲区，防止内存泄漏
        requestPartBuffer.remove(playerId);
        requestTotalParts.remove(playerId);
        ServerSyncSession.finalizeSession(playerId);

        // 立即中断同步线程
        Thread syncThread = syncThreads.remove(playerId);
        if (syncThread != null && syncThread.isAlive()) {
            syncThread.interrupt();
            LOGGER.info("Player {} disconnected, sync thread interrupted", playerId);
        }
    }

    private static boolean isSyncStillActive(UUID playerId, int syncVersion) {
        if (syncThreads.get(playerId) != Thread.currentThread()) {
            return false;
        }
        if (!ServerSyncSession.isCurrent(playerId, syncVersion)) {
            return false;
        }
        return syncingPlayers.contains(playerId);
    }

    private static final long PLAYER_VALIDATION_TIMEOUT_SEC = 60;
    private static final int PLAYER_VALIDATION_MAX_ATTEMPTS = 3;

    private enum PlayerCheckResult {
        VALID, INVALID, TIMEOUT
    }

    /**
     * 在主线程检查玩家是否仍在线且未切换维度。
     */
    private static PlayerCheckResult checkPlayerOnMainThread(MinecraftServer server, UUID playerId,
            ResourceKey<Level> startDimension, int syncVersion) {
        for (int attempt = 1; attempt <= PLAYER_VALIDATION_MAX_ATTEMPTS; attempt++) {
            try {
                boolean valid = server.submit(() -> {
                    if (!ServerSyncSession.isCurrent(playerId, syncVersion)) {
                        return false;
                    }
                    if (!syncingPlayers.contains(playerId)) {
                        return false;
                    }
                    ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                    if (player == null || player.connection == null) {
                        return false;
                    }
                    if (startDimension != null && !player.level().dimension().equals(startDimension)) {
                        LOGGER.info("Player {} changed dimension from {} to {}, aborting sync",
                                playerId, DimensionApiHelper.getDimId(startDimension),
                                DimensionApiHelper.getDimId(player.level().dimension()));
                        syncingPlayers.remove(playerId);
                        playerSyncDimensions.remove(playerId);
                        return false;
                    }
                    return true;
                }).get(PLAYER_VALIDATION_TIMEOUT_SEC, TimeUnit.SECONDS);
                return valid ? PlayerCheckResult.VALID : PlayerCheckResult.INVALID;
            } catch (java.util.concurrent.TimeoutException e) {
                LOGGER.warn("Player {} validation timed out (attempt {}/{})", playerId, attempt,
                        PLAYER_VALIDATION_MAX_ATTEMPTS);
                if (attempt >= PLAYER_VALIDATION_MAX_ATTEMPTS) {
                    return PlayerCheckResult.TIMEOUT;
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to validate player {} on server thread (attempt {}/{})",
                        playerId, attempt, PLAYER_VALIDATION_MAX_ATTEMPTS, e);
                if (attempt >= PLAYER_VALIDATION_MAX_ATTEMPTS) {
                    return PlayerCheckResult.TIMEOUT;
                }
            }
        }
        return PlayerCheckResult.TIMEOUT;
    }

    private static void notifySyncAborted(MinecraftServer server, UUID playerId, int syncVersion, String reason) {
        enqueueIfCurrent(server, playerId, syncVersion, player ->
                NetworkManager.sendToPlayer(player, new SyncProgressPayload(0, 0, "aborted:" + reason)));
    }

    /**
     * 懒启动分包超时检查定时器。
     * 客户端同步请求拆分为多包发送，若穿透隧道丢包导致服务端永远等不齐，
     * 客户端会一直显示"等待服务器响应"。此定时器检测长时间未到齐的分包，
     * 清空缓冲并通知客户端重发整个请求，实现穿透丢包兜底。
     */
    private static void ensurePartAssemblyTimer() {
        ScheduledExecutorService timer = partAssemblyTimer;
        if (timer != null && !timer.isShutdown()) {
            return;
        }
        synchronized (ServerSyncHandlerLogic.class) {
            if (partAssemblyTimer != null && !partAssemblyTimer.isShutdown()) {
                return;
            }
            timer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mapsyncer-part-assembly-timer");
                t.setDaemon(true);
                return t;
            });
            partAssemblyTimer = timer;
            timer.scheduleWithFixedDelay(ServerSyncHandlerLogic::checkPartAssemblyTimeouts,
                    PART_ASSEMBLY_TIMEOUT_MS, PART_ASSEMBLY_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
            LOGGER.info("Part assembly timeout checker started (timeout={}ms, interval={}ms)",
                    PART_ASSEMBLY_TIMEOUT_MS, PART_ASSEMBLY_CHECK_INTERVAL_MS);
        }
    }

    /**
     * 定时扫描分包缓冲：发现超过 {@link #PART_ASSEMBLY_TIMEOUT_MS} 未到齐的分包，
     * 清空并通知客户端重发整个请求。
     */
    private static void checkPartAssemblyTimeouts() {
        if (requestPartBuffer.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        MinecraftServer server = currentServer;
        for (Map.Entry<UUID, Long> entry : requestPartLastActivity.entrySet()) {
            UUID playerId = entry.getKey();
            if (now - entry.getValue() < PART_ASSEMBLY_TIMEOUT_MS) {
                continue;
            }
            Map<Integer, SyncRequestPayload> parts = requestPartBuffer.remove(playerId);
            if (parts == null) {
                requestPartLastActivity.remove(playerId);
                continue;
            }
            Integer total = requestTotalParts.get(playerId);
            requestTotalParts.remove(playerId);
            requestPartLastActivity.remove(playerId);
            LOGGER.warn("SyncRequest parts incomplete for player {} ({} of {} parts received), notifying retry",
                    playerId, parts.size(), total == null ? -1 : total);
            notifyClientRetry(playerId, server);
        }
    }

    /**
     * 在主线程上向指定玩家发送"分包未到齐，请重发"通知。
     */
    private static void notifyClientRetry(UUID playerId, MinecraftServer server) {
        if (server == null) {
            return;
        }
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(playerId);
            if (sp != null) {
                LOGGER.info("Notifying player {} to retry sync request (partial timeout)", playerId);
                NetworkManager.sendToPlayer(sp, new SyncProgressPayload(0, 0, "request_partial_timeout"));
            }
        });
    }

    private static boolean isPlayerStillValid(MinecraftServer server, UUID playerId,
            ResourceKey<Level> startDimension, int syncVersion) {
        if (!isSyncStillActive(playerId, syncVersion)) {
            return false;
        }
        PlayerCheckResult result = checkPlayerOnMainThread(server, playerId, startDimension, syncVersion);
        if (result == PlayerCheckResult.TIMEOUT) {
            // 主线程繁忙（autosave/区块生成/实体卡顿）导致校验任务排队超时。
            // 此时玩家通常仍在游戏内，不应中断正在进行的同步；
            // 记录日志并跳过本次校验，下一次循环再验证。
            // 若玩家真的断开/切换维度，后续任务执行后会返回 INVALID 并正常停止同步。
            LOGGER.warn("Player {} validation timed out, skipping check (sync continues)",
                    playerId);
            return true;
        }
        return result == PlayerCheckResult.VALID;
    }

    /**
     * 从xaeromap.txt文件读取worldId
     *
     * 文件位置：<world>/xaeromap.txt
     * 格式：id:<number>
     *
     * @param serverPlayer 服务端玩家实例
     * @return worldId，如果文件不存在返回0
     */
    private static int readWorldIdFromXaeroMap(ServerPlayer serverPlayer) {
        try {
            Path xaeromapPath = serverPlayer.level().getServer()
                    .getWorldPath(LevelResource.LEVEL_DATA_FILE).getParent()
                    .resolve("xaeromap.txt");

            if (!Files.exists(xaeromapPath)) {
                LOGGER.warn("xaeromap.txt not found at {}", xaeromapPath);
                return 0;
            }

            try (BufferedReader reader = new BufferedReader(new FileReader(xaeromapPath.toFile()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(":");
                    if (parts.length == 2 && parts[0].equals("id")) {
                        int worldId = Integer.parseInt(parts[1]);
                        LOGGER.info("Read worldId {} from xaeromap.txt", worldId);
                        return worldId;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.error("Failed to read xaeromap.txt", e);
        }
        return 0;
    }

    /**
     * 根据发送的数据量计算休眠时间，实现带宽感知的速度限制。
     *
     * 核心思路：
     * 1. 维护一个限速周期的累计发送量和周期开始时间
     * 2. 每次发送后，计算当前周期的平均带宽
     * 3. 如果平均带宽超过限速值，计算需要等待的时间
     * 4. 如果实际发送时间已经超过预期时间（网络瓶颈），则不需要额外等待
     *
     * 这种方式能自动适应网络状况：
     * - 当网络带宽充足时，通过等待来限制发送速度
     * - 当网络瓶颈导致发送速度低于限速时，不额外等待
     *
     * @param bytesSent 本次发送的字节数
     * @param player 玩家实例（用于中断检查）
     * @param playerId 玩家UUID（用于中断检查）
     * @return true 表示速度限制完成，false 表示玩家已掉线应中断同步
     */
    private static boolean applySpeedLimit(int bytesSent, MinecraftServer server, UUID playerId,
            ResourceKey<Level> startDimension, int syncVersion) {
        int limitKBps = PlatformManager.getPlatform().getSyncSpeedLimitKBps();
        if (limitKBps <= 0) return true; // No limit

        // 获取或初始化限速周期状态
        Long cycleStart = speedLimitCycleStart.get(playerId);
        Long totalBytes = speedLimitBytesSent.get(playerId);

        if (cycleStart == null || totalBytes == null) {
            // 新周期开始
            cycleStart = System.currentTimeMillis();
            totalBytes = 0L;
            speedLimitCycleStart.put(playerId, cycleStart);
            speedLimitBytesSent.put(playerId, totalBytes);
        }

        // 累加本次发送量
        totalBytes += bytesSent;
        speedLimitBytesSent.put(playerId, totalBytes);

        // 计算当前周期实际耗时
        long actualTimeMs = System.currentTimeMillis() - cycleStart;

        // 如果周期时间超过上限，重置周期（防止累计量过大）
        if (actualTimeMs > MAX_SPEED_LIMIT_CYCLE_MS) {
            LOGGER.debug("Speed limit cycle too long ({} ms), resetting", actualTimeMs);
            speedLimitCycleStart.put(playerId, System.currentTimeMillis());
            speedLimitBytesSent.put(playerId, 0L);
            // 重新计算（使用本次发送量作为新周期的起点）
            totalBytes = (long) bytesSent;
            speedLimitBytesSent.put(playerId, totalBytes);
            cycleStart = System.currentTimeMillis();
            actualTimeMs = 0;
        }

        // 计算在限速下，发送这些字节应该花费的时间
        long expectedTimeMs = (totalBytes * 1000L) / (limitKBps * 1024L);

        // 如果实际耗时 >= 预期耗时，说明网络瓶颈已经限制了发送速度，不需要等待
        if (actualTimeMs >= expectedTimeMs) {
            LOGGER.debug("Bandwidth bottleneck detected: sent {} bytes in {} ms (expected {} ms at {} KBps), skipping wait",
                    totalBytes, actualTimeMs, expectedTimeMs, limitKBps);
            // 重置周期，因为当前周期的带宽已经低于限速值
            speedLimitCycleStart.put(playerId, System.currentTimeMillis());
            speedLimitBytesSent.put(playerId, 0L);
            return true;
        }

        // 计算需要等待的剩余时间
        long remainingTimeMs = expectedTimeMs - actualTimeMs;

        LOGGER.debug("Applying speed limit: sent {} bytes in {} ms, need to wait {} ms more (limit: {} KBps)",
                totalBytes, actualTimeMs, remainingTimeMs, limitKBps);

        // 执行可中断的等待
        long checkIntervalMs = 100; // Check every 100ms
        long waitStartTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - waitStartTime < remainingTimeMs) {
            // Check if player disconnected during speed limit wait
            if (!isPlayerStillValid(server, playerId, startDimension, syncVersion)) {
                LOGGER.info("Player {} disconnected during speed limit wait, aborting sync", playerId);
                return false;
            }

            long waitRemainingMs = remainingTimeMs - (System.currentTimeMillis() - waitStartTime);
            if (waitRemainingMs <= 0) {
                break;
            }
            long sleepMs = Math.min(checkIntervalMs, waitRemainingMs);

            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        // 等待完成后，重置周期开始新的限速周期
        speedLimitCycleStart.put(playerId, System.currentTimeMillis());
        speedLimitBytesSent.put(playerId, 0L);

        return true;
    }

    /**
     * 清除玩家的限速状态。
     *
     * @param playerId 玩家UUID
     */
    private static void clearSpeedLimitState(UUID playerId) {
        speedLimitBytesSent.remove(playerId);
        speedLimitCycleStart.remove(playerId);
    }

    /**
     * 清除玩家的所有同步状态（同步完成或中断时调用）。
     * 版本号由 {@link ServerSyncSession#finalizeSession(UUID)} 移除。
     */
    private static void finalizePlayerSync(UUID playerId) {
        syncingPlayers.remove(playerId);
        playerSyncDimensions.remove(playerId);
        ServerSyncSession.finalizeSession(playerId);

        requestPartBuffer.remove(playerId);
        requestTotalParts.remove(playerId);

        // 清理线程引用并确保线程已停止
        Thread syncThread = syncThreads.remove(playerId);
        if (syncThread != null && syncThread.isAlive()) {
            syncThread.interrupt();
        }

        clearSpeedLimitState(playerId);
    }

    /**
     * 处理客户端同步请求
     *
     * 接收客户端元数据，比对服务端缓存，发送差异数据。
     * 基于哈希比对实现自动断点续传，无需索引恢复。
     *
     * **重要**：同步处理在异步线程执行，避免阻塞服务器主线程导致 Watchdog 崩溃。
     *
     * @param payload 同步请求数据包
     * @param context 数据包上下文
     */
    private static void handleSyncRequest(SyncRequestPayload payload, PayloadContext context) {
        Player player = (Player) NetworkManager.getHandler().getPlayerFromContext(context);
        if (!(player instanceof ServerPlayer serverPlayer)) return;

        UUID playerId = serverPlayer.getUUID();

        // 保存服务器引用（分包超时定时线程向玩家发通知用）
        MinecraftServer server = PlayerLevelApiHelper.getServer(serverPlayer);
        if (server != null) {
            currentServer = server;
        }

        // 记录分包活动时间 + 懒启动分包超时检查定时器（穿透丢包兜底）
        requestPartLastActivity.put(playerId, System.currentTimeMillis());
        ensurePartAssemblyTimer();

        // 组装分片 SyncRequestPayload
        if (payload.totalParts() > 1) {
            Integer existingTotal = requestTotalParts.get(playerId);
            if (existingTotal != null && existingTotal != payload.totalParts()) {
                requestPartBuffer.remove(playerId);
                LOGGER.debug("SyncRequest totalParts changed {}→{}, resetting buffer for player {}",
                        existingTotal, payload.totalParts(), playerId);
            }

            // 使用 compute 原子地 put 分片并检查是否到齐
            final SyncRequestPayload currentPayload = payload;
            boolean[] allArrived = new boolean[1];
            Map<Integer, SyncRequestPayload> parts = requestPartBuffer.compute(playerId, (k, existing) -> {
                if (existing == null) {
                    existing = new ConcurrentHashMap<>();
                }
                existing.put(currentPayload.partIndex(), currentPayload);
                allArrived[0] = existing.size() >= currentPayload.totalParts();
                return existing;
            });
            requestTotalParts.put(playerId, payload.totalParts());

            if (!allArrived[0]) {
                LOGGER.debug("SyncRequest part {}/{} from player {}", payload.partIndex() + 1, payload.totalParts(), playerId);
                return;
            }

            // 全部到齐，移除并合并（remove 可能返回 null 如果已被另一线程处理）
            parts = requestPartBuffer.remove(playerId);
            if (parts == null) {
                return;
            }
            requestTotalParts.remove(playerId);

            Map<String, ClientMeta> merged = new HashMap<>();
            SyncRequestPayload refPart = null;
            for (SyncRequestPayload part : parts.values()) {
                merged.putAll(part.clientMeta());
                if (refPart == null) {
                    refPart = part;
                }
            }
            payload = new SyncRequestPayload(merged, refPart.partIndex(), refPart.totalParts(),
                    refPart.syncAll(), refPart.targetDimension(), refPart.silent());
            LOGGER.debug("SyncRequest assembled from {} parts, {} entries total", parts.size(), merged.size());
        }

        // 请求已进入处理流程（单包或多包到齐），清除分包超时跟踪
        requestPartLastActivity.remove(playerId);

        // 递增版本号，用于标记此次请求（旧请求的 server.execute() 任务会通过版本号自过滤）
        int syncVersion = globalSyncVersion.incrementAndGet();

        // 如果玩家已经在同步中，先中断旧的同步线程（保留即将 assign 的新 version）
        ServerSyncSession.interruptOldSyncThread(playerId, syncThreads, () -> clearSpeedLimitState(playerId));

        ServerSyncSession.assignVersion(playerId, syncVersion);

        ResourceKey<Level> startDimension = serverPlayer.level().dimension();

        // Mark player as syncing and record starting dimension (在主线程快速完成)
        syncingPlayers.add(playerId);
        playerSyncDimensions.put(playerId, startDimension);

        // 在主线程预捕获玩家坐标，避免后台线程读取非线程安全的 ServerPlayer 字段
        int startBlockX = serverPlayer.getBlockX();
        int startBlockZ = serverPlayer.getBlockZ();
        int viewDistanceChunks = PlayerLevelApiHelper.getServer(serverPlayer).getPlayerList().getViewDistance() + 2;
        int viewDistanceRegions = (viewDistanceChunks >> 5) + 1;
        int worldId = readWorldIdFromXaeroMap(serverPlayer);

        // Client metadata (timestamp + hash) - contains already received regions for resume
        Map<String, ClientMeta> clientMeta = payload.clientMeta();
        boolean syncAll = payload.syncAll();
        String targetDimension = payload.targetDimension();
        boolean silent = payload.silent();

        // 将耗时操作移到异步线程执行，避免阻塞主线程
        Thread syncThread = new Thread(() -> processSyncAsync(server, playerId, clientMeta, syncAll, targetDimension,
                startDimension, syncVersion, startBlockX, startBlockZ, viewDistanceRegions, worldId, silent),
                "mapsyncer-sync-" + playerId);
        syncThread.setDaemon(true);
        syncThreads.put(playerId, syncThread);  // 存储线程引用，用于断线时中断
        syncThread.start();
        LOGGER.debug("Started async sync thread for player {} (v{})", serverPlayer.getName().getString(), syncVersion);
    }

    /**
     * 在主线程执行任务前检查版本号是否匹配（旧请求的入队任务自动丢弃）。
     */
    private static void enqueueIfCurrent(MinecraftServer server, UUID playerId, int version, Consumer<ServerPlayer> task) {
        server.execute(() -> {
            if (!ServerSyncSession.isCurrent(playerId, version)) {
                return;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                return;
            }
            task.accept(player);
        });
    }

    private static void sendSyncCompleteMessage(ServerPlayer player, int sentCount, int failedCount, int totalPlanned) {
        if (failedCount > 0) {
            player.sendSystemMessage(ChatUtils.error("mapsyncer.server.sync_partial", sentCount, failedCount, totalPlanned));
        } else {
            player.sendSystemMessage(ChatUtils.success("mapsyncer.server.sync_complete", sentCount));
        }
    }

    /**
     * 从缓存目录发现所有含 region 数据的维度（首次 sync all 时 clientMeta 为空）。
     */
    private static Set<String> discoverDimensionsFromCache(Path cacheDir) {
        Set<String> dims = new HashSet<>();
        if (!Files.exists(cacheDir)) {
            return dims;
        }
        try (Stream<Path> topLevel = Files.list(cacheDir)) {
            topLevel.filter(Files::isDirectory).forEach(dimDir -> {
                String xaeroDim = dimDir.getFileName().toString();
                try (Stream<Path> stream = Files.walk(dimDir)) {
                    if (stream.anyMatch(p -> p.toString().endsWith(".zip"))) {
                        dims.add(xaeroDim);
                    }
                } catch (IOException e) {
                    LOGGER.warn("Failed to walk dimension {} cache", xaeroDim, e);
                }
            });
        } catch (IOException e) {
            LOGGER.warn("Failed to list cache directory", e);
        }
        return dims;
    }

    /**
     * 异步处理同步请求。
     * 在单独线程中执行耗时操作（遍历缓存、比对哈希、发送数据），
     * 避免阻塞服务器主线程。
     */
    private static void processSyncAsync(MinecraftServer server, UUID playerId,
            Map<String, ClientMeta> clientMeta, boolean syncAll, String targetDimension,
            ResourceKey<Level> startDimension, int syncVersion,
            int startBlockX, int startBlockZ, int viewDistanceRegions, int worldId, boolean silent) {

        LOGGER.debug("Server worldId from xaeromap.txt: {}", worldId);

        Path cacheDir = ConversionOrchestrator.getCacheDir();
        GenerationCache genCache = GenerationCache.getInstance(cacheDir);
        genCache.pruneInvalidEntries(cacheDir);
        Map<String, TimestampHashEntry> serverCache = genCache.getAll();

        if (!Files.exists(cacheDir)) {
            enqueueIfCurrent(server, playerId, syncVersion, player -> {
                player.sendSystemMessage(ChatUtils.message(
                        "mapsyncer.server.no_cache", CacheCommandHandler.serverCommandPrefix()));
                NetworkManager.sendToPlayer(player,
                        new SyncResponsePayload(List.of(), true, worldId, "no_cache"));
                finalizePlayerSync(playerId);
            });
            return;
        }

        int hashMatchCount = 0;
        int timestampSkipCount = 0;

        Set<String> requestedDimensions = new java.util.HashSet<>();
        if (syncAll) {
            requestedDimensions.addAll(discoverDimensionsFromCache(cacheDir));
            LOGGER.info("Sync-all: discovered {} dimensions from cache", requestedDimensions.size());
        } else if (targetDimension != null && !targetDimension.isEmpty()) {
            requestedDimensions.add(targetDimension);
            LOGGER.debug("Single-dimension sync: {}", targetDimension);
        } else {
            for (String key : clientMeta.keySet()) {
                LOGGER.debug("Client meta key: {}", key);
                String[] keyParts = key.split("[/\\\\]");
                if (keyParts.length > 1) {
                    requestedDimensions.add(keyParts[0]);
                    if (key.contains("_placeholder_")) {
                        LOGGER.debug("Found placeholder for dimension {}, will sync all regions", keyParts[0]);
                    }
                }
            }
        }
        LOGGER.debug("Requested dimensions (Xaero format): {}", requestedDimensions);

        Set<String> skippedDimensions = new HashSet<>();
        DimensionPathMapping dimMapping = DimensionPathMapping.getInstance();
        boolean hasValidDimension = false;
        boolean alreadyNotifiedMissingDim = false;

        for (String xaeroDim : requestedDimensions) {
            Path dimCacheDir = cacheDir.resolve(xaeroDim);
            if (Files.exists(dimCacheDir) && dimCacheDir.toFile().isDirectory()) {
                try (Stream<Path> stream = Files.walk(dimCacheDir)) {
                    boolean hasZipFiles = stream.anyMatch(p -> p.toString().endsWith(".zip"));
                    if (hasZipFiles) {
                        hasValidDimension = true;
                    }
                } catch (IOException e) {
                    LOGGER.warn("Failed to check dimension {} cache directory", xaeroDim, e);
                }
            } else if (!syncAll) {
                String friendlyDim = dimMapping.toServerDimension(xaeroDim);
                enqueueIfCurrent(server, playerId, syncVersion, player ->
                        player.sendSystemMessage(ChatUtils.error(
                                "mapsyncer.server.dim_not_available",
                                friendlyDim,
                                CacheCommandHandler.serverCommandPrefix(),
                                friendlyDim)));
                alreadyNotifiedMissingDim = true;
                LOGGER.warn("Requested dimension {} (xaero: {}) has no cache data at {}", friendlyDim, xaeroDim, dimCacheDir);
            } else {
                LOGGER.debug("Sync-all: skipping dimension {} with no cache", xaeroDim);
            }
        }

        if (!hasValidDimension) {
            LOGGER.debug("No valid dimension cache found for requested dimensions: {}", requestedDimensions);
            String prefix = CacheCommandHandler.serverCommandPrefix();
            boolean skipChat = alreadyNotifiedMissingDim;
            enqueueIfCurrent(server, playerId, syncVersion, player -> {
                if (!skipChat) {
                    if (targetDimension != null && !targetDimension.isEmpty()) {
                        String friendlyDim = dimMapping.toServerDimension(targetDimension);
                        player.sendSystemMessage(ChatUtils.error(
                                "mapsyncer.server.dim_not_available",
                                friendlyDim, prefix, friendlyDim));
                    } else {
                        player.sendSystemMessage(ChatUtils.message(
                                "mapsyncer.server.no_cache", prefix));
                    }
                }
                NetworkManager.sendToPlayer(player,
                        new SyncResponsePayload(List.of(), true, worldId, "dim_not_available"));
                finalizePlayerSync(playerId);
            });
            return;
        }

        List<RegionSyncInfo> regionsToSync = new ArrayList<>();

        Path absCacheDir = cacheDir.toAbsolutePath().normalize();
        List<Path> allZipPaths;
        try (Stream<Path> stream = Files.walk(absCacheDir)) {
            allZipPaths = stream.filter(p -> p.toString().endsWith(".zip")).toList();
        } catch (IOException e) {
            LOGGER.error("Failed to walk cache directory", e);
            allZipPaths = List.of();
        }

        allZipPaths.forEach(zipPath -> {
                        String relativePath = absCacheDir.relativize(zipPath).toString();
                        String normalizedPath = relativePath.replace(".zip", "").replace("\\", "/");
                        normalizedPath = stripMwWorldId(normalizedPath);

                        String[] parts = normalizedPath.split("[/\\\\]");
                        String xaeroDimName = parts.length > 1 ? parts[0] : "unknown";

                        String normalizedXaeroDim = dimMapping.toXaeroDimension(xaeroDimName);
                        if (!normalizedXaeroDim.equals(xaeroDimName)) {
                            normalizedPath = normalizedXaeroDim + normalizedPath.substring(xaeroDimName.length());
                        }

                        if (!requestedDimensions.contains(normalizedXaeroDim)) {
                            if (!skippedDimensions.contains(normalizedXaeroDim)) {
                                skippedDimensions.add(normalizedXaeroDim);
                                LOGGER.debug("Skipping dimension {}: not requested", normalizedXaeroDim);
                            }
                            return;
                        }

                        TimestampHashEntry serverMeta = serverCache.get(normalizedPath);
                        ClientMeta clientMetaEntry = clientMeta.get(normalizedPath);

                        if (!HashUtils.isValidRegionZip(zipPath)) {
                            if (serverMeta != null) {
                                genCache.remove(normalizedPath);
                            }
                            return;
                        }

                        String serverHash;
                        long timestamp;
                        if (serverMeta == null) {
                            serverHash = HashUtils.computeFileHash(zipPath);
                            timestamp = System.currentTimeMillis() / 1000;
                        } else {
                            serverHash = serverMeta.hash();
                            timestamp = serverMeta.timestampSeconds();
                        }

                        if (RegionSyncPolicy.shouldTransfer(serverHash, timestamp, clientMetaEntry)) {
                            RegionSyncInfo info = parseRegionInfo(zipPath, normalizedPath, timestamp);
                            if (info != null) {
                                regionsToSync.add(info);
                            }
                        }
                    });

        genCache.save();

        for (Map.Entry<String, TimestampHashEntry> entry : serverCache.entrySet()) {
            ClientMeta cm = clientMeta.get(entry.getKey());
            if (cm != null && entry.getValue().hash().equals(cm.hash())) {
                hashMatchCount++;
            } else if (cm != null && cm.timestampSeconds() >= entry.getValue().timestampSeconds()) {
                timestampSkipCount++;
            }
        }

        int total = regionsToSync.size();
        final int finalHashMatchCount = hashMatchCount;
        final int finalTimestampSkipCount = timestampSkipCount;

        LOGGER.info("Sync request from player {}: {} regions to sync, {} hash match, {} timestamp skip",
                playerId, total, finalHashMatchCount, finalTimestampSkipCount);

        if (total == 0) {
            enqueueIfCurrent(server, playerId, syncVersion, player -> {
                if (!silent) {
                    player.sendSystemMessage(ChatUtils.success("mapsyncer.server.map_uptodate", finalHashMatchCount, finalTimestampSkipCount));
                }
                NetworkManager.sendToPlayer(player,
                        new SyncResponsePayload(List.of(), true, worldId, "uptodate"));
                finalizePlayerSync(playerId);
            });
            return;
        }

        sortByViewDistancePriority(regionsToSync, startBlockX, startBlockZ, viewDistanceRegions);

        final int initialTotal = total;
        final int initialHashMatch = hashMatchCount;
        final int initialTimestampSkip = timestampSkipCount;
        enqueueIfCurrent(server, playerId, syncVersion, player -> {
                if (!silent) {
                    player.sendSystemMessage(ChatUtils.message("mapsyncer.server.sync_start", initialTotal, initialHashMatch, initialTimestampSkip));
                }
                NetworkManager.sendToPlayer(player,
                        new SyncProgressPayload(0, initialTotal, "Sync started"));
        });

        List<ChunkMapData> batch = new ArrayList<>();
        int batchBytes = 0;
        int processed = 0;
        int batchRegionCount = 0;
        int sentRegionCount = 0;
        int failedReadCount = 0;
        int batchThreshold = getBatchThreshold();

        for (RegionSyncInfo info : regionsToSync) {
            if (!isPlayerStillValid(server, playerId, startDimension, syncVersion)) {
                LOGGER.info("Player {} disconnected during sync", playerId);
                finalizePlayerSync(playerId);
                return;
            }

            ChunkMapData chunk = readRegionData(info);
            if (chunk == null) {
                failedReadCount++;
                LOGGER.warn("Failed to read region data: {}", info.normalizedPath());
                continue;
            }

            ChunkMapData[] parts = ChunkMapData.split(chunk);
            for (ChunkMapData part : parts) {
                if (batchBytes + part.data.length > batchThreshold && !batch.isEmpty()) {
                    if (!applySpeedLimit(batchBytes, server, playerId, startDimension, syncVersion)) {
                        LOGGER.info("Player {} disconnected during speed limit, aborting sync", playerId);
                        finalizePlayerSync(playerId);
                        return;
                    }

                    sendBatchInChunks(batch, batchBytes, server, worldId, processed, total, playerId, syncVersion);
                    processed += batchRegionCount;
                    sentRegionCount += batchRegionCount;

                    batch.clear();
                    batchBytes = 0;
                    batchRegionCount = 0;
                }

                batch.add(part);
                batchBytes += part.data.length;
            }
            batchRegionCount++;
        }

        if (!isPlayerStillValid(server, playerId, startDimension, syncVersion)) {
            LOGGER.info("Player {} disconnected before final batch", playerId);
            finalizePlayerSync(playerId);
            return;
        }

        final int finalSentCount = sentRegionCount + batchRegionCount;
        final int finalFailedCount = failedReadCount;
        final int finalTotal = total;
        final String completeStatus = finalFailedCount > 0 ? "partial" : "ok";

        if (!batch.isEmpty()) {
            if (!applySpeedLimit(batchBytes, server, playerId, startDimension, syncVersion)) {
                LOGGER.info("Player {} disconnected during final speed limit, aborting sync", playerId);
                finalizePlayerSync(playerId);
                return;
            }

            final int maxPacketSize = getMaxPacketSize();
            if (batchBytes <= maxPacketSize) {
                final List<ChunkMapData> finalBatch = new ArrayList<>(batch);
                enqueueIfCurrent(server, playerId, syncVersion, player -> {
                    NetworkManager.sendToPlayer(player,
                            new SyncResponsePayload(finalBatch, true, worldId, completeStatus));
                    NetworkManager.sendToPlayer(player,
                            new SyncProgressPayload(finalTotal, finalTotal, "completed"));
                    if (!silent) {
                        sendSyncCompleteMessage(player, finalSentCount, finalFailedCount, finalTotal);
                    }
                    finalizePlayerSync(playerId);
                });
            } else {
                List<ChunkMapData> currentChunk = new ArrayList<>();
                int currentSize = 0;

                for (ChunkMapData chunk : batch) {
                    if (currentSize + chunk.data.length > maxPacketSize && !currentChunk.isEmpty()) {
                        final List<ChunkMapData> chunkToSend = new ArrayList<>(currentChunk);
                        final int sentProgress = processed;
                        enqueueIfCurrent(server, playerId, syncVersion, player -> {
                            NetworkManager.sendToPlayer(player,
                                    new SyncResponsePayload(chunkToSend, false, worldId, "ok"));
                            NetworkManager.sendToPlayer(player,
                                    new SyncProgressPayload(sentProgress, finalTotal,
                                            String.format("Sending regions %d/%d", sentProgress, finalTotal)));
                        });

                        currentChunk.clear();
                        currentSize = 0;
                    }

                    currentChunk.add(chunk);
                    currentSize += chunk.data.length;
                }

                if (!currentChunk.isEmpty()) {
                    final List<ChunkMapData> lastChunk = new ArrayList<>(currentChunk);
                    enqueueIfCurrent(server, playerId, syncVersion, player -> {
                        NetworkManager.sendToPlayer(player,
                                new SyncResponsePayload(lastChunk, true, worldId, completeStatus));
                        NetworkManager.sendToPlayer(player,
                                new SyncProgressPayload(finalTotal, finalTotal, "completed"));
                        if (!silent) {
                            sendSyncCompleteMessage(player, finalSentCount, finalFailedCount, finalTotal);
                        }
                        finalizePlayerSync(playerId);
                    });
                }
            }
        } else {
            enqueueIfCurrent(server, playerId, syncVersion, player -> {
                NetworkManager.sendToPlayer(player,
                        new SyncProgressPayload(finalTotal, finalTotal, "completed"));
                if (!silent) {
                    sendSyncCompleteMessage(player, finalSentCount, finalFailedCount, finalTotal);
                }
                finalizePlayerSync(playerId);
            });
        }

        LOGGER.info("Map sync complete for player {}: {} regions sent, {} failed", playerId, finalSentCount, finalFailedCount);
    }

    /**
     * 解析 region 信息（不含数据）。
     * 用于流式处理，先收集路径信息再排序发送。
     *
     * @param zipPath zip文件路径
     * @param normalizedPath 规范化的相对路径
     * @param timestampSeconds 时间戳（秒）
     * @return RegionSyncInfo，如果解析失败返回 null
     */
    private static RegionSyncInfo parseRegionInfo(Path zipPath, String normalizedPath, long timestampSeconds) {
        try {
            String[] parts = normalizedPath.split("[/\\\\]");

            String dimension;
            int caveLayer = Integer.MAX_VALUE;
            String fileName;

            if (parts.length >= 4 && parts[1].equals("caves")) {
                dimension = parts[0];
                caveLayer = Integer.parseInt(parts[2]);
                fileName = parts[3];
            } else {
                dimension = parts[0];
                fileName = parts[parts.length - 1];
            }

            String[] coords = fileName.split("_");
            int regionX = Integer.parseInt(coords[0]);
            int regionZ = Integer.parseInt(coords[1]);

            return new RegionSyncInfo(zipPath, normalizedPath, timestampSeconds, regionX, regionZ, dimension, caveLayer);
        } catch (NumberFormatException e) {
            LOGGER.error("Failed to parse path: {}", normalizedPath, e);
            return null;
        }
    }

    /**
     * 读取单个 region 的数据。
     * 流式处理中按需读取，避免一次性加载所有数据。
     *
     * @param info region同步信息
     * @return ChunkMapData，如果读取失败返回 null
     */
    private static ChunkMapData readRegionData(RegionSyncInfo info) {
        try {
            byte[] data = Files.readAllBytes(info.zipPath());
            return new ChunkMapData(info.regionX(), info.regionZ(), info.dimension(),
                    data, info.timestampSeconds(), info.caveLayer());
        } catch (IOException e) {
            LOGGER.error("Failed to read zip file: {}", info.zipPath(), e);
            return null;
        }
    }

    /**
     * 清除所有跟踪数据
     *
     * 在服务器停止时调用，防止内存泄漏。
     */
    public static void cleanup() {
        for (Thread thread : syncThreads.values()) {
            if (thread != null && thread.isAlive()) {
                thread.interrupt();
            }
        }
        syncingPlayers.clear();
        playerSyncDimensions.clear();
        ServerSyncSession.clearAllVersions();
        syncThreads.clear();
        speedLimitBytesSent.clear();
        speedLimitCycleStart.clear();
        requestPartBuffer.clear();
        requestTotalParts.clear();
        requestPartLastActivity.clear();
        ScheduledExecutorService timer = partAssemblyTimer;
        if (timer != null) {
            timer.shutdownNow();
            partAssemblyTimer = null;
        }
        LOGGER.debug("ServerSyncHandler tracking data cleared");
    }

    /**
     * 清理离线玩家的残留状态
     *
     * <p>玩家异常断线时，onPlayerDisconnect可能未被调用，导致状态残留。
     * 此方法定期检查并清理不在在线列表中的玩家状态。</p>
     *
     * @param onlinePlayerIds 当前在线玩家的UUID集合
     */
    public static void cleanupOfflinePlayers(Set<UUID> onlinePlayerIds) {
        // 检查syncingPlayers中的玩家是否仍然在线
        Set<UUID> toRemove = new HashSet<>();
        for (UUID playerId : syncingPlayers) {
            if (!onlinePlayerIds.contains(playerId)) {
                toRemove.add(playerId);
            }
        }

        // 清理离线玩家的状态
        for (UUID playerId : toRemove) {
            LOGGER.debug("Cleaning up stale state for offline player {}", playerId);
            finalizePlayerSync(playerId);
        }

        // 清理已结束但未移除的线程引用（防止内存泄漏）
        cleanupCompletedThreads();

        if (!toRemove.isEmpty()) {
            LOGGER.debug("Cleaned up {} stale player states", toRemove.size());
        }
    }

    /**
     * 清理已结束的同步线程引用。
     *
     * <p>线程正常完成后，Thread对象可能残留在syncThreads Map中。
     * 此方法检查并清理所有已终止的线程，防止内存泄漏。</p>
     */
    private static void cleanupCompletedThreads() {
        Set<UUID> completedThreads = new HashSet<>();

        for (Map.Entry<UUID, Thread> entry : syncThreads.entrySet()) {
            Thread thread = entry.getValue();
            // 线程已终止（不再存活），标记为需要清理
            if (thread == null || !thread.isAlive()) {
                completedThreads.add(entry.getKey());
            }
        }

        for (UUID playerId : completedThreads) {
            LOGGER.debug("Cleaning up completed thread for player {}", playerId);
            syncThreads.remove(playerId);
            syncingPlayers.remove(playerId);
            playerSyncDimensions.remove(playerId);
            ServerSyncSession.finalizeSession(playerId);
            clearSpeedLimitState(playerId);
        }

        if (!completedThreads.isEmpty()) {
            LOGGER.debug("Cleaned up {} completed thread references", completedThreads.size());
        }
    }

    /**
     * 按视距优先排序同步列表。
     * 视距内的region排在最前面，让玩家最先收到周围的地图数据。
     *
     * <p>排序逻辑：</p>
     * <ul>
     *   <li>计算玩家当前位置对应的region坐标</li>
     *   <li>视距内的region（与玩家region距离≤视距region数）排在最前</li>
     *   <li>视距外的region按与玩家的距离排序（近者优先）</li>
     * </ul>
     *
     * @param regions 待同步的region信息列表
     * @param player 服务端玩家实例
     */
    private static void sortByViewDistancePriority(List<RegionSyncInfo> regions, int startBlockX, int startBlockZ, int viewDistanceRegions) {
        // 使用主线程预捕获的玩家坐标，避免后台线程读取非线程安全的 ServerPlayer 字段
        int playerChunkX = startBlockX >> 4;
        int playerChunkZ = startBlockZ >> 4;
        int playerRegionX = playerChunkX >> 5;
        int playerRegionZ = playerChunkZ >> 5;

        LOGGER.debug("Player region: ({}, {}), view distance regions: ~{}",
                playerRegionX, playerRegionZ, viewDistanceRegions);

        // 计算每个region到玩家的距离，并排序
        regions.sort((a, b) -> {
            int distA = Math.max(Math.abs(a.regionX() - playerRegionX), Math.abs(a.regionZ() - playerRegionZ));
            int distB = Math.max(Math.abs(b.regionX() - playerRegionX), Math.abs(b.regionZ() - playerRegionZ));

            // 视距内的region（距离≤视距）排在最前，视距外按距离排序
            boolean aInView = distA <= viewDistanceRegions;
            boolean bInView = distB <= viewDistanceRegions;

            if (aInView && !bInView) return -1;  // a在视距内，排前面
            if (!aInView && bInView) return 1;   // b在视距内，排前面
            return Integer.compare(distA, distB); // 都在视距内或都在视距外，按距离排序
        });

        // 统计视距内region数量
        int viewRegionCount = 0;
        for (RegionSyncInfo info : regions) {
            int dist = Math.max(Math.abs(info.regionX() - playerRegionX), Math.abs(info.regionZ() - playerRegionZ));
            if (dist <= viewDistanceRegions) {
                viewRegionCount++;
            }
        }

        LOGGER.debug("Sorted {} regions: {} in view distance ({} region radius), rest by distance",
                regions.size(), viewRegionCount, viewDistanceRegions);
    }

    private static String stripMwWorldId(String path) {
        String[] parts = path.split("/");
        if (parts.length >= 3 && parts[1].startsWith("mw$")) {
            StringBuilder sb = new StringBuilder(parts[0]);
            for (int i = 2; i < parts.length; i++) {
                sb.append("/").append(parts[i]);
            }
            return sb.toString();
        }
        return path;
    }
}
