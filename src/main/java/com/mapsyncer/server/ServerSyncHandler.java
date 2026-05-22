package com.mapsyncer.server;

import com.mapsyncer.client.ClientHashManager.ClientMeta;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.network.ChunkMapData;
import com.mapsyncer.network.PacketHandler;
import com.mapsyncer.server.GenerationCache.RegionMeta;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.DimensionPathMapping;
import com.mapsyncer.util.HashUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * 注意：此类通过主mod类的modBus.addListener()手动注册，
 * 因为RegisterPayloadHandlersEvent是MOD总线事件。
 */
public class ServerSyncHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerSyncHandler.class);

    /** 最大数据包大小上限（1MB），避免超过 NeoForge 网络限制 */
    private static final int MAX_PACKET_SIZE_LIMIT = 1_000_000;

    /**
     * 获取实际的最大数据包大小
     * 从配置读取，但如果超过上限则使用上限值
     *
     * @return 最大数据包大小（字节）
     */
    private static int getMaxPacketSize() {
        int configValue = ModConfig.SERVER.maxSyncPacketSize.get();
        return Math.min(configValue, MAX_PACKET_SIZE_LIMIT);
    }

    /** 正在同步的玩家集合（用于断线或维度切换时中断同步） */
    private static final Set<UUID> syncingPlayers = ConcurrentHashMap.newKeySet();

    /** 玩家同步开始时的维度（用于维度切换时中断同步） */
    private static final Map<UUID, ResourceKey<Level>> playerSyncDimensions = new ConcurrentHashMap<>();

    /** 玩家同步线程引用（用于断线时立即中断线程） */
    private static final Map<UUID, Thread> syncThreads = new ConcurrentHashMap<>();

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
     * 注册网络数据包处理器
     *
     * @param event 数据包处理器注册事件
     */
    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("1").optional();

        registrar.playToServer(
                PacketHandler.SyncRequestPayload.TYPE,
                PacketHandler.SyncRequestPayload.STREAM_CODEC,
                ServerSyncHandler::handleSyncRequest
        );

        registrar.playToClient(
                PacketHandler.SyncResponsePayload.TYPE,
                PacketHandler.SyncResponsePayload.STREAM_CODEC,
                (payload, ctx) -> {}
        );

        registrar.playToClient(
                PacketHandler.SyncProgressPayload.TYPE,
                PacketHandler.SyncProgressPayload.STREAM_CODEC,
                (payload, ctx) -> {}
        );

        // Server sends ServerInstalledPayload to client on player join
        registrar.playToClient(
                PacketHandler.ServerInstalledPayload.TYPE,
                PacketHandler.ServerInstalledPayload.STREAM_CODEC,
                (payload, ctx) -> {}
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

        // 立即中断同步线程
        Thread syncThread = syncThreads.remove(playerId);
        if (syncThread != null && syncThread.isAlive()) {
            syncThread.interrupt();
            LOGGER.info("Player {} disconnected, sync thread interrupted", playerId);
        }
    }

    /**
     * 检查玩家是否仍然有效（在线、在同步会话中、在同一维度）
     *
     * @param player 服务端玩家实例
     * @return true表示玩家有效，false表示无效（应中断同步）
     */
    private static boolean isPlayerStillValid(ServerPlayer player) {
        UUID playerId = player.getUUID();

        // Check if player is still online and still in our sync set
        if (!syncingPlayers.contains(playerId) || player.connection == null) {
            return false;
        }

        // Check if player is still in the same dimension
        ResourceKey<Level> startDimension = playerSyncDimensions.get(playerId);
        if (startDimension != null && !player.level().dimension().equals(startDimension)) {
            LOGGER.info("Player {} changed dimension from {} to {}, aborting sync",
                    playerId, startDimension.location(), player.level().dimension().location());
            syncingPlayers.remove(playerId);
            playerSyncDimensions.remove(playerId);
            return false;
        }

        return true;
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
     * 根据发送的数据量计算休眠时间，强制执行速度限制
     * 使用可中断的循环等待，玩家掉线时可立即退出
     *
     * @param bytesSent 本次发送的字节数
     * @param player 玩家实例（用于中断检查）
     * @param playerId 玩家UUID（用于中断检查）
     * @return true 表示速度限制完成，false 表示玩家已掉线应中断同步
     */
    private static boolean applySpeedLimit(int bytesSent, ServerPlayer player, UUID playerId) {
        int limitKBps = ModConfig.SERVER.syncSpeedLimitKBps.get();
        if (limitKBps <= 0) return true; // No limit

        // Calculate how long this batch should take at the limit speed
        long expectedTimeMs = (bytesSent * 1000L) / (limitKBps * 1024);
        if (expectedTimeMs <= 0) return true;

        // Use interruptible wait with periodic player status check
        long startTime = System.currentTimeMillis();
        long checkIntervalMs = 100; // Check every 100ms

        while (System.currentTimeMillis() - startTime < expectedTimeMs) {
            // Check if player disconnected during speed limit wait
            if (!isPlayerStillValid(player)) {
                LOGGER.info("Player {} disconnected during speed limit wait, aborting sync", playerId);
                return false;
            }

            long remainingMs = expectedTimeMs - (System.currentTimeMillis() - startTime);
            long sleepMs = Math.min(checkIntervalMs, remainingMs);

            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        return true;
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
    private static void handleSyncRequest(PacketHandler.SyncRequestPayload payload, IPayloadContext context) {
        Player player = context.player();
        if (!(player instanceof ServerPlayer serverPlayer)) return;

        UUID playerId = serverPlayer.getUUID();
        ResourceKey<Level> startDimension = serverPlayer.level().dimension();

        // Mark player as syncing and record starting dimension (在主线程快速完成)
        syncingPlayers.add(playerId);
        playerSyncDimensions.put(playerId, startDimension);

        // Client metadata (timestamp + hash) - contains already received regions for resume
        Map<String, ClientMeta> clientMeta = payload.clientMeta();

        // 将耗时操作移到异步线程执行，避免阻塞主线程
        Thread syncThread = new Thread(() -> processSyncAsync(serverPlayer, playerId, clientMeta, startDimension),
                "mapsyncer-sync-" + playerId);
        syncThread.setDaemon(true);
        syncThreads.put(playerId, syncThread);  // 存储线程引用，用于断线时中断
        syncThread.start();
        LOGGER.info("Started async sync thread for player {}", serverPlayer.getName().getString());
    }

    /**
     * 异步处理同步请求。
     * 在单独线程中执行耗时操作（遍历缓存、比对哈希、发送数据），
     * 避免阻塞服务器主线程。
     *
     * @param serverPlayer 服务端玩家实例
     * @param playerId 玩家UUID
     * @param clientMeta 客户端元数据
     * @param startDimension 开始同步时的维度
     */
    private static void processSyncAsync(ServerPlayer serverPlayer, UUID playerId,
            Map<String, ClientMeta> clientMeta, ResourceKey<Level> startDimension) {

        // Read worldId from xaeromap.txt (Xaero's official method)
        int worldId = readWorldIdFromXaeroMap(serverPlayer);
        LOGGER.info("Server worldId from xaeromap.txt: {}", worldId);

        // Get server generation cache (timestamp + hash)
        GenerationCache genCache = GenerationCache.getInstance(ConversionOrchestrator.CACHE_DIR);
        Map<String, RegionMeta> serverCache = genCache.getAll();

        Path cacheDir = ConversionOrchestrator.CACHE_DIR;

        if (!Files.exists(cacheDir)) {
            // 在主线程发送消息和数据包
            serverPlayer.serverLevel().getServer().execute(() -> {
                serverPlayer.sendSystemMessage(ChatUtils.message("mapsyncer.server.no_cache"));
                PacketDistributor.sendToPlayer(serverPlayer,
                        new PacketHandler.SyncResponsePayload(List.of(), true, worldId, "no_cache"));
            });
            syncingPlayers.remove(playerId);
            playerSyncDimensions.remove(playerId);
            syncThreads.remove(playerId);
            return;
        }

        // Sync logic:
        // 1. Hash match → skip (file content identical)
        // 2. Hash mismatch + client timestamp older → sync
        // 3. Hash mismatch + client timestamp newer → skip (client has newer data)
        // 4. Client has no metadata for this region → sync (new region)
        int hashMatchCount = 0;
        int timestampSkipCount = 0;

        // Determine which dimensions the client is requesting (based on their metadata keys)
        Set<String> requestedDimensions = new java.util.HashSet<>();
        for (String key : clientMeta.keySet()) {
            LOGGER.debug("Client meta key: {}", key);
            String[] parts = key.split("[/\\\\]");
            if (parts.length > 1) {
                String dim = parts[0];
                if (!key.contains("_placeholder_")) {
                    requestedDimensions.add(dim);
                } else {
                    requestedDimensions.add(dim);
                    LOGGER.info("Found placeholder for dimension {}, will sync all regions", dim);
                }
            }
        }
        LOGGER.info("Client requesting dimensions (Xaero format): {}", requestedDimensions);

        // Check if requested dimensions have cache data
        Set<String> skippedDimensions = new HashSet<>();
        DimensionPathMapping dimMapping = DimensionPathMapping.getInstance();
        boolean hasValidDimension = false;

        for (String xaeroDim : requestedDimensions) {
            Path dimCacheDir = cacheDir.resolve(xaeroDim);
            if (Files.exists(dimCacheDir) && dimCacheDir.toFile().isDirectory()) {
                try {
                    boolean hasZipFiles = Files.walk(dimCacheDir)
                            .anyMatch(p -> p.toString().endsWith(".zip"));
                    if (hasZipFiles) {
                        hasValidDimension = true;
                    }
                } catch (IOException e) {
                    LOGGER.warn("Failed to check dimension {} cache directory", xaeroDim, e);
                }
            } else {
                String friendlyDim = dimMapping.toServerDimension(xaeroDim);
                // 在主线程发送消息
                serverPlayer.serverLevel().getServer().execute(() -> {
                    serverPlayer.sendSystemMessage(ChatUtils.error("mapsyncer.server.dim_not_available", friendlyDim, friendlyDim));
                });
                LOGGER.warn("Requested dimension {} (xaero: {}) has no cache data at {}", friendlyDim, xaeroDim, dimCacheDir);
            }
        }

        if (!hasValidDimension) {
            LOGGER.info("No valid dimension cache found for requested dimensions: {}", requestedDimensions);
            // 在主线程发送数据包
            serverPlayer.serverLevel().getServer().execute(() -> {
                PacketDistributor.sendToPlayer(serverPlayer,
                        new PacketHandler.SyncResponsePayload(List.of(), true, worldId, "dim_not_available"));
            });
            syncingPlayers.remove(playerId);
            playerSyncDimensions.remove(playerId);
            syncThreads.remove(playerId);
            return;
        }

        // Compare server cache with client metadata to find differences
        // 流式处理：只收集路径信息，不读取数据
        List<RegionSyncInfo> regionsToSync = new ArrayList<>();

        try (Stream<Path> stream = Files.walk(cacheDir)) {
            stream.filter(p -> p.toString().endsWith(".zip"))
                    .forEach(zipPath -> {
                        String relativePath = cacheDir.relativize(zipPath).toString();
                        String normalizedPath = relativePath.replace(".zip", "").replace("\\", "/");

                        String[] parts = normalizedPath.split("[/\\\\]");
                        String xaeroDimName = parts.length > 1 ? parts[0] : "unknown";

                        String normalizedXaeroDim = dimMapping.toXaeroDimension(xaeroDimName);
                        if (!normalizedXaeroDim.equals(xaeroDimName)) {
                            normalizedPath = normalizedXaeroDim + normalizedPath.substring(xaeroDimName.length());
                        }

                        if (!requestedDimensions.contains(normalizedXaeroDim)) {
                            if (!skippedDimensions.contains(normalizedXaeroDim)) {
                                skippedDimensions.add(normalizedXaeroDim);
                                LOGGER.info("Skipping dimension {}: not requested", normalizedXaeroDim);
                            }
                            return;
                        }

                        RegionMeta serverMeta = serverCache.get(normalizedPath);
                        ClientMeta clientMetaEntry = clientMeta.get(normalizedPath);

                        // 判断是否需要同步
                        boolean shouldSync = false;
                        long timestamp = 0;

                        // Server has no cache entry → compute hash from file
                        if (serverMeta == null) {
                            String serverHash = HashUtils.computeFileHash(zipPath);
                            timestamp = System.currentTimeMillis() / 1000;

                            if (clientMetaEntry == null) {
                                shouldSync = true;
                            } else if (!serverHash.equals(clientMetaEntry.hash())) {
                                shouldSync = true;
                            }
                        } else {
                            // Client has no metadata → sync (new region)
                            if (clientMetaEntry == null) {
                                shouldSync = true;
                                timestamp = serverMeta.timestampSeconds();
                            } else if (!serverMeta.hash().equals(clientMetaEntry.hash())) {
                                // Hash mismatch → check timestamps
                                if (clientMetaEntry.timestampSeconds() < serverMeta.timestampSeconds()) {
                                    shouldSync = true;
                                    timestamp = serverMeta.timestampSeconds();
                                }
                            }
                        }

                        if (shouldSync) {
                            // 解析路径信息，但不读取数据
                            RegionSyncInfo info = parseRegionInfo(zipPath, normalizedPath, timestamp);
                            if (info != null) {
                                regionsToSync.add(info);
                            }
                        }
                    });
        } catch (IOException e) {
            LOGGER.error("Failed to walk cache directory", e);
        }

        // Count hash matches and timestamp skips
        for (Map.Entry<String, RegionMeta> entry : serverCache.entrySet()) {
            ClientMeta cm = clientMeta.get(entry.getKey());
            if (cm != null && entry.getValue().hash().equals(cm.hash())) {
                hashMatchCount++;
            } else if (cm != null && cm.timestampSeconds() >= entry.getValue().timestampSeconds()) {
                timestampSkipCount++;
            }
        }

        int total = regionsToSync.size();
        // 创建 final 变量供 lambda 使用
        final int finalHashMatchCount = hashMatchCount;
        final int finalTimestampSkipCount = timestampSkipCount;

        LOGGER.info("Sync request from {}: {} regions to sync, {} hash match, {} timestamp skip",
                serverPlayer.getName().getString(), total, finalHashMatchCount, finalTimestampSkipCount);

        if (total == 0) {
            // 在主线程发送消息
            serverPlayer.serverLevel().getServer().execute(() -> {
                serverPlayer.sendSystemMessage(ChatUtils.success("mapsyncer.server.map_uptodate", finalHashMatchCount, finalTimestampSkipCount));
                PacketDistributor.sendToPlayer(serverPlayer,
                        new PacketHandler.SyncResponsePayload(List.of(), true, worldId, "uptodate"));
            });
            syncingPlayers.remove(playerId);
            playerSyncDimensions.remove(playerId);
            syncThreads.remove(playerId);
            return;
        }

        // 按视距优先排序：视距内region最先发送，让玩家更快看到周围地图
        sortByViewDistancePriority(regionsToSync, serverPlayer);

        // 立即发送初始进度通知，避免客户端超时
        final int initialTotal = total;
        serverPlayer.serverLevel().getServer().execute(() -> {
            PacketDistributor.sendToPlayer(serverPlayer,
                    new PacketHandler.SyncProgressPayload(0, initialTotal,
                            String.format("Preparing to sync %d regions", initialTotal)));
        });

        // 流式处理：逐个读取数据并发送，避免一次性加载所有数据到内存
        List<ChunkMapData> batch = new ArrayList<>();
        int batchSize = 0;
        int batchBytes = 0;
        int processed = 0;
        boolean isFirstBatch = true;

        for (RegionSyncInfo info : regionsToSync) {
            if (!isPlayerStillValid(serverPlayer)) {
                LOGGER.info("Player {} disconnected during sync", playerId);
                syncThreads.remove(playerId);
                return;
            }

            // 读取单个region的数据（流式处理）
            ChunkMapData chunk = readRegionData(info);
            if (chunk == null) {
                LOGGER.warn("Failed to read region data: {}", info.normalizedPath());
                continue;
            }

            // 数据发送后立即可以释放，因为 batch 只保存引用
            // 但需要在发送前复制数据，因为异步发送需要数据存活

            if (batchSize + chunk.data.length > getMaxPacketSize() && !batch.isEmpty()) {
                // 第一批数据立即发送，避免客户端超时；后续批次执行速度限制
                if (!isFirstBatch) {
                    // Apply speed limit with interruptible check
                    if (!applySpeedLimit(batchBytes, serverPlayer, playerId)) {
                        LOGGER.info("Player {} disconnected during speed limit, aborting sync", playerId);
                        syncThreads.remove(playerId);
                        return;
                    }
                }

                // 在主线程发送数据包
                final List<ChunkMapData> batchToSend = new ArrayList<>(batch);
                final int processedCount = processed;
                final int totalCount = total;
                serverPlayer.serverLevel().getServer().execute(() -> {
                    PacketDistributor.sendToPlayer(serverPlayer,
                            new PacketHandler.SyncResponsePayload(batchToSend, false, worldId, "ok"));
                    PacketDistributor.sendToPlayer(serverPlayer,
                            new PacketHandler.SyncProgressPayload(processedCount, totalCount,
                                    String.format("Sending regions %d/%d", processedCount, totalCount)));
                });
                processed += batch.size();
                isFirstBatch = false;

                batch.clear();
                batchSize = 0;
                batchBytes = 0;
            }

            batch.add(chunk);
            batchSize += chunk.data.length;
            batchBytes += chunk.data.length;
        }

        if (!isPlayerStillValid(serverPlayer)) {
            LOGGER.info("Player {} disconnected before final batch", playerId);
            syncThreads.remove(playerId);
            return;
        }

        // Send final batch
        if (!batch.isEmpty()) {
            // 如果之前已发送过数据（不是第一批），则执行速度限制
            if (!isFirstBatch) {
                if (!applySpeedLimit(batchBytes, serverPlayer, playerId)) {
                    LOGGER.info("Player {} disconnected during final speed limit, aborting sync", playerId);
                    syncThreads.remove(playerId);
                    return;
                }
            }

            // 在主线程发送数据包
            final List<ChunkMapData> finalBatch = new ArrayList<>(batch);
            final int finalTotal = total;
            serverPlayer.serverLevel().getServer().execute(() -> {
                PacketDistributor.sendToPlayer(serverPlayer,
                        new PacketHandler.SyncResponsePayload(finalBatch, true, worldId, "ok"));
                PacketDistributor.sendToPlayer(serverPlayer,
                        new PacketHandler.SyncProgressPayload(finalTotal, finalTotal, "completed"));
                serverPlayer.sendSystemMessage(ChatUtils.success("mapsyncer.server.sync_complete", finalTotal));
            });
            processed += batch.size();
        } else {
            // 没有数据要发送，但仍需发送完成消息
            serverPlayer.serverLevel().getServer().execute(() -> {
                PacketDistributor.sendToPlayer(serverPlayer,
                        new PacketHandler.SyncProgressPayload(total, total, "completed"));
                serverPlayer.sendSystemMessage(ChatUtils.success("mapsyncer.server.sync_complete", total));
            });
        }

        LOGGER.info("Map sync complete for player {}: {} regions", serverPlayer.getName().getString(), total);

        syncingPlayers.remove(playerId);
        playerSyncDimensions.remove(playerId);
        syncThreads.remove(playerId);
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
        syncingPlayers.clear();
        playerSyncDimensions.clear();
        syncThreads.clear();
        LOGGER.info("ServerSyncHandler tracking data cleared");
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
    private static void sortByViewDistancePriority(List<RegionSyncInfo> regions, ServerPlayer player) {
        // 获取玩家位置
        int playerChunkX = player.getBlockX() >> 4;
        int playerChunkZ = player.getBlockZ() >> 4;
        int playerRegionX = playerChunkX >> 5;
        int playerRegionZ = playerChunkZ >> 5;

        // 获取视距（渲染距离），加2 chunks作为移动偏移容差
        int viewDistanceChunks = player.serverLevel().getServer().getPlayerList().getViewDistance() + 2;
        int viewDistanceRegions = (viewDistanceChunks >> 5) + 1;  // 向上取整

        LOGGER.debug("Player region: ({}, {}), view distance: {} chunks = ~{} regions",
                playerRegionX, playerRegionZ, viewDistanceChunks, viewDistanceRegions);

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

        LOGGER.info("Sorted {} regions: {} in view distance ({} region radius), rest by distance",
                regions.size(), viewRegionCount, viewDistanceRegions);
    }
}