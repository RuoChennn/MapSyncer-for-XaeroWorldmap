package com.mapsyncer.server;

import com.mapsyncer.client.ClientHashManager.ClientMeta;
import com.mapsyncer.config.ModConfig;
import com.mapsyncer.network.ChunkMapData;
import com.mapsyncer.network.PacketHandler;
import com.mapsyncer.server.GenerationCache.RegionMeta;
import net.minecraft.network.chat.Component;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Note: This class is manually registered in the main mod class via modBus.addListener()
// because RegisterPayloadHandlersEvent is a MOD bus event.
public class ServerSyncHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerSyncHandler.class);
    // Maximum packet size ~1MB to avoid "Packet too large" error
    private static final int MAX_PACKET_SIZE = 1_000_000;

    // Track players currently syncing (to abort on disconnect or dimension change)
    private static final Set<UUID> syncingPlayers = ConcurrentHashMap.newKeySet();

    // Track player's dimension at sync start (to abort on dimension change)
    private static final Map<UUID, ResourceKey<Level>> playerSyncDimensions = new ConcurrentHashMap<>();

    // Track sync progress for resume capability (playerId -> SyncProgress)
    private static final Map<UUID, SyncProgress> playerSyncProgress = new ConcurrentHashMap<>();

    /**
     * Tracks a player's sync progress for resume capability.
     * NOTE: We only store metadata (total count, start index) to minimize memory usage.
     * The actual chunk data is computed on-demand rather than cached.
     * This prevents memory leaks when sync is interrupted.
     */
    public static class SyncProgress {
        public final int totalChunks;      // Total chunks to sync
        public final int startIndex;       // Where to resume from
        public final int worldId;
        public final long startTime;
        public final long lastActivityTime; // Last successful packet sent time

        public SyncProgress(int totalChunks, int startIndex, int worldId) {
            this.totalChunks = totalChunks;
            this.startIndex = startIndex;
            this.worldId = worldId;
            this.startTime = System.currentTimeMillis();
            this.lastActivityTime = System.currentTimeMillis();
        }

        /**
         * Check if this sync progress is stale (no activity for too long).
         * Stale progress will be cleared to prevent memory leaks.
         */
        public boolean isStale(long timeoutMs) {
            return System.currentTimeMillis() - lastActivityTime > timeoutMs;
        }
    }

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
     * Called when a player disconnects. Marks sync as interrupted but preserves progress for resume.
     */
    public static void onPlayerDisconnect(UUID playerId) {
        if (syncingPlayers.remove(playerId)) {
            playerSyncDimensions.remove(playerId);
            LOGGER.info("Player {} disconnected, sync paused (progress preserved for resume)", playerId);
        }
    }

    /**
     * Check if a player is still connected, in sync session, and in same dimension.
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
     * Read worldId from xaeromap.txt file.
     * File location: <world>/xaeromap.txt
     * Format: id:<number>
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
     * Calculate sleep time based on data sent to enforce speed limit.
     * @param bytesSent Number of bytes sent in this batch
     */
    private static void applySpeedLimit(int bytesSent) {
        int limitKBps = ModConfig.COMMON.syncSpeedLimitKBps.get();
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
            serverPlayer.sendSystemMessage(Component.literal("No map cache available. Run /mapsyncer generate first."));
            PacketDistributor.sendToPlayer(serverPlayer,
                    new PacketHandler.SyncResponsePayload(List.of(), true, worldId));
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
        Set<String> requestedDimensions = new java.util.HashSet<>();
        for (String key : clientMeta.keySet()) {
            String[] parts = key.split("[/\\\\]");
            if (parts.length > 1) {
                String dim = parts[0];
                // Skip placeholder entries (used when client has no local data)
                if (!key.contains("_placeholder_")) {
                    requestedDimensions.add(dim);  // First part is dimension name
                } else {
                    // Placeholder indicates client wants full sync for this dimension
                    requestedDimensions.add(dim);
                    LOGGER.debug("Found placeholder for dimension {}, will sync all regions", dim);
                }
            }
        }
        LOGGER.info("Client requesting dimensions: {}", requestedDimensions);

        // 检查请求的维度是否有缓存数据
        for (String requestedDim : requestedDimensions) {
            Path dimCacheDir = cacheDir.resolve(requestedDim);
            if (!Files.exists(dimCacheDir) || !dimCacheDir.toFile().isDirectory()) {
                serverPlayer.sendSystemMessage(Component.literal(
                        String.format("Dimension '%s' map data not available. Run /mapsyncer generate %s first.",
                                requestedDim, requestedDim)));
                LOGGER.warn("Requested dimension {} has no cache data at {}", requestedDim, dimCacheDir);
                // 继续处理其他维度，而不是直接返回
            }
        }

        try {
            Files.walk(cacheDir)
                    .filter(p -> p.toString().endsWith(".zip"))
                    .forEach(zipPath -> {
                        // Convert path to relative format: dimension/regionX_regionZ
                        String relativePath = cacheDir.relativize(zipPath).toString();
                        // Remove .zip extension and normalize path separator
                        String normalizedPath = relativePath.replace(".zip", "").replace("\\", "/");

                        // Parse dimension from path
                        String[] parts = normalizedPath.split("[/\\\\]");
                        String dimension = parts.length > 1 ? parts[0] : "unknown";

                        // Skip if client didn't request this dimension
                        if (!requestedDimensions.contains(dimension)) {
                            LOGGER.debug("Skipping {}: dimension {} not requested by client", normalizedPath, dimension);
                            // dimensionSkipCount would need to be AtomicInteger for lambda
                            return;
                        }

                        RegionMeta serverMeta = serverCache.get(normalizedPath);
                        ClientMeta clientMetaEntry = clientMeta.get(normalizedPath);

                        // Server has no record → skip (shouldn't happen if generate ran)
                        if (serverMeta == null) {
                            LOGGER.debug("Skipping {}: no server cache entry", normalizedPath);
                            return;
                        }

                        // Client has no metadata → sync (new region for client)
                        if (clientMetaEntry == null) {
                            LOGGER.debug("Will sync {}: client has no metadata (new region)", normalizedPath);
                            addChunkData(diffs, zipPath, normalizedPath, serverMeta.timestampSeconds());
                            return;
                        }

                        // Hash match → skip sync (file content identical)
                        if (serverMeta.hash().equals(clientMetaEntry.hash())) {
                            LOGGER.debug("Skipping {}: hash match (server={}, client={})",
                                    normalizedPath, serverMeta.hash(), clientMetaEntry.hash());
                            return;  // hashMatchCount incremented outside lambda
                        }

                        // Hash mismatch → check timestamps
                        // Client timestamp older than server → sync
                        if (clientMetaEntry.timestampSeconds() < serverMeta.timestampSeconds()) {
                            LOGGER.debug("Will sync {}: hash mismatch, client ts={}s < server ts={}s",
                                    normalizedPath, clientMetaEntry.timestampSeconds(), serverMeta.timestampSeconds());
                            addChunkData(diffs, zipPath, normalizedPath, serverMeta.timestampSeconds());
                        } else {
                            // Client timestamp newer → skip (client explored newer content)
                            LOGGER.debug("Skipping {}: hash mismatch but client ts={}s >= server ts={}s",
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
            serverPlayer.sendSystemMessage(Component.literal(
                    String.format("Map is up-to-date. %d hash match, %d timestamp skip.", hashMatchCount, timestampSkipCount)));
            PacketDistributor.sendToPlayer(serverPlayer,
                    new PacketHandler.SyncResponsePayload(List.of(), true, worldId));
            syncingPlayers.remove(playerId);
            playerSyncDimensions.remove(playerId);
            playerSyncProgress.remove(playerId);
            return;
        }

        serverPlayer.sendSystemMessage(Component.literal(
                String.format("Starting map sync: %d regions to download (%d identical, %d newer on client skipped)",
                        total, hashMatchCount, timestampSkipCount)));

        // Check if this is a resumed sync
        SyncProgress existingProgress = playerSyncProgress.get(playerId);
        int startIndex = 0;
        if (existingProgress != null && ModConfig.COMMON.enableResumeSync.get()) {
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
            if (batchSize + chunk.data.length > MAX_PACKET_SIZE && !batch.isEmpty()) {
                // Apply speed limit before sending
                applySpeedLimit(batchBytes);

                // Send current batch
                PacketDistributor.sendToPlayer(serverPlayer,
                        new PacketHandler.SyncResponsePayload(new ArrayList<>(batch), false, worldId));
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
                    new PacketHandler.SyncResponsePayload(new ArrayList<>(batch), true, worldId));
            processed += batch.size();
        }

        PacketDistributor.sendToPlayer(serverPlayer,
                new PacketHandler.SyncProgressPayload(total, total, "completed"));

        serverPlayer.sendSystemMessage(
                Component.literal(String.format("Map sync complete: %d regions sent", total)));
        LOGGER.info("Map sync complete for player {}: {} regions",
                serverPlayer.getName().getString(), total);

        // Remove from tracking sets
        syncingPlayers.remove(playerId);
        playerSyncDimensions.remove(playerId);
        playerSyncProgress.remove(playerId);
    }

    /**
     * Helper to add chunk data from zip file.
     */
    private static void addChunkData(List<ChunkMapData> diffs, Path zipPath, String normalizedPath, long timestampSeconds) {
        try {
            byte[] data = Files.readAllBytes(zipPath);
            // Parse dimension and coordinates from path
            String[] parts = normalizedPath.split("[/\\\\]");
            String fileName = parts[parts.length - 1];
            String[] coords = fileName.split("_");
            int regionX = Integer.parseInt(coords[0]);
            int regionZ = Integer.parseInt(coords[1]);
            String dimension = parts.length > 1 ? parts[parts.length - 2] : "null";
            diffs.add(new ChunkMapData(regionX, regionZ, dimension, data, timestampSeconds));
        } catch (IOException e) {
            LOGGER.error("Failed to read zip file: {}", zipPath, e);
        }
    }

    /**
     * Clear all tracking data. Called when server stops to prevent memory leaks.
     * Also clears stale progress entries that may have accumulated.
     */
    public static void cleanup() {
        syncingPlayers.clear();
        playerSyncDimensions.clear();
        playerSyncProgress.clear();
        LOGGER.info("ServerSyncHandler tracking data cleared");
    }

    /**
     * Clear stale sync progress entries (those with no activity for >5 minutes).
     * This prevents memory leaks from abandoned sync sessions.
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