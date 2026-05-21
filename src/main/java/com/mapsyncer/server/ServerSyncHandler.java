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
     * 哈希比对机制会自动处理断点续传：
     * - 客户端重连后发送已接收区域的哈希（从 sync_timestamps.cache 读取）
     * - 服务端比对后只同步差异区域
     *
     * @param playerId 玩家UUID
     */
    public static void onPlayerDisconnect(UUID playerId) {
        syncingPlayers.remove(playerId);
        playerSyncDimensions.remove(playerId);
        LOGGER.info("Player {} disconnected, sync interrupted. Resume via hash comparison on reconnect.", playerId);
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
     * 基于哈希比对实现自动断点续传，无需索引恢复。
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

        // Client metadata (timestamp + hash) - contains already received regions for resume
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
            LOGGER.info("Client meta key: {}", key);
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
                serverPlayer.sendSystemMessage(ChatUtils.error("mapsyncer.server.dim_not_available", friendlyDim, friendlyDim));
                LOGGER.warn("Requested dimension {} (xaero: {}) has no cache data at {}", friendlyDim, xaeroDim, dimCacheDir);
            }
        }

        if (!hasValidDimension) {
            LOGGER.info("No valid dimension cache found for requested dimensions: {}", requestedDimensions);
            PacketDistributor.sendToPlayer(serverPlayer,
                    new PacketHandler.SyncResponsePayload(List.of(), true, worldId, "dim_not_available"));
            syncingPlayers.remove(playerId);
            playerSyncDimensions.remove(playerId);
            return;
        }

        // Compare server cache with client metadata to find differences
        try {
            Files.walk(cacheDir)
                    .filter(p -> p.toString().endsWith(".zip"))
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

                        // Server has no cache entry → compute hash from file
                        if (serverMeta == null) {
                            String serverHash = HashUtils.computeFileHash(zipPath);
                            long fileTimestamp = System.currentTimeMillis() / 1000;

                            if (clientMetaEntry == null) {
                                addChunkData(diffs, zipPath, normalizedPath, fileTimestamp);
                                return;
                            }

                            if (serverHash.equals(clientMetaEntry.hash())) {
                                return; // Hash match, skip
                            }

                            addChunkData(diffs, zipPath, normalizedPath, fileTimestamp);
                            return;
                        }

                        // Client has no metadata → sync (new region)
                        if (clientMetaEntry == null) {
                            addChunkData(diffs, zipPath, normalizedPath, serverMeta.timestampSeconds());
                            return;
                        }

                        // Hash match → skip
                        if (serverMeta.hash().equals(clientMetaEntry.hash())) {
                            return;
                        }

                        // Hash mismatch → check timestamps
                        if (clientMetaEntry.timestampSeconds() < serverMeta.timestampSeconds()) {
                            addChunkData(diffs, zipPath, normalizedPath, serverMeta.timestampSeconds());
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

        LOGGER.info("Sync request from {}: {} regions to sync, {} hash match, {} timestamp skip",
                serverPlayer.getName().getString(), total, hashMatchCount, timestampSkipCount);

        if (total == 0) {
            serverPlayer.sendSystemMessage(ChatUtils.success("mapsyncer.server.map_uptodate", hashMatchCount, timestampSkipCount));
            PacketDistributor.sendToPlayer(serverPlayer,
                    new PacketHandler.SyncResponsePayload(List.of(), true, worldId, "uptodate"));
            syncingPlayers.remove(playerId);
            playerSyncDimensions.remove(playerId);
            return;
        }

        // Send data in batches with speed limiting
        List<ChunkMapData> batch = new ArrayList<>();
        int batchSize = 0;
        int batchBytes = 0;
        int processed = 0;

        for (ChunkMapData chunk : diffs) {
            if (!isPlayerStillValid(serverPlayer)) {
                LOGGER.info("Player {} disconnected during sync", playerId);
                return;
            }

            if (batchSize + chunk.data.length > getMaxPacketSize() && !batch.isEmpty()) {
                applySpeedLimit(batchBytes);

                PacketDistributor.sendToPlayer(serverPlayer,
                        new PacketHandler.SyncResponsePayload(new ArrayList<>(batch), false, worldId, "ok"));
                processed += batch.size();

                PacketDistributor.sendToPlayer(serverPlayer,
                        new PacketHandler.SyncProgressPayload(processed, total,
                                String.format("Sending regions %d/%d", processed, total)));

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
            return;
        }

        // Send final batch
        if (!batch.isEmpty()) {
            applySpeedLimit(batchBytes);

            PacketDistributor.sendToPlayer(serverPlayer,
                    new PacketHandler.SyncResponsePayload(new ArrayList<>(batch), true, worldId, "ok"));
            processed += batch.size();
        }

        PacketDistributor.sendToPlayer(serverPlayer,
                new PacketHandler.SyncProgressPayload(total, total, "completed"));

        serverPlayer.sendSystemMessage(ChatUtils.success("mapsyncer.server.sync_complete", total));
        LOGGER.info("Map sync complete for player {}: {} regions", serverPlayer.getName().getString(), total);

        syncingPlayers.remove(playerId);
        playerSyncDimensions.remove(playerId);
    }

    /**
     * 从zip文件添加区块数据
     *
     * @param diffs 差异数据列表
     * @param zipPath zip文件路径
     * @param normalizedPath 规范化的相对路径
     * @param timestampSeconds 时间戳（秒）
     */
    private static void addChunkData(List<ChunkMapData> diffs, Path zipPath, String normalizedPath, long timestampSeconds) {
        try {
            byte[] data = Files.readAllBytes(zipPath);
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
        LOGGER.info("ServerSyncHandler tracking data cleared");
    }
}