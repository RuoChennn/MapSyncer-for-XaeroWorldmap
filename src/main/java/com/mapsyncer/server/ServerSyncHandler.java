package com.mapsyncer.server;

import com.mapsyncer.client.ClientHashManager.ClientMeta;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.network.ChunkMapData;
import com.mapsyncer.network.PacketHandler;
import com.mapsyncer.server.GenerationCache.RegionMeta;
import com.mapsyncer.util.ChatUtils;
import com.mapsyncer.util.DimensionPathMapping;
import com.mapsyncer.util.HashUtils;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 服务端同步处理器 - Fabric 版本
 *
 * 处理客户端请求的地图数据同步。
 */
public class ServerSyncHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerSyncHandler.class);

    private static final int MAX_PACKET_SIZE_LIMIT = 1_000_000;

    private static int getMaxPacketSize() {
        int configValue = ModConfig.SERVER.maxSyncPacketSize;
        return Math.min(configValue, MAX_PACKET_SIZE_LIMIT);
    }

    private static int getBatchThreshold() {
        int limitKBps = ModConfig.SERVER.syncSpeedLimitKBps;
        if (limitKBps <= 0) {
            return getMaxPacketSize();
        }

        int maxPacketSize = getMaxPacketSize();
        int limitBytesPerSec = limitKBps * 1024;
        int packetsPerSecond = limitBytesPerSec / maxPacketSize;

        if (packetsPerSecond < 1) {
            packetsPerSecond = 1;
        }

        return packetsPerSecond * maxPacketSize;
    }

    private static int sendBatchInChunks(List<ChunkMapData> batch, int batchBytes,
            ServerPlayer serverPlayer, int worldId, int processed, int total) {
        int maxPacketSize = getMaxPacketSize();

        if (batchBytes <= maxPacketSize) {
            final List<ChunkMapData> batchToSend = new ArrayList<>(batch);
            serverPlayer.level().getServer().execute(() -> {
                ServerPlayNetworking.send(serverPlayer,
                        new PacketHandler.SyncResponsePayload(batchToSend, false, worldId, "ok"));
                ServerPlayNetworking.send(serverPlayer,
                        new PacketHandler.SyncProgressPayload(processed, total,
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
                serverPlayer.level().getServer().execute(() -> {
                    ServerPlayNetworking.send(serverPlayer,
                            new PacketHandler.SyncResponsePayload(chunkToSend, false, worldId, "ok"));
                    ServerPlayNetworking.send(serverPlayer,
                            new PacketHandler.SyncProgressPayload(sentProgress, total,
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
            serverPlayer.level().getServer().execute(() -> {
                ServerPlayNetworking.send(serverPlayer,
                        new PacketHandler.SyncResponsePayload(chunkToSend, false, worldId, "ok"));
                ServerPlayNetworking.send(serverPlayer,
                        new PacketHandler.SyncProgressPayload(sentProgress, total,
                                String.format("Sending regions %d/%d", sentProgress, total)));
            });
            packetCount++;
        }

        return packetCount;
    }

    private static final Set<UUID> syncingPlayers = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, ResourceKey<Level>> playerSyncDimensions = new ConcurrentHashMap<>();
    private static final Map<UUID, Thread> syncThreads = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> speedLimitBytesSent = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> speedLimitCycleStart = new ConcurrentHashMap<>();
    private static final long MAX_SPEED_LIMIT_CYCLE_MS = 1000;

    private record RegionSyncInfo(Path zipPath, String normalizedPath, long timestampSeconds,
                                   int regionX, int regionZ, String dimension, int caveLayer) {
        boolean isSurfaceLayer() {
            return caveLayer == Integer.MAX_VALUE;
        }
    }

    /**
     * 处理客户端同步请求
     */
    public static void handleSyncRequest(PacketHandler.SyncRequestPayload payload, ServerPlayer serverPlayer) {
        UUID playerId = serverPlayer.getUUID();

        Thread oldThread = syncThreads.get(playerId);
        if (oldThread != null && oldThread.isAlive()) {
            LOGGER.info("Player {} requested new sync while syncing, interrupting old sync", playerId);
            oldThread.interrupt();
            cleanupSyncState(playerId);
        }

        ResourceKey<Level> startDimension = serverPlayer.level().dimension();
        syncingPlayers.add(playerId);
        playerSyncDimensions.put(playerId, startDimension);

        Map<String, ClientMeta> clientMeta = payload.clientMeta();

        Thread syncThread = new Thread(() -> processSyncAsync(serverPlayer, playerId, clientMeta, startDimension),
                "mapsyncer-sync-" + playerId);
        syncThread.setDaemon(true);
        syncThreads.put(playerId, syncThread);
        syncThread.start();
        LOGGER.info("Started async sync thread for player {}", serverPlayer.getName().getString());
    }

    public static void onPlayerDisconnect(UUID playerId) {
        syncingPlayers.remove(playerId);
        playerSyncDimensions.remove(playerId);
        clearSpeedLimitState(playerId);

        Thread syncThread = syncThreads.remove(playerId);
        if (syncThread != null && syncThread.isAlive()) {
            syncThread.interrupt();
            LOGGER.info("Player {} disconnected, sync thread interrupted", playerId);
        }
    }

    private static boolean isPlayerStillValid(ServerPlayer player) {
        UUID playerId = player.getUUID();

        if (!syncingPlayers.contains(playerId) || player.connection == null) {
            return false;
        }

        ResourceKey<Level> startDimension = playerSyncDimensions.get(playerId);
        if (startDimension != null && !player.level().dimension().equals(startDimension)) {
            LOGGER.info("Player {} changed dimension from {} to {}, aborting sync",
                    playerId, startDimension.identifier(), player.level().dimension().identifier());
            syncingPlayers.remove(playerId);
            playerSyncDimensions.remove(playerId);
            return false;
        }

        return true;
    }

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

    private static boolean applySpeedLimit(int bytesSent, ServerPlayer player, UUID playerId) {
        int limitKBps = ModConfig.SERVER.syncSpeedLimitKBps;
        if (limitKBps <= 0) return true;

        Long cycleStart = speedLimitCycleStart.get(playerId);
        Long totalBytes = speedLimitBytesSent.get(playerId);

        if (cycleStart == null || totalBytes == null) {
            cycleStart = System.currentTimeMillis();
            totalBytes = 0L;
            speedLimitCycleStart.put(playerId, cycleStart);
            speedLimitBytesSent.put(playerId, totalBytes);
        }

        totalBytes += bytesSent;
        speedLimitBytesSent.put(playerId, totalBytes);

        long actualTimeMs = System.currentTimeMillis() - cycleStart;

        if (actualTimeMs > MAX_SPEED_LIMIT_CYCLE_MS) {
            speedLimitCycleStart.put(playerId, System.currentTimeMillis());
            speedLimitBytesSent.put(playerId, 0L);
            totalBytes = (long) bytesSent;
            speedLimitBytesSent.put(playerId, totalBytes);
            cycleStart = System.currentTimeMillis();
            actualTimeMs = 0;
        }

        long expectedTimeMs = (totalBytes * 1000L) / (limitKBps * 1024L);

        if (actualTimeMs >= expectedTimeMs) {
            speedLimitCycleStart.put(playerId, System.currentTimeMillis());
            speedLimitBytesSent.put(playerId, 0L);
            return true;
        }

        long remainingTimeMs = expectedTimeMs - actualTimeMs;
        long checkIntervalMs = 100;
        long waitStartTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - waitStartTime < remainingTimeMs) {
            if (!isPlayerStillValid(player)) {
                LOGGER.info("Player {} disconnected during speed limit wait, aborting sync", playerId);
                return false;
            }

            long waitRemainingMs = remainingTimeMs - (System.currentTimeMillis() - waitStartTime);
            long sleepMs = Math.min(checkIntervalMs, waitRemainingMs);

            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        speedLimitCycleStart.put(playerId, System.currentTimeMillis());
        speedLimitBytesSent.put(playerId, 0L);
        return true;
    }

    private static void clearSpeedLimitState(UUID playerId) {
        speedLimitBytesSent.remove(playerId);
        speedLimitCycleStart.remove(playerId);
    }

    private static void cleanupSyncState(UUID playerId) {
        syncingPlayers.remove(playerId);
        playerSyncDimensions.remove(playerId);
        syncThreads.remove(playerId);
        clearSpeedLimitState(playerId);
    }

    private static void processSyncAsync(ServerPlayer serverPlayer, UUID playerId,
            Map<String, ClientMeta> clientMeta, ResourceKey<Level> startDimension) {

        int worldId = readWorldIdFromXaeroMap(serverPlayer);
        LOGGER.info("Server worldId from xaeromap.txt: {}", worldId);

        GenerationCache genCache = GenerationCache.getInstance(ConversionOrchestrator.CACHE_DIR);
        Map<String, RegionMeta> serverCache = genCache.getAll();

        Path cacheDir = ConversionOrchestrator.CACHE_DIR;

        if (!Files.exists(cacheDir)) {
            serverPlayer.level().getServer().execute(() -> {
                serverPlayer.sendSystemMessage(ChatUtils.message("mapsyncer.server.no_cache"));
                ServerPlayNetworking.send(serverPlayer,
                        new PacketHandler.SyncResponsePayload(List.of(), true, worldId, "no_cache"));
            });
            cleanupSyncState(playerId);
            return;
        }

        int hashMatchCount = 0;
        int timestampSkipCount = 0;

        Set<String> requestedDimensions = new java.util.HashSet<>();
        for (String key : clientMeta.keySet()) {
            String[] parts = key.split("[/\\\\]");
            if (parts.length > 1) {
                String dim = parts[0];
                if (!key.contains("_placeholder_")) {
                    requestedDimensions.add(dim);
                } else {
                    requestedDimensions.add(dim);
                }
            }
        }
        LOGGER.info("Client requesting dimensions (Xaero format): {}", requestedDimensions);

        Set<String> skippedDimensions = new HashSet<>();
        DimensionPathMapping dimMapping = DimensionPathMapping.getInstance();
        boolean hasValidDimension = false;

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
            } else {
                String friendlyDim = dimMapping.toServerDimension(xaeroDim);
                serverPlayer.level().getServer().execute(() -> {
                    serverPlayer.sendSystemMessage(ChatUtils.error("mapsyncer.server.dim_not_available", friendlyDim, friendlyDim));
                });
                LOGGER.warn("Requested dimension {} (xaero: {}) has no cache data at {}", friendlyDim, xaeroDim, dimCacheDir);
            }
        }

        if (!hasValidDimension) {
            LOGGER.info("No valid dimension cache found for requested dimensions: {}", requestedDimensions);
            serverPlayer.level().getServer().execute(() -> {
                ServerPlayNetworking.send(serverPlayer,
                        new PacketHandler.SyncResponsePayload(List.of(), true, worldId, "dim_not_available"));
            });
            cleanupSyncState(playerId);
            return;
        }

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

                        boolean shouldSync = false;
                        long timestamp = 0;

                        if (serverMeta == null) {
                            String serverHash = HashUtils.computeFileHash(zipPath);
                            timestamp = System.currentTimeMillis() / 1000;

                            if (clientMetaEntry == null) {
                                shouldSync = true;
                            } else if (!serverHash.equals(clientMetaEntry.hash())) {
                                shouldSync = true;
                            }
                        } else {
                            if (clientMetaEntry == null) {
                                shouldSync = true;
                                timestamp = serverMeta.timestampSeconds();
                            } else if (!serverMeta.hash().equals(clientMetaEntry.hash())) {
                                if (clientMetaEntry.timestampSeconds() < serverMeta.timestampSeconds()) {
                                    shouldSync = true;
                                    timestamp = serverMeta.timestampSeconds();
                                }
                            }
                        }

                        if (shouldSync) {
                            RegionSyncInfo info = parseRegionInfo(zipPath, normalizedPath, timestamp);
                            if (info != null) {
                                regionsToSync.add(info);
                            }
                        }
                    });
        } catch (IOException e) {
            LOGGER.error("Failed to walk cache directory", e);
        }

        for (Map.Entry<String, RegionMeta> entry : serverCache.entrySet()) {
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

        LOGGER.info("Sync request from {}: {} regions to sync, {} hash match, {} timestamp skip",
                serverPlayer.getName().getString(), total, finalHashMatchCount, finalTimestampSkipCount);

        if (total == 0) {
            serverPlayer.level().getServer().execute(() -> {
                serverPlayer.sendSystemMessage(ChatUtils.success("mapsyncer.server.map_uptodate", finalHashMatchCount, finalTimestampSkipCount));
                ServerPlayNetworking.send(serverPlayer,
                        new PacketHandler.SyncResponsePayload(List.of(), true, worldId, "uptodate"));
            });
            cleanupSyncState(playerId);
            return;
        }

        sortByViewDistancePriority(regionsToSync, serverPlayer);

        final int initialTotal = total;
        serverPlayer.level().getServer().execute(() -> {
            ServerPlayNetworking.send(serverPlayer,
                    new PacketHandler.SyncProgressPayload(0, initialTotal, "Sync started"));
        });

        List<ChunkMapData> batch = new ArrayList<>();
        int batchBytes = 0;
        int processed = 0;
        int batchThreshold = getBatchThreshold();

        for (RegionSyncInfo info : regionsToSync) {
            if (!isPlayerStillValid(serverPlayer)) {
                LOGGER.info("Player {} disconnected during sync", playerId);
                cleanupSyncState(playerId);
                return;
            }

            ChunkMapData chunk = readRegionData(info);
            if (chunk == null) {
                LOGGER.warn("Failed to read region data: {}", info.normalizedPath());
                continue;
            }

            if (batchBytes + chunk.data.length > batchThreshold && !batch.isEmpty()) {
                if (!applySpeedLimit(batchBytes, serverPlayer, playerId)) {
                    LOGGER.info("Player {} disconnected during speed limit, aborting sync", playerId);
                    cleanupSyncState(playerId);
                    return;
                }

                sendBatchInChunks(batch, batchBytes, serverPlayer, worldId, processed, total);
                processed += batch.size();

                batch.clear();
                batchBytes = 0;
            }

            batch.add(chunk);
            batchBytes += chunk.data.length;
        }

        if (!isPlayerStillValid(serverPlayer)) {
            LOGGER.info("Player {} disconnected before final batch", playerId);
            cleanupSyncState(playerId);
            return;
        }

        if (!batch.isEmpty()) {
            if (!applySpeedLimit(batchBytes, serverPlayer, playerId)) {
                LOGGER.info("Player {} disconnected during final speed limit, aborting sync", playerId);
                cleanupSyncState(playerId);
                return;
            }

            int maxPacketSize = getMaxPacketSize();
            if (batchBytes <= maxPacketSize) {
                final List<ChunkMapData> finalBatch = new ArrayList<>(batch);
                final int finalTotal = total;
                serverPlayer.level().getServer().execute(() -> {
                    ServerPlayNetworking.send(serverPlayer,
                            new PacketHandler.SyncResponsePayload(finalBatch, true, worldId, "ok"));
                    ServerPlayNetworking.send(serverPlayer,
                            new PacketHandler.SyncProgressPayload(finalTotal, finalTotal, "completed"));
                    serverPlayer.sendSystemMessage(ChatUtils.success("mapsyncer.server.sync_complete", finalTotal));
                });
            } else {
                List<ChunkMapData> currentChunk = new ArrayList<>();
                int currentSize = 0;

                for (ChunkMapData chunk : batch) {
                    if (currentSize + chunk.data.length > maxPacketSize && !currentChunk.isEmpty()) {
                        final List<ChunkMapData> chunkToSend = new ArrayList<>(currentChunk);
                        final int sentProgress = processed;
                        serverPlayer.level().getServer().execute(() -> {
                            ServerPlayNetworking.send(serverPlayer,
                                    new PacketHandler.SyncResponsePayload(chunkToSend, false, worldId, "ok"));
                            ServerPlayNetworking.send(serverPlayer,
                                    new PacketHandler.SyncProgressPayload(sentProgress, total,
                                            String.format("Sending regions %d/%d", sentProgress, total)));
                        });
                        processed += currentChunk.size();
                        currentChunk.clear();
                        currentSize = 0;
                    }

                    currentChunk.add(chunk);
                    currentSize += chunk.data.length;
                }

                if (!currentChunk.isEmpty()) {
                    final List<ChunkMapData> lastChunk = new ArrayList<>(currentChunk);
                    final int finalTotal = total;
                    serverPlayer.level().getServer().execute(() -> {
                        ServerPlayNetworking.send(serverPlayer,
                                new PacketHandler.SyncResponsePayload(lastChunk, true, worldId, "ok"));
                        ServerPlayNetworking.send(serverPlayer,
                                new PacketHandler.SyncProgressPayload(finalTotal, finalTotal, "completed"));
                        serverPlayer.sendSystemMessage(ChatUtils.success("mapsyncer.server.sync_complete", finalTotal));
                    });
                }
            }
        } else {
            final int finalTotal = total;
            serverPlayer.level().getServer().execute(() -> {
                ServerPlayNetworking.send(serverPlayer,
                        new PacketHandler.SyncProgressPayload(finalTotal, finalTotal, "completed"));
                serverPlayer.sendSystemMessage(ChatUtils.success("mapsyncer.server.sync_complete", finalTotal));
            });
        }

        LOGGER.info("Map sync complete for player {}: {} regions", serverPlayer.getName().getString(), total);
        cleanupSyncState(playerId);
    }

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

    public static void cleanup() {
        syncingPlayers.clear();
        playerSyncDimensions.clear();
        syncThreads.clear();
        speedLimitBytesSent.clear();
        speedLimitCycleStart.clear();
        LOGGER.info("ServerSyncHandler tracking data cleared");
    }

    public static void cleanupOfflinePlayers(Set<UUID> onlinePlayerIds) {
        Set<UUID> toRemove = new HashSet<>();
        for (UUID playerId : syncingPlayers) {
            if (!onlinePlayerIds.contains(playerId)) {
                toRemove.add(playerId);
            }
        }

        for (UUID playerId : toRemove) {
            LOGGER.info("Cleaning up stale state for offline player {}", playerId);
            syncingPlayers.remove(playerId);
            playerSyncDimensions.remove(playerId);

            Thread syncThread = syncThreads.remove(playerId);
            if (syncThread != null && syncThread.isAlive()) {
                syncThread.interrupt();
            }

            clearSpeedLimitState(playerId);
        }

        if (!toRemove.isEmpty()) {
            LOGGER.debug("Cleaned up {} stale player states", toRemove.size());
        }
    }

    private static void sortByViewDistancePriority(List<RegionSyncInfo> regions, ServerPlayer player) {
        int playerChunkX = player.getBlockX() >> 4;
        int playerChunkZ = player.getBlockZ() >> 4;
        int playerRegionX = playerChunkX >> 5;
        int playerRegionZ = playerChunkZ >> 5;

        int viewDistanceChunks = player.level().getServer().getPlayerList().getViewDistance() + 2;
        int viewDistanceRegions = (viewDistanceChunks >> 5) + 1;

        regions.sort((a, b) -> {
            int distA = Math.max(Math.abs(a.regionX() - playerRegionX), Math.abs(a.regionZ() - playerRegionZ));
            int distB = Math.max(Math.abs(b.regionX() - playerRegionX), Math.abs(b.regionZ() - playerRegionZ));

            boolean aInView = distA <= viewDistanceRegions;
            boolean bInView = distB <= viewDistanceRegions;

            if (aInView && !bInView) return -1;
            if (!aInView && bInView) return 1;
            return Integer.compare(distA, distB);
        });
    }
}
