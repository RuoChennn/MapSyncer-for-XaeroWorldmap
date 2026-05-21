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

/**
 * 服务端同步处理器 - 处理客户端请求的地图数据同步
 *
 * 功能：
 * - 接收客户端同步请求，包含客户端缓存的元数据（时间戳+哈希）
 * - 比对服务端缓存与客户端元数据，确定需要同步的区域
 * - 分批发送差异区域数据到客户端
 * - 支持同步中断恢复（玩家断线后重新连接可继续）
 * - 支持速度限制，避免网络拥塞
 *
 * 同步逻辑：
 * 1. 哈希值一致 → 不同步（文件内容相同）
 * 2. 哈希值不一致 + 客户端时间戳旧于服务端 → 同步
 * 3. 哈希值不一致 + 客户端时间戳新于服务端 → 不同步（客户端有新数据）
 * 4. 客户端无该区域的元数据 → 同步（新区域）
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

    /** 玩家同步进度（用于断线恢复） */
    private static final Map<UUID, SyncProgress> playerSyncProgress = new ConcurrentHashMap<>();

    /**
     * 同步进度记录 - 用于断线恢复
     *
     * 仅存储元数据（总数、起始索引），不缓存实际的区块数据，
     * 以最小化内存占用并防止内存泄漏。
     */
    public static class SyncProgress {
        /** 需同步的总区块数 */
        public final int totalChunks;

        /** 恢复起始位置 */
        public final int startIndex;

        /** 世界ID */
        public final int worldId;

        /** 同步开始时间 */
        public final long startTime;

        /** 最后活动时间（用于检测过期） */
        public final long lastActivityTime;

        /**
         * 构造同步进度记录
         *
         * @param totalChunks 需同步的总区块数
         * @param startIndex 恢复起始位置
         * @param worldId 世界ID
         */
        public SyncProgress(int totalChunks, int startIndex, int worldId) {
            this.totalChunks = totalChunks;
            this.startIndex = startIndex;
            this.worldId = worldId;
            this.startTime = System.currentTimeMillis();
            this.lastActivityTime = System.currentTimeMillis();
        }

        /**
         * 检查同步进度是否过期（无活动时间过长）
         *
         * 过期进度将被清除以防止内存泄漏。
         *
         * @param timeoutMs 过期超时时间（毫秒）
         * @return true表示已过期
         */
        public boolean isStale(long timeoutMs) {
            return System.currentTimeMillis() - lastActivityTime > timeoutMs;
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
    }

    /**
     * 玩家断线事件处理
     *
     * 标记同步为已中断但保留进度，用于断线恢复。
     *
     * @param playerId 玩家UUID
     */
    public static void onPlayerDisconnect(UUID playerId) {
        if (syncingPlayers.remove(playerId)) {
            playerSyncDimensions.remove(playerId);
            LOGGER.info("Player {} disconnected, sync paused (progress preserved for resume)", playerId);
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
            playerSyncProgress.remove(playerId);
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
     *
     * @param bytesSent 本次发送的字节数
     */
    private static void applySpeedLimit(int bytesSent) {
        int limitKBps = ModConfig.SERVER.syncSpeedLimitKBps.get();
        if (limitKBps <= 0) return; // No limit

        // Calculate how long this batch should take at the limit speed
        long expectedTimeMs = (bytesSent * 1000L) / (limitKBps * 1024);
        if (expectedTimeMs <= 0) return;

        try {
            Thread.sleep(expectedTimeMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 处理客户端同步请求
     *
     * 接收客户端元数据，比对服务端缓存，发送差异数据。
     *
     * @param payload 同步请求数据包
     * @param context 数据包上下文
     */
    private static void handleSyncRequest(PacketHandler.SyncRequestPayload payload, IPayloadContext context) {
        Player player = context.player();
        if (!(player instanceof ServerPlayer serverPlayer)) return;

        UUID playerId = serverPlayer.getUUID();
        ResourceKey<Level> startDimension = serverPlayer.level().dimension();

        // Mark player as syncing and record starting dimension
        syncingPlayers.add(playerId);
        playerSyncDimensions.put(playerId, startDimension);

        // Client metadata (timestamp + hash)
        Map<String, ClientMeta> clientMeta = payload.clientMeta();

        // Read worldId from xaeromap.txt (Xaero's official method)
        int worldId = readWorldIdFromXaeroMap(serverPlayer);
        LOGGER.info("Server worldId from xaeromap.txt: {}", worldId);

        // Get server generation cache (timestamp + hash)
        GenerationCache genCache = GenerationCache.getInstance(ConversionOrchestrator.CACHE_DIR);
        Map<String, RegionMeta> serverCache = genCache.getAll();

        List<ChunkMapData> diffs = new ArrayList<>();
        Path cacheDir = ConversionOrchestrator.CACHE_DIR;

        if (!Files.exists(cacheDir)) {
            serverPlayer.sendSystemMessage(ChatUtils.message("mapsyncer.server.no_cache"));
            PacketDistributor.sendToPlayer(serverPlayer,
                    new PacketHandler.SyncResponsePayload(List.of(), true, worldId, "no_cache"));
            syncingPlayers.remove(playerId);
            playerSyncDimensions.remove(playerId);
            playerSyncProgress.remove(playerId);
            return;
        }

        // Sync logic:
        // 1. Hash match → skip (file content identical)
        // 2. Hash mismatch + client timestamp older → sync
        // 3. Hash mismatch + client timestamp newer → skip (client has newer data)
        // 4. Client has no metadata for this dimension → skip (not requested)
        int hashMatchCount = 0;
        int timestampSkipCount = 0;

        // Determine which dimensions the client is requesting (based on their metadata keys)
        // 客户端发送的维度名是 Xaero 格式（如 null, DIM-1, DIM1, twilightforest$twilight_forest）
        Set<String> requestedDimensions = new java.util.HashSet<>();
        for (String key : clientMeta.keySet()) {
            LOGGER.info("Client meta key: {}", key);
            String[] parts = key.split("[/\\\\]");
            if (parts.length > 1) {
                String dim = parts[0];
                // Skip placeholder entries (used when client has no local data)
                if (!key.contains("_placeholder_")) {
                    requestedDimensions.add(dim);  // First part is dimension name (Xaero format)
                } else {
                    // Placeholder indicates client wants full sync for this dimension
                    requestedDimensions.add(dim);
                    LOGGER.info("Found placeholder for dimension {}, will sync all regions", dim);
                }
            }
        }
        LOGGER.info("Client requesting dimensions (Xaero format): {}", requestedDimensions);

        // 记录已经跳过的维度，避免重复打印日志
        Set<String> skippedDimensions = new HashSet<>();

        // 检查请求的维度是否有缓存数据
        // 客户端发送的是 Xaero 格式，服务端缓存目录也是 Xaero 格式
        DimensionPathMapping dimMapping = DimensionPathMapping.getInstance();
        boolean hasValidDimension = false;  // 是否至少有一个维度存在缓存
        for (String xaeroDim : requestedDimensions) {
            Path dimCacheDir = cacheDir.resolve(xaeroDim);
            if (Files.exists(dimCacheDir) && dimCacheDir.toFile().isDirectory()) {
                // 检查目录是否包含 zip 文件
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
                // 维度缓存不存在，发送错误提示
                String friendlyDim = dimMapping.toServerDimension(xaeroDim);
                serverPlayer.sendSystemMessage(ChatUtils.error("mapsyncer.server.dim_not_available", friendlyDim, friendlyDim));
                LOGGER.warn("Requested dimension {} (xaero: {}) has no cache data at {}", friendlyDim, xaeroDim, dimCacheDir);
            }
        }

        // 如果所有请求的维度都不存在缓存，立即返回（不显示 uptodate 消息）
        if (!hasValidDimension) {
            LOGGER.info("No valid dimension cache found for requested dimensions: {}", requestedDimensions);
            PacketDistributor.sendToPlayer(serverPlayer,
                    new PacketHandler.SyncResponsePayload(List.of(), true, worldId, "dim_not_available"));
            syncingPlayers.remove(playerId);
            playerSyncDimensions.remove(playerId);
            playerSyncProgress.remove(playerId);
            return;
        }

        try {
            Files.walk(cacheDir)
                    .filter(p -> p.toString().endsWith(".zip"))
                    .forEach(zipPath -> {
                        // Convert path to relative format: dimension/regionX_regionZ
                        String relativePath = cacheDir.relativize(zipPath).toString();
                        // Remove .zip extension and normalize path separator
                        String normalizedPath = relativePath.replace(".zip", "").replace("\\", "/");

                        // Parse dimension from path (服务端缓存目录使用 Xaero 格式)
                        String[] parts = normalizedPath.split("[/\\\\]");
                        String xaeroDimName = parts.length > 1 ? parts[0] : "unknown";

                        LOGGER.info("Checking cache file: normalizedPath={}, xaeroDimName={}", normalizedPath, xaeroDimName);

                        // 兼容旧版本缓存：将 Minecraft 格式的维度名转换为 Xaero 格式
                        // 旧版本可能生成 overworld, the_nether, the_end 目录
                        // 新版本使用 null, DIM-1, DIM1 目录
                        String normalizedXaeroDim = dimMapping.toXaeroDimension(xaeroDimName);
                        if (!normalizedXaeroDim.equals(xaeroDimName)) {
                            LOGGER.info("Legacy cache dir detected: {} -> {}", xaeroDimName, normalizedXaeroDim);
                            // 更新 normalizedPath 使用正确的 Xaero 格式
                            normalizedPath = normalizedXaeroDim + normalizedPath.substring(xaeroDimName.length());
                            LOGGER.info("Updated normalizedPath: {}", normalizedPath);
                        }

                        // Skip if client didn't request this dimension (直接用 Xaero 格式匹配)
                        if (!requestedDimensions.contains(normalizedXaeroDim)) {
                            // 只打印一次维度级别的跳过信息，避免重复日志
                            if (!skippedDimensions.contains(normalizedXaeroDim)) {
                                skippedDimensions.add(normalizedXaeroDim);
                                LOGGER.info("Skipping dimension {}: not in requestedDimensions {}", normalizedXaeroDim, requestedDimensions);
                            }
                            return;
                        }

                        RegionMeta serverMeta = serverCache.get(normalizedPath);
                        ClientMeta clientMetaEntry = clientMeta.get(normalizedPath);

                        LOGGER.info("Comparing {}: serverMeta={}, clientMetaEntry={}", normalizedPath,
                                serverMeta != null ? "ts=" + serverMeta.timestampSeconds() + ",hash=" + serverMeta.hash() : "null",
                                clientMetaEntry != null ? "ts=" + clientMetaEntry.timestampSeconds() + ",hash=" + clientMetaEntry.hash() : "null");

                        // Server has no GenerationCache entry → compute hash from file and compare
                        // This handles legacy cache that doesn't have generation_cache.properties
                        if (serverMeta == null) {
                            String serverHash = HashUtils.computeFileHash(zipPath);
                            long fileTimestamp = System.currentTimeMillis() / 1000;

                            LOGGER.info("No server cache entry, computed hash from file: {}, ts={}", serverHash, fileTimestamp);

                            // Client has no metadata → sync (new region for client)
                            if (clientMetaEntry == null) {
                                LOGGER.info("Will sync {}: client has no metadata (new region)", normalizedPath);
                                addChunkData(diffs, zipPath, normalizedPath, fileTimestamp);
                                return;
                            }

                            // Hash match → skip sync (file content identical)
                            if (serverHash.equals(clientMetaEntry.hash())) {
                                LOGGER.info("Skipping {}: hash match (computed server={}, client={})",
                                        normalizedPath, serverHash, clientMetaEntry.hash());
                                return;
                            }

                            // Hash mismatch → sync (server has newer data from file)
                            LOGGER.info("Will sync {}: hash mismatch (computed server={}, client={})",
                                    normalizedPath, serverHash, clientMetaEntry.hash());
                            addChunkData(diffs, zipPath, normalizedPath, fileTimestamp);
                            return;
                        }

                        // Client has no metadata → sync (new region for client)
                        if (clientMetaEntry == null) {
                            LOGGER.info("Will sync {}: client has no metadata (new region)", normalizedPath);
                            addChunkData(diffs, zipPath, normalizedPath, serverMeta.timestampSeconds());
                            return;
                        }

                        // Hash match → skip sync (file content identical)
                        if (serverMeta.hash().equals(clientMetaEntry.hash())) {
                            LOGGER.info("Skipping {}: hash match (server={}, client={})",
                                    normalizedPath, serverMeta.hash(), clientMetaEntry.hash());
                            return;  // hashMatchCount incremented outside lambda
                        }

                        // Hash mismatch → check timestamps
                        // Client timestamp older than server → sync
                        if (clientMetaEntry.timestampSeconds() < serverMeta.timestampSeconds()) {
                            LOGGER.info("Will sync {}: hash mismatch, client ts={}s < server ts={}s",
                                    normalizedPath, clientMetaEntry.timestampSeconds(), serverMeta.timestampSeconds());
                            addChunkData(diffs, zipPath, normalizedPath, serverMeta.timestampSeconds());
                        } else {
                            // Client timestamp newer → skip (client explored newer content)
                            LOGGER.info("Skipping {}: hash mismatch but client ts={}s >= server ts={}s",
                                    normalizedPath, clientMetaEntry.timestampSeconds(), serverMeta.timestampSeconds());
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

        int total = diffs.size();

        LOGGER.info("Sync request from {}: {} regions to sync, {} hash match (identical), {} timestamp skip (client newer)",
                serverPlayer.getName().getString(), total, hashMatchCount, timestampSkipCount);

        if (total == 0) {
            serverPlayer.sendSystemMessage(ChatUtils.success("mapsyncer.server.map_uptodate", hashMatchCount, timestampSkipCount));
            PacketDistributor.sendToPlayer(serverPlayer,
                    new PacketHandler.SyncResponsePayload(List.of(), true, worldId, "uptodate"));
            syncingPlayers.remove(playerId);
            playerSyncDimensions.remove(playerId);
            playerSyncProgress.remove(playerId);
            return;
        }

        // 直接开始同步，不发送开始消息（服务端消息将在完成时发送）

        // Check if this is a resumed sync
        SyncProgress existingProgress = playerSyncProgress.get(playerId);
        int startIndex = 0;
        if (existingProgress != null && ModConfig.SERVER.enableResumeSync.get()) {
            // Resume from last progress if same total count
            if (existingProgress.totalChunks == total) {
                startIndex = Math.min(existingProgress.totalChunks - 1, total - 1);
                LOGGER.info("Resuming sync for player {} from index {}", playerId, startIndex);
            } else {
                LOGGER.info("Sync total changed for player {}, starting fresh", playerId);
                playerSyncProgress.remove(playerId);
            }
        }

        // Store progress for potential resume (only metadata, not chunk data)
        playerSyncProgress.put(playerId, new SyncProgress(total, startIndex, worldId));

        // Send progress updates and data in batches with speed limiting
        int processed = startIndex;
        List<ChunkMapData> batch = new ArrayList<>();
        int batchSize = 0;
        int batchBytes = 0;

        for (int i = startIndex; i < diffs.size(); i++) {
            ChunkMapData chunk = diffs.get(i);

            // Check if player is still valid (connected + same dimension)
            if (!isPlayerStillValid(serverPlayer)) {
                LOGGER.info("Player {} became invalid during sync, pausing at index {}", playerId, i);
                return;  // Progress preserved for resume
            }

            // Check if adding this chunk would exceed packet size limit
            if (batchSize + chunk.data.length > getMaxPacketSize() && !batch.isEmpty()) {
                // Apply speed limit before sending
                applySpeedLimit(batchBytes);

                // Send current batch
                PacketDistributor.sendToPlayer(serverPlayer,
                        new PacketHandler.SyncResponsePayload(new ArrayList<>(batch), false, worldId, "ok"));
                processed += batch.size();

                // Send progress update
                PacketDistributor.sendToPlayer(serverPlayer,
                        new PacketHandler.SyncProgressPayload(processed, total,
                                String.format("Sending regions %d/%d", processed, total)));

                // Clear batch and reset size
                batch.clear();
                batchSize = 0;
                batchBytes = 0;
            }

            batch.add(chunk);
            batchSize += chunk.data.length;
            batchBytes += chunk.data.length;
        }

        // Check player validity before final batch
        if (!isPlayerStillValid(serverPlayer)) {
            LOGGER.info("Player {} became invalid before final batch, pausing at index {}", playerId, processed);
            return;  // Progress preserved for resume
        }

        // Send final batch with completion flag
        if (!batch.isEmpty()) {
            // Apply speed limit before sending final batch
            applySpeedLimit(batchBytes);

            PacketDistributor.sendToPlayer(serverPlayer,
                    new PacketHandler.SyncResponsePayload(new ArrayList<>(batch), true, worldId, "ok"));
            processed += batch.size();
        }

        PacketDistributor.sendToPlayer(serverPlayer,
                new PacketHandler.SyncProgressPayload(total, total, "completed"));

        serverPlayer.sendSystemMessage(ChatUtils.success("mapsyncer.server.sync_complete", total));
        LOGGER.info("Map sync complete for player {}: {} regions",
                serverPlayer.getName().getString(), total);

        // Remove from tracking sets
        syncingPlayers.remove(playerId);
        playerSyncDimensions.remove(playerId);
        playerSyncProgress.remove(playerId);
    }

    /**
     * 从zip文件添加区块数据
     *
     * 路径格式解析：
     * - 地表：dim/regionX_regionZ
     * - 洞穴：dim/caves/layer/regionX_regionZ
     *
     * @param diffs 差异数据列表
     * @param zipPath zip文件路径
     * @param normalizedPath 规范化的相对路径
     * @param timestampSeconds 时间戳（秒）
     */
    private static void addChunkData(List<ChunkMapData> diffs, Path zipPath, String normalizedPath, long timestampSeconds) {
        try {
            byte[] data = Files.readAllBytes(zipPath);
            // Parse dimension, caveLayer, and coordinates from path
            String[] parts = normalizedPath.split("[/\\\\]");

            // 解析维度名、洞穴层和坐标
            String dimension;
            int caveLayer = Integer.MAX_VALUE;  // 默认地表
            String fileName;

            if (parts.length >= 4 && parts[1].equals("caves")) {
                // 洞穴层格式：dim/caves/layer/regionX_regionZ
                dimension = parts[0];
                caveLayer = Integer.parseInt(parts[2]);
                fileName = parts[3];
            } else {
                // 地表格式：dim/regionX_regionZ
                dimension = parts[0];
                fileName = parts[parts.length - 1];
            }

            String[] coords = fileName.split("_");
            int regionX = Integer.parseInt(coords[0]);
            int regionZ = Integer.parseInt(coords[1]);

            diffs.add(new ChunkMapData(regionX, regionZ, dimension, data, timestampSeconds, caveLayer));
        } catch (IOException e) {
            LOGGER.error("Failed to read zip file: {}", zipPath, e);
        } catch (NumberFormatException e) {
            LOGGER.error("Failed to parse path: {}", normalizedPath, e);
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
        playerSyncProgress.clear();
        LOGGER.info("ServerSyncHandler tracking data cleared");
    }

    /**
     * 清除过期的同步进度记录
     *
     * 无活动超过5分钟的进度被视为过期，将被清除以防止内存泄漏。
     */
    public static void clearStaleProgress() {
        final long STALE_TIMEOUT_MS = 5 * 60 * 1000; // 5 minutes
        int cleared = 0;
        for (Map.Entry<UUID, SyncProgress> entry : playerSyncProgress.entrySet()) {
            if (entry.getValue().isStale(STALE_TIMEOUT_MS)) {
                playerSyncProgress.remove(entry.getKey());
                syncingPlayers.remove(entry.getKey());
                playerSyncDimensions.remove(entry.getKey());
                cleared++;
                LOGGER.info("Cleared stale sync progress for player {}", entry.getKey());
            }
        }
        if (cleared > 0) {
            LOGGER.info("Cleared {} stale sync progress entries", cleared);
        }
    }
}