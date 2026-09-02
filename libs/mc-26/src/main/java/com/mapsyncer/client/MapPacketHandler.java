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
import com.mapsyncer.util.ClientMessageHelper;
import com.mapsyncer.util.DimensionPathMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
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
        MapSyncerCommandLogic.resetSyncRetry();
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

        // 服务端在线但首次响应超时时，自动重发同步请求（穿透丢包/分包未到齐兜底）
        SyncProgressTracker.setTimeoutCallback(MapSyncerCommandLogic::retryLastSyncRequest);

        // 注册服务端已安装通知处理器
        handler.registerServerInstalledHandler((payload, ctx) -> {
            ctx.enqueueWork(() -> {
                try {
                serverInstalled = true;
                serverVersion = payload.version();
                // 存储服务端统一标识名，供 XaeroMapIntegrator 复用同一地图缓存
                if (payload.serverName() != null && !payload.serverName().isEmpty()) {
                    ClientSyncSession.get().setServerName(payload.serverName());
                    LOGGER.info("Server identity name: {}", payload.serverName());
                } else {
                    LOGGER.info("Server did not configure serverName, will use automatic directory matching");
                }
                int intervalMinutes = payload.autoSyncIntervalMinutes();
                AutoSyncManager.configureFromServer(
                        payload.updateMode(), intervalMinutes, payload.incrementalUpdateIntervalTicks());
                LOGGER.info("Server has MapSyncer installed, version: {}, mode={}, intervalMinutes={}, joinAutoSync={}",
                        serverVersion, payload.updateMode(), intervalMinutes, intervalMinutes > 0);

                // 显示自动同步状态
                Object[] statusKey = AutoSyncManager.getStatusKey(intervalMinutes);
                String key = (String) statusKey[0];
                if (statusKey.length > 1) {
                    Minecraft.getInstance().player.sendSystemMessage(
                        ChatUtils.prefix().append(ChatUtils.desc(key, statusKey[1])));
                } else {
                    Minecraft.getInstance().player.sendSystemMessage(
                        ChatUtils.prefix().append(ChatUtils.desc(key)));
                }

                boolean shouldJoinSync = AutoSyncManager.shouldAutoSyncOnJoin(
                        payload.lastGenerationTimestamp(), intervalMinutes);
                LOGGER.info("shouldAutoSyncOnJoin result: {} (serverGenTime={}, intervalMinutes={})",
                        shouldJoinSync, payload.lastGenerationTimestamp(), intervalMinutes);

                // 多入口缓存复用：在后台线程执行（扫描+复制可能耗时），
                // 完成后回到主线程再触发进服自动同步，避免阻塞主线程导致"等待服务端响应"卡住
                handleMultiEntryCacheReuseAsync(
                        payload.serverName() != null ? payload.serverName() : "",
                        () -> {
                            Minecraft.getInstance().execute(() -> {
                                if (shouldJoinSync) {
                                    if (Minecraft.getInstance().player != null
                                            && !MapPacketHandler.isSyncInProgress()) {
                                        Minecraft.getInstance().player.sendSystemMessage(
                                            ChatUtils.prefix().append(ChatUtils.desc("mapsyncer.autosync.start")));
                                        AutoSyncManager.markStarted();
                                        MapSyncerCommandLogic.executeSyncAll(true);
                                    }
                                }
                            });
                        });

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
     * 多入口地图缓存复用：在后台线程执行，避免阻塞主线程导致"等待服务端响应"卡死。
     * 复制完成后回到主线程执行 onComplete 回调。
     */
    private static void handleMultiEntryCacheReuseAsync(String serverName, Runnable onComplete) {
        try {
            Minecraft mc = Minecraft.getInstance();
            Path gameDir = mc.gameDirectory.toPath();
            Path worldMapDir = XaeroMapIntegrator.getWorldMapDir(gameDir);
            ClientPacketListener connection = mc.getConnection();
            if (worldMapDir == null || !worldMapDir.toFile().exists() || connection == null) {
                if (onComplete != null) {
                    onComplete.run();
                }
                return;
            }
            ServerData serverData = connection.getServerData();
            if (serverData == null) {
                if (onComplete != null) {
                    onComplete.run();
                }
                return;
            }
            String currentIP = XaeroMapIntegrator.cleanServerIP(serverData.ip);
            if (currentIP == null || currentIP.isEmpty()) {
                if (onComplete != null) {
                    onComplete.run();
                }
                return;
            }

            final String serverNameFinal = serverName;
            final Path worldMapDirFinal = worldMapDir;
            final String currentIPFinal = currentIP;

            // 后台线程执行扫描 + 复制（可能耗时数秒到数十秒）
            Thread worker = new Thread(() -> {
                try {
                    handleMultiEntryCacheReuse(worldMapDirFinal, currentIPFinal, serverNameFinal);
                } catch (Exception e) {
                    LOGGER.warn("Background cache reuse failed: {}", e.getMessage());
                } finally {
                    if (onComplete != null) {
                        try {
                            Minecraft.getInstance().execute(onComplete);
                        } catch (Exception e) {
                            LOGGER.debug("Failed to schedule onComplete: {}", e.getMessage());
                        }
                    }
                }
            }, "mapsyncer-cache-reuse");
            worker.setDaemon(true);
            worker.start();
        } catch (Exception e) {
            LOGGER.warn("Failed to start background cache reuse: {}", e.getMessage());
            if (onComplete != null) {
                onComplete.run();
            }
        }
    }

    /**
     * 多入口地图缓存复用：如果之前通过其他 IP 连接过同一服务器，
     * 将旧 IP 目录的地图数据复制到新 IP 目录，避免重新下载。
     * <p>匹配策略（按优先级）：</p>
     * <ol>
     *   <li>服务端配置了 serverName → 匹配 mapsyncer_server_name.txt 标识</li>
     *   <li>未配置 serverName 或未匹配到 → 自动扫描所有 Multiplayer_ 目录，
     *       找包含最多 mw$ 地图数据的目录（按地图文件数/修改时间启发式）</li>
     * </ol>
     * <p>使用 CRC32 校验确保复制完整性，只复制新 IP 目录中不存在的文件。</p>
     */
    private static void handleMultiEntryCacheReuse(Path worldMapDir, String currentIP, String serverName) {
        try {
            Path currentIPDir = worldMapDir.resolve("Multiplayer_" + currentIP);

            boolean hasServerName = serverName != null && !serverName.isEmpty();

            // 1) 按 serverName 标识匹配旧 IP 目录
            Path sourceDir = null;
            if (hasServerName) {
                sourceDir = findSourceByServerName(worldMapDir, currentIP, serverName);
            }

            // 2) 自动匹配：按地图数据量启发式选择
            if (sourceDir == null) {
                sourceDir = findBestMapSource(worldMapDir, currentIP);
                if (sourceDir != null) {
                    LOGGER.info("Auto-matched previous map directory: {} (no serverName configured)",
                            sourceDir);
                }
            }

            if (sourceDir == null) {
                LOGGER.info("No previous IP directory found for serverName={}, skip cache reuse", serverName);
                // 首次连接，写入 serverName 标识文件
                writeServerNameIdentifier(currentIPDir, serverName);
                return;
            }

            // 3) 判断当前 IP 目录状态
            long currentCount = currentIPDir.toFile().exists() ? countMapFiles(currentIPDir) : 0L;
            long sourceCount = countMapFiles(sourceDir);

            if (currentCount >= sourceCount && currentCount > 0) {
                // 当前目录数据完整，无需操作
                LOGGER.debug("Current IP directory already has {} map files (source has {}), skip cache reuse",
                        currentCount, sourceCount);
                writeServerNameIdentifier(currentIPDir, serverName);
                return;
            }

            // 4) 零拷贝：重命名目录（而非复制）
            // 若 currentIPDir 存在但为空壳，先删除；然后重命名 sourceDir → currentIPDir
            LOGGER.info("Renaming map directory: {} ({} files) → {} (current has {} files)",
                    sourceDir, sourceCount, currentIPDir, currentCount);

            if (currentIPDir.toFile().exists()) {
                // 删除空壳目录
                try {
                    deleteDirectory(currentIPDir);
                    LOGGER.debug("Removed empty shell directory: {}", currentIPDir);
                } catch (Exception e) {
                    LOGGER.warn("Failed to remove empty shell directory {}: {}", currentIPDir, e.getMessage());
                    return;
                }
            }

            // 执行重命名（原子操作）
            try {
                Files.move(sourceDir, currentIPDir);
                LOGGER.info("Directory renamed successfully: {} → {}", sourceDir, currentIPDir);
            } catch (Exception e) {
                LOGGER.error("Failed to rename directory {} → {}: {}", sourceDir, currentIPDir, e.getMessage());
                return;
            }

            // 重命名后确保 serverName 标识文件存在
            writeServerNameIdentifier(currentIPDir, serverName);

        } catch (Exception e) {
            LOGGER.warn("Failed to reuse cache for serverName={}: {}", serverName, e.getMessage());
        }
    }

    /**
     * 按 serverName 标识文件匹配旧 IP 目录。
     */
    private static Path findSourceByServerName(Path worldMapDir, String currentIP, String serverName) {
        if (serverName == null || serverName.isEmpty()) {
            return null;
        }
        try (var stream = Files.list(worldMapDir)) {
            return stream
                    .filter(p -> p.getFileName().toString().startsWith("Multiplayer_"))
                    .filter(p -> !p.getFileName().toString().equals("Multiplayer_" + currentIP))
                    .filter(Files::isDirectory)
                    .filter(p -> {
                        // 检查该目录是否属于同一 serverName
                        Path nameFile = p.resolve("mapsyncer_server_name.txt");
                        if (nameFile.toFile().exists()) {
                            try {
                                String name = new String(Files.readAllBytes(nameFile), StandardCharsets.UTF_8).trim();
                                return serverName.equals(name);
                            } catch (Exception e) {
                                return false;
                            }
                        }
                        return false;
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            LOGGER.debug("findSourceByServerName failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 自动匹配最佳地图源目录：扫描所有 Multiplayer_ 目录，
     * 选择包含最多 mw$ 地图数据（zip 文件）的目录，作为最可能的同服务器地图源。
     */
    private static Path findBestMapSource(Path worldMapDir, String currentIP) {
        Path bestDir = null;
        long bestScore = -1;
        try (var stream = Files.list(worldMapDir)) {
            var dirs = stream
                    .filter(p -> p.getFileName().toString().startsWith("Multiplayer_"))
                    .filter(p -> !p.getFileName().toString().equals("Multiplayer_" + currentIP))
                    .filter(Files::isDirectory)
                    .toList();
            for (Path dir : dirs) {
                // 跳过明显的非地图目录（没有 null 维度目录的）
                Path nullDir = dir.resolve("null");
                if (!nullDir.toFile().exists()) {
                    continue;
                }
                long score = countMapFiles(dir);
                if (score > bestScore) {
                    bestScore = score;
                    bestDir = dir;
                }
            }
        } catch (IOException e) {
            LOGGER.debug("findBestMapSource failed: {}", e.getMessage());
        }
        if (bestDir != null && bestScore > 0) {
            LOGGER.debug("Best map source: {} with {} map files", bestDir, bestScore);
            return bestDir;
        }
        return null;
    }

    /**
     * 统计目录中的地图数据文件数量（.zip 文件，排除缓存目录）。
     * 只统计主世界 null/mw$* 下的数据（其他维度数据量少且遍历慢）。
     */
    private static long countMapFiles(Path dir) {
        long[] count = {0};
        try {
            // 只扫描主世界维度目录（Xaero 地图数据在 null/mw$<worldId>/ 下）
            Path nullDir = dir.resolve("null");
            if (!nullDir.toFile().exists()) {
                return 0;
            }
            Files.walkFileTree(nullDir, new SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (name.endsWith(".zip") && !name.endsWith(".zip.temp")) {
                        count[0]++;
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            LOGGER.debug("countMapFiles failed for {}: {}", dir, e.getMessage());
        }
        return count[0];
    }

    /**
     * 写入 serverName 标识文件，用于后续多入口缓存复用识别。
     */
    private static void writeServerNameIdentifier(Path serverDir, String serverName) {
        try {
            Files.createDirectories(serverDir);
            Path nameFile = serverDir.resolve("mapsyncer_server_name.txt");
            Files.write(nameFile, serverName.getBytes(StandardCharsets.UTF_8));
            LOGGER.debug("Wrote serverName identifier to {}", nameFile);
        } catch (Exception e) {
            LOGGER.warn("Failed to write serverName identifier: {}", e.getMessage());
        }
    }

    /**
     * 复制源目录到目标目录，使用 CRC32 校验跳过已存在且相同的文件。
     * 返回复制的文件数量。
     */
    private static long copyDirectoryWithCrc32Check(Path source, Path target) throws IOException {
        java.util.zip.CRC32 crc32 = new java.util.zip.CRC32();
        final long[] copied = {0};

        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(dir);
                Path targetDir = target.resolve(relative);
                Files.createDirectories(targetDir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(file);
                Path targetFile = target.resolve(relative);

                // 跳过 serverName 标识文件（每个 IP 目录独立）
                if (file.getFileName().toString().equals("mapsyncer_server_name.txt")) {
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                // 如果目标文件已存在，比较 CRC32
                if (targetFile.toFile().exists()) {
                    String srcHash = computeCrc32(file, crc32);
                    String tgtHash = computeCrc32(targetFile, crc32);
                    if (srcHash.equals(tgtHash)) {
                        return java.nio.file.FileVisitResult.CONTINUE; // 相同，跳过
                    }
                }

                // 复制文件
                Files.copy(file, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                copied[0]++;
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });

        return copied[0];
    }

    /**
     * 计算文件的 CRC32 哈希值。
     */
    private static String computeCrc32(Path file, java.util.zip.CRC32 crc32) throws IOException {
        crc32.reset();
        byte[] data = Files.readAllBytes(file);
        crc32.update(data, 0, data.length);
        return Long.toHexString(crc32.getValue());
    }

    /**
     * 递归删除目录及其内容。
     */
    private static void deleteDirectory(Path dir) throws IOException {
        if (!dir.toFile().exists()) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
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
                MapSyncerCommandLogic.resetSyncRetry();
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
                    Minecraft.getInstance().player.sendSystemMessage(ChatUtils.error("mapsyncer.sync.timeout"));
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
                        Minecraft.getInstance().player.sendSystemMessage(
                                ChatUtils.error("mapsyncer.sync.reflection_failed"));
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
        MapSyncerCommandLogic.resetSyncRetry();

        if (!updatedRegionCoords.isEmpty()) {
            XaeroMapDataHandler.recordUpdatedRegionCoords(updatedRegionCoords);
            SyncProgressTracker.completeWithCount(totalReceived);

            if (AutoSyncManager.isActive()) {
                AutoSyncManager.markComplete();
                if (Minecraft.getInstance().player != null) {
                    Minecraft.getInstance().player.sendSystemMessage(
                            ChatUtils.success("mapsyncer.autosync.complete"));
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
                Minecraft.getInstance().player.sendSystemMessage(
                        ChatUtils.error("mapsyncer.sync.reflection_failed"));
            } else {
                Minecraft.getInstance().player.sendSystemMessage(
                        ChatUtils.error("mapsyncer.sync.partial"));
            }
        }
    }

    /**
     * 处理服务端发送的进度更新数据包。
     */
    private static void handleProgressUpdate(SyncProgressPayload payload, PayloadContext context) {
        context.enqueueWork(() -> {
            String status = payload.status();
            if (status != null && status.startsWith("request_")) {
                // 服务端分包未到齐（穿透丢包）通知 → 取消当前跟踪并重发整个请求
                SyncProgressTracker.cancelTracking();
                MapSyncerCommandLogic.retryLastSyncRequest();
                return;
            }
            if (status != null && status.startsWith("aborted")) {
                SyncProgressTracker.cancelTracking();
                MapPacketHandler.clearSyncData();
                MapSyncerCommandLogic.resetSyncRetry();
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    if (status.contains("timeout")) {
                        ClientMessageHelper.sendChatMessage(
                                ChatUtils.error("mapsyncer.sync.server_timeout"));
                    } else {
                        ClientMessageHelper.sendChatMessage(
                                ChatUtils.error("mapsyncer.sync.cancelled"));
                    }
                }
                return;
            }

            // 自动同步同样显示进度（action bar 展示），完成提示由 handleSyncResponse 单独处理
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

    /** 进服自动同步结束（含地图已是最新）：显示唯一完成提示 */
    private static void finishJoinAutoSyncIfActive() {
        if (!AutoSyncManager.isActive()) {
            return;
        }
        AutoSyncManager.markComplete();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            ClientMessageHelper.sendChatMessage(
                    ChatUtils.success("mapsyncer.autosync.complete"));
        }
    }
}
